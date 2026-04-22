package com.sentinel.agent;

import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook receiver for Prometheus AlertManager.
 */
@RestController
@RequestMapping("/webhook/prometheus")
public class AlertController {

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
    public void receiveAlert(@RequestBody Map<String, Object> payload) {
        System.out.println("Received Prometheus AlertManager Webhook");

        String status = (String) payload.get("status");
        if (!"firing".equals(status)) {
            System.out.println("[Sentinel] Alert status: " + status + ". Ignoring.");
            return;
        }

        System.out.println("[Sentinel] Alert is FIRING. Dispatching AI SRE...");

        // Extract only structured, known fields — never pass raw payload strings to the LLM
        // to prevent prompt injection via attacker-controlled alert annotations.
        // AlertManager webhook v4 nests labels under "commonLabels" and annotations under "commonAnnotations".
        String alertName = extractSafeString(payload, "commonLabels", "alertname", "Unknown Alert");
        String severity  = extractSafeString(payload, "commonLabels", "severity",  "unknown");
        String summary   = extractSafeString(payload, "commonAnnotations", "summary", "");
        String incidentDescription = "Alert: " + alertName
                + " | Severity: " + severity
                + (summary.isEmpty() ? "" : " | Summary: " + summary);

        // Persist the investigation immediately so the dashboard can show it as PENDING.
        Investigation investigation = investigationService.start(alertName, severity);

        investigationExecutor.execute(() -> {
            try {
                String result = sreAgent.investigate(investigation.getId().toString(), incidentDescription);
                System.out.println("[Sentinel] Investigation [" + investigation.getId() + "] complete:\n" + result);
                investigationService.complete(investigation.getId(), result);
            } catch (Exception e) {
                System.err.println("[Sentinel] Investigation [" + investigation.getId() + "] failed: " + e.getMessage());
                investigationService.fail(investigation.getId(), e.getMessage());
            }
        });
    }

    // AlertManager webhook v4 payload structure:
    // { "status": "firing", "commonLabels": { "alertname": "...", "severity": "..." },
    //   "commonAnnotations": { "summary": "..." }, "alerts": [...] }
    @SuppressWarnings("unchecked")
    private String extractSafeString(Map<String, Object> payload, String mapKey, String key, String fallback) {
        Object subMap = payload.get(mapKey);
        if (subMap instanceof Map<?,?> m) {
            Object val = m.get(key);
            if (val instanceof String s) return s;
        }
        return fallback;
    }
}
