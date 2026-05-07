package com.sentinel.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook receiver for Prometheus AlertManager.
 */
@RestController
@RequestMapping("/webhook/prometheus")
public class AlertController {

    @Value("${agent.webhook.secret:}")
    private String webhookSecret;

    private final SreAgent sreAgent;
    private final TaskExecutor investigationExecutor;
    private final InvestigationService investigationService;

    public AlertController(SreAgent sreAgent,
                           TaskExecutor investigationExecutor,
                           InvestigationService investigationService) {
        this.sreAgent = sreAgent;
        this.investigationExecutor = investigationExecutor;
        this.investigationService = investigationService;
    }

    @PostMapping
    public ResponseEntity<Void> receiveAlert(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        if (!webhookSecret.isBlank() && !("Bearer " + webhookSecret).equals(authHeader)) {
            return ResponseEntity.status(401).build();
        }

        System.out.println("Received Prometheus AlertManager Webhook");

        String status = (String) payload.get("status");
        String alertNameForResolve = extractSafeString(payload, "commonLabels", "alertname", "Unknown Alert");

        // Resolved webhooks aren't a no-op anymore — they close the active
        // episode so the next firing webhook for this alert spawns a fresh
        // investigation instead of being deduped onto a stale row.
        if ("resolved".equals(status)) {
            System.out.println("[Sentinel] Alert RESOLVED for '" + alertNameForResolve
                    + "'. Closing active episode.");
            investigationService.resolveEpisode(alertNameForResolve);
            return ResponseEntity.ok().build();
        }

        if (!"firing".equals(status)) {
            System.out.println("[Sentinel] Alert status: " + status + ". Ignoring.");
            return ResponseEntity.ok().build();
        }

        System.out.println("[Sentinel] Alert is FIRING. Dispatching AI SRE...");

        // Extract only structured, known fields — never pass raw payload strings to the LLM
        // to prevent prompt injection via attacker-controlled alert annotations.
        // AlertManager webhook v4 nests labels under "commonLabels" and annotations under "commonAnnotations".
        String alertName = alertNameForResolve;
        String severity  = extractSafeString(payload, "commonLabels", "severity",  "unknown");
        String summary   = extractSafeString(payload, "commonAnnotations", "summary", "");
        String incidentDescription = "Alert: " + alertName
                + " | Severity: " + severity
                + (summary.isEmpty() ? "" : " | Summary: " + summary);

        // Persist (or reuse) the investigation. AlertManager re-sends still-firing
        // alerts at its repeat_interval; the dedupe inside InvestigationService.start
        // collapses those repeats onto a single investigation row.
        InvestigationService.StartResult startResult = investigationService.start(alertName, severity);
        if (startResult.wasReused()) {
            System.out.println("[Sentinel] Webhook is a continuation of investigation "
                    + startResult.investigation().getId() + " — skipping AI dispatch.");
            return ResponseEntity.ok().build();
        }

        Investigation investigation = startResult.investigation();
        investigationExecutor.execute(() -> {
            // MemoryIdContext binds the active investigation id to this worker
            // thread so downstream tools (proposeFix) and the BudgetedChatModel
            // can attribute their work back to the right investigation row.
            // Cleared in finally to avoid leaking a stale id onto a pooled thread.
            MemoryIdContext.set(investigation.getId().toString());
            try {
                String result = sreAgent.investigate(investigation.getId().toString(), incidentDescription);
                System.out.println("[Sentinel] Investigation [" + investigation.getId() + "] complete:\n" + result);
                investigationService.complete(investigation.getId(), result);
            } catch (Exception e) {
                System.err.println("[Sentinel] Investigation [" + investigation.getId() + "] failed: " + e.getMessage());
                investigationService.fail(investigation.getId(), e.getMessage());
            } finally {
                MemoryIdContext.clear();
            }
        });

        return ResponseEntity.ok().build();
    }

    // AlertManager webhook v4 payload structure:
    // { "status": "firing", "commonLabels": { "alertname": "...", "severity": "..." },
    //   "commonAnnotations": { "summary": "..." }, "alerts": [...] }
    private String extractSafeString(Map<String, Object> payload, String mapKey, String key, String fallback) {
        Object subMap = payload.get(mapKey);
        if (subMap instanceof Map<?,?> m) {
            Object val = m.get(key);
            if (val instanceof String s) return s;
        }
        return fallback;
    }
}
