package com.sentinel.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring configurations for the LangChain4j Gemini Agent.
 */
@Configuration
@EnableScheduling
public class AgentConfiguration {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${agent.gemini.token-budget:100000}")
    private int tokenBudget;

    @Value("${gemini.model.primary:gemini-2.5-flash}")
    private String primaryModelName;

    @Value("${gemini.model.fallback:gemini-2.0-flash-lite}")
    private String fallbackModelName;

    /**
     * Builds a Gemini chat model for the given model name. Kept private so the
     * publicly exposed {@code ChatModel} bean is always the fully decorated
     * variant — {@code BudgetedChatModel(FallbackChatModel(primary, secondary))} —
     * and every consumer transparently inherits both the token cap and the
     * automatic fallback on rate-limit / overload errors.
     */
    private ChatModel buildGemini(String modelName) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName(modelName)
                .temperature(0.2)
                .maxRetries(3) // Built-in exponential backoff for transient 5xx / network failures
                // Required for Gemini 3 multi-turn tool calling — captures the
                // thought_signature emitted on each function call and echoes it
                // back on the next request. Without this Gemini 3 returns
                // 400 INVALID_ARGUMENT after the first tool invocation.
                // Harmless for Gemini 2.5 (older models simply ignore the field).
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }

    @Bean
    ChatModel geminiChatModel(SentinelMetrics metrics) {
        ChatModel primary = buildGemini(primaryModelName);
        ChatModel secondary = buildGemini(fallbackModelName);
        ChatModel withFallback = new FallbackChatModel(primary, secondary, metrics);
        return new BudgetedChatModel(withFallback, tokenBudget);
    }

    @Bean
    StreamingChatModel geminiStreamingChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName(primaryModelName)
                .temperature(0.2)
                .build();
    }

    // Bounded executor for background webhook investigations.
    // Max 5 concurrent LLM investigations; excess alerts are queued up to 20 deep.
    // Spring manages the lifecycle — threads are cleanly shut down on context close.
    @Bean
    TaskExecutor investigationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("sentinel-investigation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    // Explicitly wires the SreAgent with its tools and per-request memory.
    // Using chatMemoryProvider (not chatMemory) ensures each investigation gets
    // its own isolated memory — no context leakage between concurrent requests.
    @Bean
    SreAgent sreAgent(ChatModel model, SreTools sreTools) {
        return AiServices.builder(SreAgent.class)
                .chatModel(model)
                .tools(sreTools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(30))
                .build();
    }

    /**
     * Streaming variant of the SRE agent — same tools and per-request memory,
     * but driven by Gemini's streaming endpoint so the dashboard can render
     * tokens as they arrive. Token-budget enforcement is intentionally not
     * applied here (it lives on the synchronous ChatModel path).
     */
    @Bean
    StreamingSreAgent streamingSreAgent(StreamingChatModel streamingModel, SreTools sreTools) {
        return AiServices.builder(StreamingSreAgent.class)
                .streamingChatModel(streamingModel)
                .tools(sreTools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(30))
                .build();
    }
}
