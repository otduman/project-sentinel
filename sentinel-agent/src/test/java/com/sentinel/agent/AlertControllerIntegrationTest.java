package com.sentinel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link AlertController}. The full Spring context is NOT loaded —
 * only the MVC layer plus the explicitly mocked collaborators. The real
 * {@link SreAgent} (LangChain4j + Gemini) is mocked, so no LLM calls are made.
 */
@WebMvcTest(AlertController.class)
@TestPropertySource(properties = "agent.webhook.secret=test-secret")
class AlertControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private SreAgent sreAgent;

    @MockitoBean
    private TaskExecutor investigationExecutor;

    @MockitoBean
    private InvestigationService investigationService;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> firingPayload() {
        return Map.of(
                "status", "firing",
                "commonLabels", Map.of(
                        "alertname", "HighCpuAlert",
                        "severity", "critical"
                ),
                "commonAnnotations", Map.of(
                        "summary", "CPU at 100% for 5 minutes"
                ),
                "alerts", List.of()
        );
    }

    private static Map<String, Object> resolvedPayload() {
        return Map.of(
                "status", "resolved",
                "commonLabels", Map.of(
                        "alertname", "HighCpuAlert",
                        "severity", "critical"
                ),
                "commonAnnotations", Map.of("summary", "Recovered"),
                "alerts", List.of()
        );
    }

    private static Investigation persisted(String alertName, String severity) {
        Investigation inv = Investigation.create(alertName, severity);
        inv.setId(UUID.randomUUID());
        inv.setStartedAt(Instant.now());
        return inv;
    }

    // -------------------------------------------------------------------------
    // status=firing → start() invoked
    // -------------------------------------------------------------------------

    @Test
    void firingAlert_triggersInvestigationStart() throws Exception {
        when(investigationService.start(eq("HighCpuAlert"), eq("critical")))
                .thenReturn(new InvestigationService.StartResult(persisted("HighCpuAlert", "critical"), false));
        // Run the executor task synchronously so we can verify downstream calls deterministically.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(investigationExecutor).execute(any(Runnable.class));
        when(sreAgent.investigate(anyString(), anyString())).thenReturn("**Symptoms**: ok");

        mockMvc.perform(post("/webhook/prometheus")
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(firingPayload())))
                .andExpect(status().isOk());

        verify(investigationService).start("HighCpuAlert", "critical");
    }

    // -------------------------------------------------------------------------
    // status=resolved → start() NOT invoked, still 200
    // -------------------------------------------------------------------------

    @Test
    void resolvedAlert_doesNotStartInvestigation() throws Exception {
        mockMvc.perform(post("/webhook/prometheus")
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(resolvedPayload())))
                .andExpect(status().isOk());

        verify(investigationService, never()).start(anyString(), anyString());
        verify(investigationExecutor, never()).execute(any(Runnable.class));
    }

    // -------------------------------------------------------------------------
    // Webhook auth: secret is set, so missing/wrong Authorization → 401
    // -------------------------------------------------------------------------

    @Test
    void missingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(post("/webhook/prometheus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(firingPayload())))
                .andExpect(status().isUnauthorized());

        verify(investigationService, never()).start(anyString(), anyString());
    }

    @Test
    void wrongBearerToken_returns401() throws Exception {
        mockMvc.perform(post("/webhook/prometheus")
                        .header("Authorization", "Bearer wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(firingPayload())))
                .andExpect(status().isUnauthorized());

        verify(investigationService, never()).start(anyString(), anyString());
    }

    @Test
    void correctBearerToken_returns200() throws Exception {
        when(investigationService.start(anyString(), anyString()))
                .thenReturn(new InvestigationService.StartResult(persisted("HighCpuAlert", "critical"), false));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(investigationExecutor).execute(any(Runnable.class));
        when(sreAgent.investigate(anyString(), anyString())).thenReturn("**Symptoms**: ok");

        mockMvc.perform(post("/webhook/prometheus")
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(firingPayload())))
                .andExpect(status().isOk());
    }
}
