package com.sentinel.core.profiler;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the Dynamic Profiler injected into the Lab Rat JVM via ByteBuddy attach.
 *
 * agentArgs format: "targetPackage|/absolute/path/to/report.txt"
 */
public class SentinelAgentMain {

    // Static reference so we can shut down the old executor if agentmain is called again
    // (e.g. multiple profiling sessions against the same JVM).
    private static volatile ScheduledExecutorService executor;

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("[Sentinel Agent] Injected. Args: " + agentArgs);

        // Shut down any executor left over from a previous profiling session.
        if (executor != null) {
            executor.shutdownNow();
        }

        // Parse "targetPackage|/path/to/report.txt"
        String targetPackage = "com.sentinel.lab_rat";
        String reportFilePath = "profiler_report.txt"; // fallback (same dir as JVM working dir)
        if (agentArgs != null && agentArgs.contains("|")) {
            String[] parts = agentArgs.split("\\|", 2);
            targetPackage = parts[0];
            reportFilePath = parts[1];
        } else if (agentArgs != null && !agentArgs.isEmpty()) {
            targetPackage = agentArgs;
        }

        System.out.println("[Sentinel Agent] Targeting package: " + targetPackage);
        System.out.println("[Sentinel Agent] Report path: " + reportFilePath);

        MethodTimerInterceptor.METRICS.clear();

        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(ElementMatchers.nameStartsWith(targetPackage))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(MethodTimerInterceptor.class).on(ElementMatchers.any()))
            )
            .installOn(inst);

        final String finalReportPath = reportFilePath;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sentinel-profiler-reporter");
            t.setDaemon(true); // don't prevent JVM shutdown
            return t;
        });
        executor.schedule(() -> {
            try {
                StringBuilder sb = new StringBuilder("--- SENTINEL DIAGNOSTIC REPORT ---\n");
                // Snapshot metrics under compute to get a consistent read
                MethodTimerInterceptor.METRICS.forEach((method, stats) -> {
                    long[] snapshot = stats.snapshot();
                    long count = snapshot[0];
                    long totalMs = snapshot[1] / 1_000_000;
                    long avgMs = count > 0 ? totalMs / count : 0;
                    sb.append(String.format(
                        "Method: %s | Invocations: %d | Total Time: %d ms | Avg Time: %d ms%n",
                        method, count, totalMs, avgMs));
                });

                Path report = Path.of(finalReportPath);
                Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
                System.out.println("[Sentinel Agent] Report written to " + report.toAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                executor.shutdown(); // self-cleanup after firing
            }
        }, 10, TimeUnit.SECONDS);
    }
}
