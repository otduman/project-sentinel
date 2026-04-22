package com.sentinel.agent;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.PrintWriter;
import java.util.UUID;

/**
 * Controller mapping for our AI Agent.
 */
@RestController
@RequestMapping("/api/sentinel")
public class AgentController {

    private final SreAgent sreAgent;

    public AgentController(SreAgent sreAgent) {
        this.sreAgent = sreAgent;
    }

    // Blocking endpoint — kept for programmatic / webhook use.
    @PostMapping("/investigate")
    public String investigate(@RequestBody String incidentDescription) {
        String memoryId = UUID.randomUUID().toString();
        System.out.println("Starting investigation [" + memoryId + "] for: " + incidentDescription);
        return sreAgent.investigate(memoryId, incidentDescription);
    }

    // Streaming endpoint for the React dashboard — writes the full result as plain-text chunks.
    // The LLM call is synchronous, so we stream a status line first, then the final report.
    // For true token-level streaming, LangChain4j's StreamingChatLanguageModel would be needed.
    @PostMapping(value = "/investigate/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public StreamingResponseBody investigateStream(@RequestBody String incidentDescription) {
        String memoryId = UUID.randomUUID().toString();
        return outputStream -> {
            try (PrintWriter writer = new PrintWriter(outputStream, true)) {
                writer.println("Investigation started [" + memoryId + "]...");
                writer.println("Dispatching AI SRE Agent. Fetching logs and profiling...");
                writer.flush();

                String result = sreAgent.investigate(memoryId, incidentDescription);

                writer.println("\n--- DIAGNOSTIC REPORT ---\n");
                writer.println(result);
                writer.flush();
            } catch (Exception e) {
                outputStream.write(("Error during investigation: " + e.getMessage()).getBytes());
            }
        };
    }
}
