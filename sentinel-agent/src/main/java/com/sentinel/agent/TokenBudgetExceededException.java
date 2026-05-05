package com.sentinel.agent;

/**
 * Thrown by {@link BudgetedChatModel} when an investigation's cumulative input
 * tokens exceed the configured per-memory cap. Bubbles up out of the LangChain4j
 * AI service proxy so callers (AlertController / AgentController) can mark the
 * investigation as FAILED and surface a clear reason to the dashboard.
 */
public class TokenBudgetExceededException extends RuntimeException {

    private final String memoryId;
    private final int used;
    private final int cap;

    public TokenBudgetExceededException(String memoryId, int used, int cap) {
        super("Token budget exceeded for memory_id=" + memoryId
                + ": used " + used + " of " + cap + " input tokens");
        this.memoryId = memoryId;
        this.used = used;
        this.cap = cap;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public int getUsed() {
        return used;
    }

    public int getCap() {
        return cap;
    }
}
