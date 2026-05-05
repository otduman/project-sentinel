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
    private final StreamingSreAgent streamingSreAgent;

    public AgentController(SreAgent sreAgent, StreamingSreAgent streamingSreAgent) {
        this.sreAgent = sreAgent;
        this.streamingSreAgent = streamingSreAgent;
    }

    // Blocking endpoint — kept for programmatic / webhook use.
    @PostMapping("/investigate")
    public String investigate(@RequestBody String incidentDescription) {
        String memoryId = UUID.randomUUID().toString();
        System.out.println("Starting investigation [" + memoryId + "] for: " + incidentDescription);
        return sreAgent.investigate(memoryId, incidentDescription);
    }

    // Real token-by-token streaming via Gemini's streaming API. Each partial
    // response is flushed immediately so the browser renders tokens as they arrive.
    // The writer is kept open until onCompleteResponse / onError fires; we cannot
    // use try-with-resources here because TokenStream callbacks run on a Gemini
    // SDK thread asynchronously after this lambda returns.
    @PostMapping(value = "/investigate/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public StreamingResponseBody investigateStream(@RequestBody String incidentDescription) {
        final String memoryId = UUID.randomUUID().toString();
        return outputStream -> {
            final PrintWriter writer = new PrintWriter(outputStream, true);
            // Make the memory id visible to BudgetedChatModel for accurate per-investigation
            // token accounting. (Streaming itself uses the non-budgeted StreamingChatModel,
            // but tool calls inside the stream may still hit the synchronous ChatModel path.)
            MemoryIdContext.set(memoryId);

            final Object completion = new Object();
            final boolean[] done = {false};

            try {
                streamingSreAgent.investigateStreaming(memoryId, incidentDescription)
                        .onPartialResponse((String token) -> {
                            writer.print(token);
                            writer.flush();
                        })
                        .onCompleteResponse(resp -> {
                            writer.flush();
                            synchronized (completion) {
                                done[0] = true;
                                completion.notifyAll();
                            }
                        })
                        .onError((Throwable err) -> {
                            writer.println();
                            writer.println("Error: " + err.getMessage());
                            writer.flush();
                            synchronized (completion) {
                                done[0] = true;
                                completion.notifyAll();
                            }
                        })
                        .start();

                // Block the request thread until the stream finishes so Spring
                // doesn't close the underlying outputStream early.
                synchronized (completion) {
                    while (!done[0]) {
                        completion.wait();
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                writer.println("Error during investigation: " + e.getMessage());
                writer.flush();
            } finally {
                MemoryIdContext.clear();
                writer.close();
            }
        };
    }
}
