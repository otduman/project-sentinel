package com.sentinel.agent;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the Phase-1 approval gate:
 * <ul>
 *   <li>{@code GET /api/patches/by-investigation/{investigationId}} — fetch the
 *       most recent proposed patch (used by the dashboard diff viewer)</li>
 *   <li>{@code POST /api/patches/{id}/approve} — flip status PENDING_REVIEW → APPROVED</li>
 *   <li>{@code POST /api/patches/{id}/reject}  — flip status PENDING_REVIEW → REJECTED</li>
 * </ul>
 *
 * <p>Phase 1 is record-only — APPROVED <em>does not</em> trigger a file write.
 * Phase 2 will introduce a {@code PatchApplier} that consumes APPROVED rows
 * and runs the actual filesystem update + Spring DevTools restart.
 */
@RestController
@RequestMapping("/api/patches")
public class PatchController {

    private final ProposedPatchRepository repository;

    public PatchController(ProposedPatchRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/by-investigation/{investigationId}")
    public ResponseEntity<ProposedPatch> getByInvestigation(@PathVariable UUID investigationId) {
        return repository.findFirstByInvestigationIdOrderByCreatedAtDesc(investigationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<ProposedPatch> approve(@PathVariable UUID id) {
        return decide(id, "APPROVED", "PENDING_REVIEW");
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<ProposedPatch> reject(@PathVariable UUID id) {
        return decide(id, "REJECTED", "PENDING_REVIEW");
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
