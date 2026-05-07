package com.sentinel.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the patch approval gate:
 * <ul>
 *   <li>{@code GET /api/patches/by-investigation/{investigationId}} — fetch the
 *       most recent proposed patch (used by the dashboard diff viewer)</li>
 *   <li>{@code POST /api/patches/{id}/approve} — PENDING_REVIEW → APPLYING → APPLIED|ROLLED_BACK</li>
 *   <li>{@code POST /api/patches/{id}/reject}  — PENDING_REVIEW → REJECTED</li>
 *   <li>{@code POST /api/patches/{id}/rollback} — APPLIED → ROLLED_BACK (revert disk + restore backup)</li>
 * </ul>
 *
 * <h3>Authentication</h3>
 * The two state-changing POSTs (approve, reject) require a {@code Bearer} token
 * matching the {@code agent.patch.secret} property. This must be set — there
 * is no "blank means disabled" mode like the webhook secret has, because the
 * approval flow can write to the lab-rat source tree (Phase 2). A blank token
 * fails closed. Read-only endpoints (GET by-investigation, GET stats) remain
 * unauthenticated for the dashboard's diff viewer.
 */
@RestController
@RequestMapping("/api/patches")
public class PatchController {

    @Value("${agent.patch.secret:}")
    private String patchSecret;

    private final ProposedPatchRepository repository;
    private final PatchApplier patchApplier;

    public PatchController(ProposedPatchRepository repository, PatchApplier patchApplier) {
        this.repository = repository;
        this.patchApplier = patchApplier;
    }

    /**
     * Authorization gate for approve/reject/rollback. Returns null when the
     * caller is authorized, or a {@code ResponseEntity<Void>} with the right
     * 401/503 status when not. Fails closed if the secret is unset — the
     * applier must never run without an approver identity behind it.
     */
    private ResponseEntity<ProposedPatch> requireAuth(String authHeader) {
        if (patchSecret == null || patchSecret.isBlank()) {
            // Fail closed — see class Javadoc.
            return ResponseEntity.status(503)
                    .header("X-Reason", "agent.patch.secret not configured; patch endpoints disabled")
                    .build();
        }
        if (authHeader == null || !("Bearer " + patchSecret).equals(authHeader)) {
            return ResponseEntity.status(401).build();
        }
        return null;
    }

    @GetMapping("/by-investigation/{investigationId}")
    public ResponseEntity<ProposedPatch> getByInvestigation(@PathVariable UUID investigationId) {
        return repository.findFirstByInvestigationIdOrderByCreatedAtDesc(investigationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<ProposedPatch> approve(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        ResponseEntity<ProposedPatch> denied = requireAuth(authHeader);
        if (denied != null) return denied;
        return repository.findById(id)
                .map(patch -> {
                    if (!"PENDING_REVIEW".equals(patch.getStatus())) {
                        return ResponseEntity.status(409)
                                .header("X-Reason", "approve only valid from PENDING_REVIEW, current=" + patch.getStatus())
                                .body(patch);
                    }
                    // State machine: PENDING_REVIEW → APPLYING → (APPLIED | ROLLED_BACK).
                    // The intermediate APPLYING is brief but explicit so the dashboard
                    // can show "applying..." while the file write + DevTools restart
                    // happen, and so a crash mid-apply leaves a recognisable state.
                    patch.setStatus("APPLYING");
                    patch.setDecidedAt(Instant.now());
                    repository.save(patch);

                    PatchApplier.Result applyResult = patchApplier.apply(patch);
                    if (applyResult.success()) {
                        patch.setStatus("APPLIED");
                        System.out.println("[Sentinel] Patch " + id + " APPLIED to " + patch.getFilePath());
                    } else {
                        patch.setStatus("ROLLED_BACK");
                        System.err.println("[Sentinel] Patch " + id + " apply FAILED, rolled back: "
                                + applyResult.message());
                    }
                    ProposedPatch saved = repository.save(patch);
                    return applyResult.success()
                            ? ResponseEntity.ok(saved)
                            : ResponseEntity.status(500)
                                    .header("X-Reason", applyResult.message())
                                    .body(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<ProposedPatch> reject(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        ResponseEntity<ProposedPatch> denied = requireAuth(authHeader);
        if (denied != null) return denied;
        return decide(id, "REJECTED", "PENDING_REVIEW");
    }

    @PostMapping("/{id}/rollback")
    @Transactional
    public ResponseEntity<ProposedPatch> rollback(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        ResponseEntity<ProposedPatch> denied = requireAuth(authHeader);
        if (denied != null) return denied;
        return repository.findById(id)
                .map(patch -> {
                    if (!"APPLIED".equals(patch.getStatus())) {
                        return ResponseEntity.status(409)
                                .header("X-Reason", "rollback only valid from APPLIED, current=" + patch.getStatus())
                                .body(patch);
                    }
                    PatchApplier.Result result = patchApplier.rollback(patch);
                    if (!result.success()) {
                        return ResponseEntity.status(500)
                                .header("X-Reason", result.message())
                                .body(patch);
                    }
                    patch.setStatus("ROLLED_BACK");
                    patch.setDecidedAt(Instant.now());
                    ProposedPatch saved = repository.save(patch);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Atomically transitions a patch from {@code expectedFromStatus} to
     * {@code newStatus} and stamps {@code decidedAt}. Returns 404 if the patch
     * doesn't exist, 409 if it's no longer in the expected state (already
     * approved/rejected — guards against double-clicks and replayed POSTs).
     */
    private ResponseEntity<ProposedPatch> decide(UUID id, String newStatus, String expectedFromStatus) {
        return repository.findById(id)
                .map(patch -> {
                    if (!expectedFromStatus.equals(patch.getStatus())) {
                        return ResponseEntity.status(409)
                                .header("X-Reason", "patch is " + patch.getStatus() + ", not " + expectedFromStatus)
                                .body(patch);
                    }
                    patch.setStatus(newStatus);
                    patch.setDecidedAt(Instant.now());
                    ProposedPatch saved = repository.save(patch);
                    System.out.println("[Sentinel] Patch " + id + " transitioned to " + newStatus
                            + " for investigation " + patch.getInvestigationId());
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lightweight diagnostic — returns counts by status. Useful for the
     * dashboard's status pill and for ad-hoc "how many patches has Sentinel
     * proposed today?" queries during demos.
     */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return repository.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ProposedPatch::getStatus, java.util.stream.Collectors.counting()));
    }
}
