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

    public FallbackChatModel(ChatModel primary, ChatModel secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        try {
            return primary.chat(chatRequest);
        } catch (RuntimeException e) {
            if (!isRetriable(e)) {
                throw e;
            }
            fallbackCount.incrementAndGet();
            System.err.println("[Sentinel] Primary model failed with retriable error ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") — falling back to secondary model.");
            return secondary.chat(chatRequest);
        }
    }

    /** Total number of times the secondary model has been invoked due to primary failure. */
    public long getFallbackCount() {
        return fallbackCount.get();
    }

    /**
     * Walks the cause chain looking for capacity / rate-limit signals. We match
     * on message substrings rather than exception types because LangChain4j
     * wraps SDK errors and the exact wrapper class varies between versions.
     */
    private static boolean isRetriable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
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
                    || lower.contains("503")) {
                return true;
            }
        }
        return false;
    }
}
