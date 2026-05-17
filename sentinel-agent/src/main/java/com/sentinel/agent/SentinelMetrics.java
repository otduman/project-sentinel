package com.sentinel.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sentinel's self-observability surface. Every metric emitted here is scraped
 * by the same Prometheus that watches lab-rat, so the agent shows up alongside
 * the things it monitors — "the SRE watches itself."
 *
 * <p>Naming follows the Prometheus convention: {@code <namespace>_<subsystem>_<name>_<unit>}.
 * Namespace is {@code sentinel}, subsystem is whichever component owns the
 * metric, and tags carry dimensions that you'd actually want to filter on
 * in a Grafana panel (alert name, primary/fallback model, status).
 *
 * <h3>Why a dedicated component</h3>
 * Spring Boot Actuator + Micrometer would let any service inject the
 * {@link MeterRegistry} directly, but centralising metric definitions here
 * means: (a) one place to read the full list, (b) no accidental
 * duplicate-counter-with-different-tags bugs, and (c) consumers (services,
 * decorators) don't need to know about Micrometer at all — they call typed
 * methods like {@code recordInvestigationCompleted}.
 */
@Component
public class SentinelMetrics {

    private final MeterRegistry registry;

    // Investigation lifecycle counters. Tagged by status so a single counter
    // family covers started/completed/failed without three separate fields.
    private final Counter investigationsStarted;
    private final Counter investigationsCompleted;
    private final Counter investigationsFailed;
    private final Counter investigationsDeduped;

    // Investigation duration histogram. Buckets cover the realistic range —
    // a ReAct loop is fast in the empty case (a few seconds), typical for
    // chaos demos is 15-45s, and the long tail is 60-120s on slow Gemini
    // responses. >120s likely means a stuck call.
    private final Timer investigationDuration;

    // Active investigations gauge. Backed by an AtomicInteger so producers
    // can increment/decrement from any thread without registry coordination.
    private final AtomicInteger activeInvestigations = new AtomicInteger();

    // Model-selection counters — how often each model actually answered.
    // The fallback counter ticks any time FallbackChatModel routed around
    // the primary; primary tick counts successful primary calls.
    private final Counter modelPrimaryUsed;
    private final Counter modelFallbackUsed;

    // Token consumption per investigation. Recorded as a counter so we can
    // graph cumulative spend over a time range AND derive per-investigation
    // averages via rate(). Tagged by model so we can compare primary vs
    // fallback token cost.
    private final Counter tokensInputConsumed;

    public SentinelMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.investigationsStarted = Counter.builder("sentinel.investigations.started.total")
                .description("Investigations that began (a webhook arrived and was not deduped)")
                .register(registry);
        this.investigationsCompleted = Counter.builder("sentinel.investigations.completed.total")
                .description("Investigations that returned a successful diagnosis from the LLM")
                .register(registry);
        this.investigationsFailed = Counter.builder("sentinel.investigations.failed.total")
                .description("Investigations that errored out (Gemini error, timeout, token-budget exceeded)")
                .register(registry);
        this.investigationsDeduped = Counter.builder("sentinel.investigations.deduped.total")
                .description("Webhooks that matched an active episode and were collapsed onto an existing investigation")
                .register(registry);

        this.investigationDuration = Timer.builder("sentinel.investigation.duration")
                .description("End-to-end wall-clock time per investigation, from start() to complete()/fail()")
                .publishPercentileHistogram()
                .register(registry);

        registry.gauge("sentinel.investigations.active", Tags.empty(), activeInvestigations, AtomicInteger::get);

        this.modelPrimaryUsed = Counter.builder("sentinel.model.calls.total")
                .tag("tier", "primary")
                .description("Chat calls completed by the primary Gemini model")
                .register(registry);
        this.modelFallbackUsed = Counter.builder("sentinel.model.calls.total")
                .tag("tier", "fallback")
                .description("Chat calls completed by the fallback Gemini model")
                .register(registry);

        this.tokensInputConsumed = Counter.builder("sentinel.tokens.input.total")
                .description("Cumulative input tokens spent across all investigations (Gemini billing units)")
                .register(registry);
    }

    // -----------------------------------------------------------------------
    // Investigation lifecycle
    // -----------------------------------------------------------------------

    public void recordInvestigationStarted(String alertName) {
        Counter.builder("sentinel.investigations.started.total")
                .tag("alert", alertName == null ? "unknown" : alertName)
                .register(registry)
                .increment();
        investigationsStarted.increment();
        activeInvestigations.incrementAndGet();
    }

    public void recordInvestigationCompleted(String alertName, long durationMillis) {
        investigationsCompleted.increment();
        investigationDuration.record(java.time.Duration.ofMillis(durationMillis));
        activeInvestigations.decrementAndGet();
        registry.counter("sentinel.investigations.terminal.total",
                Tags.of(Tag.of("alert", alertName == null ? "unknown" : alertName), Tag.of("outcome", "completed")))
                .increment();
    }

    public void recordInvestigationFailed(String alertName, long durationMillis, String reason) {
        investigationsFailed.increment();
        investigationDuration.record(java.time.Duration.ofMillis(durationMillis));
        activeInvestigations.decrementAndGet();
        registry.counter("sentinel.investigations.terminal.total",
                Tags.of(
                        Tag.of("alert", alertName == null ? "unknown" : alertName),
                        Tag.of("outcome", "failed"),
                        Tag.of("reason", reason == null ? "unknown" : reason)))
                .increment();
    }

    public void recordInvestigationDeduped(String alertName) {
        investigationsDeduped.increment();
        registry.counter("sentinel.investigations.deduped.total",
                Tags.of(Tag.of("alert", alertName == null ? "unknown" : alertName)))
                .increment();
    }

    // -----------------------------------------------------------------------
    // Model tier accounting
    // -----------------------------------------------------------------------

    public void recordPrimaryModelCall() {
        modelPrimaryUsed.increment();
    }

    public void recordFallbackModelCall(String reason) {
        modelFallbackUsed.increment();
        registry.counter("sentinel.model.fallback.reasons.total",
                Tags.of(Tag.of("reason", reason == null ? "unknown" : reason)))
                .increment();
    }

    public void recordInputTokens(int tokens, String tier) {
        registry.counter("sentinel.tokens.input.total",
                Tags.of(Tag.of("tier", tier == null ? "primary" : tier)))
                .increment(tokens);
        tokensInputConsumed.increment(tokens);
    }
}
