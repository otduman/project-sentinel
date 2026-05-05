package com.sentinel.agent;

/**
 * Thread-local carrier for the current investigation's memory id, used by
 * {@link BudgetedChatModel} to attribute input-token usage to the correct
 * investigation.
 *
 * <p>Set this at the start of an investigation (on the thread that will invoke
 * the LangChain4j {@code SreAgent} proxy) and clear it in a {@code finally}
 * block to avoid leaking a memory id onto pooled threads.
 */
public final class MemoryIdContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private MemoryIdContext() {}

    public static void set(String memoryId) {
        CURRENT.set(memoryId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
