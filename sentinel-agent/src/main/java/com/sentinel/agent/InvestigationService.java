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
    // Handles all observed shapes:
    //   **Symptoms**:       — bold close, then colon
    //   **Symptoms:**       — colon inside bold (Gemini frequently does this)
    //   ## Symptoms         — markdown heading (any level h1-h6)
    //   #### Symptoms       — Gemini sometimes nests under a top-level title
    //   Symptoms:           — plain
    // The hash range is widened to 1-6 (full markdown heading range) — Gemini
    // emits #### subsection headers when the overall document already uses
    // ### for its title. Trailing `(?:\\*{1,2})?` after the optional colon
    // handles the **Section:** form so the bold close isn't leaked into body.
    private static final Pattern SECTION = Pattern.compile(
            "^[ \t]*(?:\\*{1,2}|#{1,6}\\s*)?(Symptoms|Evidence|Root\\s*Cause|Proposed\\s*Fix)(?:\\*{1,2})?\\s*:?\\s*(?:\\*{1,2})?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    /**
     * Backstop for episode-based dedupe: if an "active" investigation (one with
     * {@code alertResolvedAt} still null) is older than this, treat it as
     * orphaned and create a fresh investigation anyway. Self-heals the edge
     * case where AlertManager's {@code resolved} webhook is lost — without a
     * backstop, an active episode would dedupe forever.
     *
     * <p>The primary dedupe mechanism is now the explicit {@code resolveEpisode}
     * call wired to {@code status=resolved} webhooks, so this only matters in
     * the rare case that webhook never arrives. 3 hours is generous for any
     * realistic demo/chaos session (one investigation per session) without
     * being so long that a truly missed-resolve scenario locks dedupe forever.
     */
    private static final java.time.Duration EPISODE_BACKSTOP = java.time.Duration.ofHours(3);

    private final InvestigationRepository repository;
    private final BudgetedChatModel budgetedChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Thread-safe list of active SSE connections.
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Outcome of {@link #start(String, String)} — either a brand-new investigation
     * was created, or an existing recent one was reused because the webhook is a
     * continuation of an already-active alert.
     */
    public record StartResult(Investigation investigation, boolean wasReused) {}

    public InvestigationService(InvestigationRepository repository,
                                dev.langchain4j.model.chat.ChatModel chatModel) {
        this.repository = repository;
        // The Spring ChatModel bean is wrapped by BudgetedChatModel — downcast so we
        // can release per-memory-id token counters when an investigation terminates.
        this.budgetedChatModel = (chatModel instanceof BudgetedChatModel b) ? b : null;
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
     * Resolves the appropriate investigation for an incoming firing-alert webhook:
     * either creates a new PENDING investigation (notifying subscribers), or
     * returns the existing investigation for an active episode of the same
     * alertName.
     *
     * <p>An "active episode" = an investigation whose {@code alertResolvedAt}
     * is still null AND that started within {@link #EPISODE_BACKSTOP}. As long
     * as those conditions hold, every subsequent firing webhook for the same
     * alertName is collapsed onto that one row — no new investigation, no new
     * Gemini invocation, no token spend.
     *
     * <p>The episode ends when {@link #resolveEpisode(String)} fires (driven by
     * an AlertManager {@code resolved} webhook), at which point the next firing
     * webhook will create a fresh investigation.
     *
     * @return a {@link StartResult} where {@code wasReused == true} signals to
     *         the caller (typically {@code AlertController}) that no AI dispatch
     *         should be triggered — the existing investigation is already
     *         handling this alert episode.
     */
    public StartResult start(String alertName, String severity) {
        Instant cutoff = Instant.now().minus(EPISODE_BACKSTOP);
        Optional<Investigation> recent =
                repository.findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
                        alertName, "FAILED", cutoff);
        if (recent.isPresent()) {
            Investigation existing = recent.get();
            System.out.println("[Sentinel] Deduped webhook for alert='" + alertName
                    + "' — reusing investigation " + existing.getId()
                    + " (status=" + existing.getStatus() + ", episode still active)");
            return new StartResult(existing, true);
        }
        Investigation inv = Investigation.create(alertName, severity);
        inv = repository.save(inv);
        broadcast("investigation_started", inv);
        return new StartResult(inv, false);
    }

    /**
     * Closes the currently-active episode(s) for an alertName by stamping
     * {@code alertResolvedAt = now} on every matching investigation. Driven by
     * the {@code status=resolved} AlertManager webhook in
     * {@code AlertController.receiveAlert}.
     *
     * <p>After this returns, the next firing webhook for the same alertName
     * will create a brand-new investigation — i.e. a genuinely separate
     * incident, not a continuation of the resolved one.
     *
     * <p>Idempotent: running it twice (or against an alertName with no active
     * episode) is a no-op.
     */
    @Transactional
    public void resolveEpisode(String alertName) {
        Instant now = Instant.now();
        List<Investigation> active = repository.findByAlertNameAndAlertResolvedAtIsNull(alertName);
        if (active.isEmpty()) return;
        for (Investigation inv : active) {
            inv.setAlertResolvedAt(now);
            Investigation saved = repository.save(inv);
            broadcast("episode_resolved", saved);
        }
        System.out.println("[Sentinel] Resolved " + active.size()
                + " active episode(s) for alert='" + alertName + "'");
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
            releaseBudget(id.toString());
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
            releaseBudget(id.toString());
            broadcast("investigation_failed", saved);
        });
    }

    /**
     * Releases the per-investigation token-budget counter held by
     * {@link BudgetedChatModel}. No-op if the wrapper isn't installed
     * (e.g. tests that bypass it).
     */
    private void releaseBudget(String memoryId) {
        if (budgetedChatModel != null) {
            budgetedChatModel.removeBudget(memoryId);
        }
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

            String section = stripStrayHashLines(rawReport.substring(from, end)).trim();
            if (!section.isBlank()) {
                result.put(keys.get(i), section);
            }
        }

        return result;
    }

    /**
     * Removes standalone heading-marker lines (e.g. lines containing only "#" /
     * "##" / "###" with optional whitespace) that Gemini occasionally emits
     * between section bodies. These would otherwise render as empty headings or
     * literal "#" characters depending on the consumer's rendering layer.
     */
    private String stripStrayHashLines(String section) {
        return section.replaceAll("(?m)^\\s*#{1,6}\\s*$", "");
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
