package com.sentinel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the lifecycle of {@link Investigation} entities and broadcasts
 * real-time status updates to all connected SSE subscribers.
 *
 * <p>Status transitions:
 * <pre>
 *   start()    -> PENDING  -> (AlertController transitions to) INVESTIGATING
 *   complete() -> COMPLETE
 *   fail()     -> FAILED
 * </pre>
 */
@Service
public class InvestigationService {

    // Matches section headers produced by Gemini in its free-text reports.
    // Handles bold (**), heading (#), and plain variants, with or without trailing colon.
    private static final Pattern SECTION = Pattern.compile(
            "(?i)(?:\\*{1,2}|#{1,3}\\s*)?(Symptoms|Evidence|Root\\s*Cause|Proposed\\s*Fix)(?:\\*{1,2})?\\s*:?\\s*",
            Pattern.CASE_INSENSITIVE
    );

    private final InvestigationRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Thread-safe list of active SSE connections.
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public InvestigationService(InvestigationRepository repository) {
        this.repository = repository;
    }

    @PreDestroy
    public void shutdown() {
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }

    // -------------------------------------------------------------------------
    // Lifecycle methods
    // -------------------------------------------------------------------------

    /**
     * Persists a new PENDING investigation and notifies subscribers.
     */
    public Investigation start(String alertName, String severity) {
        Investigation inv = Investigation.create(alertName, severity);
        inv = repository.save(inv);
        broadcast("investigation_started", inv);
        return inv;
    }

    /**
     * Parses the LLM report into structured sections, marks the investigation
     * COMPLETE, and notifies subscribers.
     */
    @Transactional
    public void complete(UUID id, String rawReport) {
        repository.findById(id).ifPresent(inv -> {
            Map<String, String> sections = parseReport(rawReport);
            inv.setSymptoms(sections.getOrDefault("symptoms", null));
            inv.setEvidence(sections.getOrDefault("evidence", null));
            inv.setRootCause(sections.getOrDefault("rootcause", null));
            inv.setProposedFix(sections.getOrDefault("proposedfix", null));
            inv.setStatus("COMPLETE");
            inv.setCompletedAt(Instant.now());
            Investigation saved = repository.save(inv);
            broadcast("investigation_complete", saved);
        });
    }

    /**
     * Marks the investigation FAILED and notifies subscribers.
     */
    @Transactional
    public void fail(UUID id, String error) {
        repository.findById(id).ifPresent(inv -> {
            inv.setStatus("FAILED");
            inv.setCompletedAt(Instant.now());
            // Store the error message in symptoms so the dashboard can display it.
            inv.setSymptoms("Investigation failed: " + error);
            Investigation saved = repository.save(inv);
            broadcast("investigation_failed", saved);
        });
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /**
     * Returns investigations started within the last 30 days, newest first.
     */
    public List<Investigation> getLast30Days() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        return repository.findByStartedAtAfterOrderByStartedAtDesc(since);
    }

    public Optional<Investigation> getById(UUID id) {
        return repository.findById(id);
    }

    // -------------------------------------------------------------------------
    // SSE subscription
    // -------------------------------------------------------------------------

    /**
     * Creates a long-lived SSE emitter for a new dashboard subscriber.
     * Sends an immediate heartbeat so the browser connection is confirmed open.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));

        emitters.add(emitter);

        // Send an initial heartbeat to confirm the connection is live.
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * Sends a keepalive heartbeat to all connected subscribers every 30 seconds.
     * Prevents proxies and browsers from closing idle SSE connections.
     */
    @Scheduled(fixedDelay = 30_000)
    public void sendHeartbeats() {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    // -------------------------------------------------------------------------
    // Scheduled cleanup
    // -------------------------------------------------------------------------

    /**
     * Deletes investigations older than 30 days. Runs daily at 03:00.
     */
    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldInvestigations() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        repository.deleteByStartedAtBefore(cutoff);
        System.out.println("[Sentinel] Cleaned up investigations older than 30 days.");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Broadcasts a named SSE event carrying the investigation as a simple
     * JSON-like string. A proper JSON serialiser is not needed here because
     * Spring's default Jackson converter handles the REST endpoints; SSE only
     * needs the id for the dashboard to trigger a follow-up REST fetch.
     */
    private void broadcast(String eventName, Investigation inv) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "id", inv.getId().toString(),
                    "status", inv.getStatus(),
                    "alertName", inv.getAlertName() != null ? inv.getAlertName() : "",
                    "severity", inv.getSeverity() != null ? inv.getSeverity() : ""
            ));
        } catch (Exception e) {
            payload = "{\"id\":\"" + inv.getId() + "\"}";
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException ex) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    /**
     * Splits the raw LLM report on section headers and returns a map keyed by
     * normalised section name (lowercase, no spaces).
     *
     * <p>Example input:
     * <pre>
     * **Symptoms**: High CPU usage observed...
     * **Root Cause**: Memory leak in...
     * </pre>
     *
     * <p>If the report contains none of the known headers the entire text is
     * stored under {@code symptoms} so nothing is silently dropped.
     */
    private Map<String, String> parseReport(String rawReport) {
        if (rawReport == null || rawReport.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = SECTION.matcher(rawReport);

        List<String> keys = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();

        while (matcher.find()) {
            // Normalise: lowercase, strip spaces — e.g. "Root Cause" -> "rootcause"
            String key = matcher.group(1).toLowerCase().replaceAll("\\s+", "");
            keys.add(key);
            starts.add(matcher.end());
        }

        if (keys.isEmpty()) {
            // No recognised headers — store everything in symptoms.
            result.put("symptoms", rawReport.trim());
            return result;
        }

        // Extract text between consecutive section headers (and from last header to end of report).
        for (int i = 0; i < keys.size(); i++) {
            int from = starts.get(i);
            int end = (i + 1 < keys.size())
                    ? findHeaderStart(rawReport, starts.get(i + 1))
                    : rawReport.length();

            String section = rawReport.substring(from, end).trim();
            if (!section.isBlank()) {
                result.put(keys.get(i), section);
            }
        }

        return result;
    }

    /**
     * Walks backwards from the end-of-match position to find the start of the
     * header token in the original string (accounts for leading **, ## etc.).
     */
    private int findHeaderStart(String text, int matcherEnd) {
        // Re-run the pattern to find the start of the header group before matcherEnd.
        Matcher m = SECTION.matcher(text);
        int start = matcherEnd;
        while (m.find()) {
            if (m.end() == matcherEnd) {
                start = m.start();
                break;
            }
        }
        return start;
    }

}
