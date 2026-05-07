package com.sentinel.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link FallbackChatModel}. Verifies that retriable errors
 * trigger the secondary model while non-retriable errors propagate unchanged.
 */
class FallbackChatModelTest {

    private final ChatRequest request = ChatRequest.builder()
            .messages(dev.langchain4j.data.message.UserMessage.from("hi"))
            .build();

    @Test
    void primarySuccess_secondaryNeverCalled() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(primary.chat(any(ChatRequest.class))).thenReturn(response);

        FallbackChatModel model = new FallbackChatModel(primary, secondary);
        ChatResponse result = model.chat(request);

        assertThat(result).isSameAs(response);
        verify(secondary, never()).chat(any(ChatRequest.class));
        assertThat(model.getFallbackCount()).isZero();
    }

    @Test
    void rateLimitError_fallsBackToSecondary() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("HTTP 429 Too Many Requests"));
        when(secondary.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, secondary);
        ChatResponse result = model.chat(request);

        assertThat(result).isSameAs(fallbackResponse);
        verify(primary, times(1)).chat(any(ChatRequest.class));
        verify(secondary, times(1)).chat(any(ChatRequest.class));
        assertThat(model.getFallbackCount()).isEqualTo(1);
    }

    @Test
    void resourceExhaustedError_fallsBackToSecondary() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("RESOURCE_EXHAUSTED: quota exceeded for project"));
        when(secondary.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, secondary);
        ChatResponse result = model.chat(request);

        assertThat(result).isSameAs(fallbackResponse);
        assertThat(model.getFallbackCount()).isEqualTo(1);
    }

    @Test
    void httpTimeoutException_fallsBackToSecondary() {
        // Real motivating case: preview Gemini models occasionally take longer
        // than the JDK HttpClient timeout, surfacing as HttpTimeoutException with
        // a "request timed out" message. We must fall back so a slow primary
        // doesn't fail the whole investigation.
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("call failed",
                        new java.net.http.HttpTimeoutException("request timed out")));
        when(secondary.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, secondary);
        model.chat(request);

        assertThat(model.getFallbackCount()).isEqualTo(1);
    }

    @Test
    void serviceUnavailable_fallsBackToSecondary() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("503 Service Unavailable: model overloaded"));
        when(secondary.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, secondary);
        model.chat(request);

        assertThat(model.getFallbackCount()).isEqualTo(1);
    }

    @Test
    void retriableSignalInCauseChain_isDetected() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        ChatResponse fallbackResponse = mock(ChatResponse.class);
        // Simulate LangChain4j wrapping the underlying SDK exception.
        Throwable inner = new RuntimeException("429 too many requests");
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("Gemini SDK error", inner));
        when(secondary.chat(any(ChatRequest.class))).thenReturn(fallbackResponse);

        FallbackChatModel model = new FallbackChatModel(primary, secondary);
        model.chat(request);

        assertThat(model.getFallbackCount()).isEqualTo(1);
    }

    @Test
    void nonRetriableError_propagatesWithoutFallback() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("400 Bad Request: malformed prompt"));

        FallbackChatModel model = new FallbackChatModel(primary, secondary);

        assertThatThrownBy(() -> model.chat(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("400");
        verify(secondary, never()).chat(any(ChatRequest.class));
        assertThat(model.getFallbackCount()).isZero();
    }

    @Test
    void tokenBudgetExceeded_isNotRetriable() {
        // Budget breaches must not trigger fallback — that would defeat the cost cap.
        ChatModel primary = mock(ChatModel.class);
        ChatModel secondary = mock(ChatModel.class);
        when(primary.chat(any(ChatRequest.class)))
                .thenThrow(new TokenBudgetExceededException("inv-1", 5000, 4000));

        FallbackChatModel model = new FallbackChatModel(primary, secondary);

        assertThatThrownBy(() -> model.chat(request))
                .isInstanceOf(TokenBudgetExceededException.class);
        verify(secondary, never()).chat(any(ChatRequest.class));
    }
}
