package com.sentinel.core.profiler;

import net.bytebuddy.agent.ByteBuddyAgent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * Attaches the Sentinel profiler agent to an external JVM.
 * This class runs inside the AI Sentinel Agent process, not inside Lab Rat.
 */
public class ProfilerAttacher {

    private static final int PROFILER_TIMEOUT_SECONDS = 30;

    /**
     * Attaches the Sentinel Java Agent to an external JVM and waits for the report.
     *
     * @param pid           Process ID of the Lab Rat application.
     * @param agentJarPath  Path to the compiled sentinel-core fat jar.
     * @param targetPackage The package the agent should instrument.
     * @return The contents of the profiler report.
     */
    public static String attach(String pid, String agentJarPath, String targetPackage) throws Exception {
        File agentFile = new File(agentJarPath);
        if (!agentFile.exists()) {
            throw new RuntimeException("Agent JAR not found at: " + agentJarPath);
        }

        // Use a temp file with an absolute path agreed upon by both sides.
        // Passing the path as agentArgs means SentinelAgentMain writes to the exact same file.
        Path reportPath = Files.createTempFile("sentinel-profiler-", ".txt");
        Files.delete(reportPath); // We want to watch for its creation, not its modification

        // agentArgs format: "targetPackage|/absolute/path/to/report.txt"
        String agentArgs = targetPackage + "|" + reportPath.toAbsolutePath();

        System.out.println("Attaching to PID: " + pid + " | Report will be written to: " + reportPath);
        ByteBuddyAgent.attach(agentFile, pid, agentArgs);
        System.out.println("Attachment successful. Waiting up to " + PROFILER_TIMEOUT_SECONDS + "s for report...");

        // Watch the parent directory for the report file to appear instead of sleeping blindly.
        return waitForReport(reportPath);
    }

    private static String waitForReport(Path reportPath) throws IOException, InterruptedException {
        Path parentDir = reportPath.getParent();
        try (WatchService watcher = parentDir.getFileSystem().newWatchService()) {
            parentDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(PROFILER_TIMEOUT_SECONDS);
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                WatchKey key = watcher.poll(remaining, TimeUnit.MILLISECONDS);
                if (key == null) break; // timeout

                for (var event : key.pollEvents()) {
                    Path changed = parentDir.resolve((Path) event.context());
                    if (changed.equals(reportPath) && Files.exists(reportPath)) {
                        String report = Files.readString(reportPath, StandardCharsets.UTF_8);
                        // Guard against an empty report file: the agent attached successfully
                        // but collected no allocation data during the profiling window.
                        return (report == null || report.isBlank())
                                ? "Profiler report was empty — no allocation data captured during the profiling window."
                                : report;
                    }
                }
                key.reset();
            }
        }
        return "Error: profiler report was not generated within " + PROFILER_TIMEOUT_SECONDS + " seconds.";
    }
}
