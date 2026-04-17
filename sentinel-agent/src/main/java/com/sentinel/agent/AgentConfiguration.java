package com.sentinel.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring configurations for the LangChain4j Gemini Agent.
 */
@Configuration
public class AgentConfiguration {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Bean
    ChatLanguageModel geminiChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName("gemini-2.5-flash") // Fast + capable, good balance for SRE investigation chains
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
    SreAgent sreAgent(ChatLanguageModel model, SreTools sreTools) {
        return AiServices.builder(SreAgent.class)
                .chatLanguageModel(model)
                .tools(sreTools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(30))
                .build();
    }
}
