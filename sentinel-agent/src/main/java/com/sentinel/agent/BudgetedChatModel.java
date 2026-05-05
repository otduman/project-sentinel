package com.sentinel.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decorating {@link ChatModel} that enforces a per-memory-id input-token cap
 * across an investigation's full ReAct loop. The goal is to bound the cost of
 * any single runaway investigation by capping cumulative input tokens.
 *
 * <h3>Memory-id correlation</h3>
 * The active investigation's memory id is read from {@link MemoryIdContext}
 * (a {@link ThreadLocal}). When unset (e.g. webhook flows that don't populate
 * the context), we fall back to the thread name — which is still a stable key
 * for a single in-flight synchronous investigation since each worker thread
 * services one investigation at a time.
 *
 * <h3>Token estimation</h3>
 * To stay decoupled from any specific tokenizer SPI version, we use a
 * conservative 4-characters-per-token heuristic over the serialised
 * {@link ChatRequest} content. This typically over-estimates slightly for
 * English prompts, which is the right side to err on for a budget enforcer.
 *
 * <h3>Cleanup</h3>
 * Callers must invoke {@link #removeBudget(String)} when an investigation
 * terminates so per-memory-id counters don't accumulate. {@code InvestigationService}
 * does this from {@code complete()} and {@code fail()}.
 */
public class BudgetedChatModel implements ChatModel {

    private static final int CHARS_PER_TOKEN = 4;

    private final ChatModel delegate;
    private final int tokenBudget;
    private final ConcurrentHashMap<String, AtomicInteger> usageByMemoryId = new ConcurrentHashMap<>();

    public BudgetedChatModel(ChatModel delegate, int tokenBudget) {
        this.delegate = delegate;
        this.tokenBudget = tokenBudget;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        String memoryId = resolveMemoryId();
        int estimated = estimateInputTokens(chatRequest);

        AtomicInteger counter = usageByMemoryId.computeIfAbsent(memoryId, k -> new AtomicInteger(0));
        int newTotal = counter.addAndGet(estimated);

        if (newTotal > tokenBudget) {
            System.err.println("[Sentinel] Token budget exceeded: "
                    + newTotal + "/" + tokenBudget + " for memory_id=" + memoryId);
            throw new TokenBudgetExceededException(memoryId, newTotal, tokenBudget);
        }

        return delegate.chat(chatRequest);
    }

    /**
     * Removes the per-memory-id usage counter. Safe to call multiple times.
     */
    public void removeBudget(String memoryId) {
        if (memoryId != null) {
            usageByMemoryId.remove(memoryId);
        }
    }

    /**
     * Visible for diagnostics — returns the cumulative input-token estimate
     * for the given memory id, or 0 if untracked.
     */
    public int getUsage(String memoryId) {
        AtomicInteger counter = usageByMemoryId.get(memoryId);
        return counter == null ? 0 : counter.get();
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private String resolveMemoryId() {
        String fromContext = MemoryIdContext.get();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        // Fallback: each pooled investigation thread runs one synchronous
        // investigation at a time, so thread name is a safe per-investigation key.
        return "thread:" + Thread.currentThread().getName();
    }

    /**
     * 4-chars-per-token heuristic on the serialised user/system/tool content
     * of the {@link ChatRequest}. Conservative by design — slight over-estimation
     * is acceptable for a budget enforcer.
     */
    private int estimateInputTokens(ChatRequest chatRequest) {
        if (chatRequest == null || chatRequest.messages() == null) {
            return 0;
        }
        int charCount = 0;
        for (Object msg : chatRequest.messages()) {
            if (msg == null) continue;
            // toString() on LangChain4j ChatMessage variants includes the message
            // content plus role; good enough for a heuristic input estimate.
            String s = msg.toString();
            charCount += s == null ? 0 : s.length();
        }
        // Round up so a 1-char message still costs at least 1 token.
        return Math.max(1, (charCount + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }
}
