package com.sentinel.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorating {@link ChatModel} that transparently falls back to a secondary
 * model when the primary fails with a retriable, capacity-related error —
 * rate-limits (HTTP 429), service overload (HTTP 503 / {@code RESOURCE_EXHAUSTED}
 * / {@code UNAVAILABLE}), or quota exhaustion.
 *
 * <p>The intended pairing is {@code gemini-2.5-flash} (primary) with
 * {@code gemini-2.0-flash-lite} (secondary): same vendor, lighter quota, so
 * an investigation still completes during a Gemini demand spike instead of
 * surfacing a 429 to the operator.
 *
 * <p>Non-retriable errors (auth, malformed requests, safety filtering) are
 * rethrown unchanged so genuine bugs aren't masked.
 *
 * <p>Designed to layer <i>inside</i> {@link BudgetedChatModel}, so the
 * per-investigation token budget is enforced once per logical call regardless
 * of which model ends up answering.
 */
public class FallbackChatModel implements ChatModel {

    private final ChatModel primary;
    private final ChatModel secondary;
    private final AtomicLong fallbackCount = new AtomicLong();
    /** Optional — null in unit tests, wired by AgentConfiguration in production. */
    private final SentinelMetrics metrics;

    public FallbackChatModel(ChatModel primary, ChatModel secondary) {
        this(primary, secondary, null);
    }

    public FallbackChatModel(ChatModel primary, ChatModel secondary, SentinelMetrics metrics) {
        this.primary = primary;
        this.secondary = secondary;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            ChatResponse response = primary.chat(chatRequest);
            if (metrics != null) metrics.recordPrimaryModelCall();
            return response;
        } catch (RuntimeException e) {
            if (!isRetriable(e)) {
                throw e;
            }
            fallbackCount.incrementAndGet();
            String reason = classifyRetriableReason(e);
            System.err.println("[Sentinel] Primary model failed with retriable error ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") — falling back to secondary model.");
            if (metrics != null) metrics.recordFallbackModelCall(reason);
            return secondary.chat(chatRequest);
        }
    }

    /** Total number of times the secondary model has been invoked due to primary failure. */
    public long getFallbackCount() {
        return fallbackCount.get();
    }

    /**
     * Buckets a retriable error into one of a small set of reasons so the
     * fallback counter's "reason" tag stays low cardinality.
     */
    private static String classifyRetriableReason(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String typeName = cur.getClass().getSimpleName().toLowerCase();
            if (typeName.contains("timeout")) return "timeout";
            String msg = cur.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out")) return "timeout";
            if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")) return "rate_limited";
            if (lower.contains("resource_exhausted") || lower.contains("quota")) return "quota";
            if (lower.contains("503") || lower.contains("unavailable") || lower.contains("overloaded")) return "unavailable";
        }
        return "other";
    }

    /**
     * Walks the cause chain looking for capacity / rate-limit / availability
     * signals — anything where retrying on the secondary model has a good
     * chance of succeeding. Matches on message substrings (and also on the
     * exception class name, for {@code HttpTimeoutException} where the
     * message is sometimes just "request timed out") because LangChain4j
     * wraps SDK errors and the exact wrapper class varies between versions.
     */
    private static boolean isRetriable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            // HttpTimeoutException sometimes carries only "request timed out"
            // as its message — also check the exception type name so a slow
            // preview model (the original motivation for fallback) reliably
            // falls back to the GA model instead of failing the investigation.
            String typeName = cur.getClass().getSimpleName().toLowerCase();
            if (typeName.contains("timeout")) return true;

            String msg = cur.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase();
            if (lower.contains("429")
                    || lower.contains("rate limit")
                    || lower.contains("rate_limit")
                    || lower.contains("resource_exhausted")
                    || lower.contains("resource exhausted")
                    || lower.contains("quota")
                    || lower.contains("overloaded")
                    || lower.contains("unavailable")
                    || lower.contains("503")
                    || lower.contains("timed out")
                    || lower.contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}
