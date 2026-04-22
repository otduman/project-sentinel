package com.sentinel.agent;

import com.sentinel.core.logging.LogFetcher;
import com.sentinel.core.profiler.ProfilerAttacher;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Exposes Sentinel Core diagnostic capabilities to the LLM as callable tools.
 * LangChain4j reads the @Tool annotations to generate the JSON schema for Gemini.
 */
@Component
public class SreTools {

    @Value("${lab.rat.log.path}")
    private String logPath;

    @Value("${lab.rat.jar.path}")
    private String agentJarPath;

    private final RunbookRepository runbookRepository;
    private final InvestigationRepository investigationRepository;

    public SreTools(RunbookRepository runbookRepository, InvestigationRepository investigationRepository) {
        this.runbookRepository = runbookRepository;
        this.investigationRepository = investigationRepository;
    }

    @Tool("Fetches the latest ERROR and WARN logs from the target microservice. Use this to find stack traces.")
    public String fetchLatestErrors() {
        System.out.println("[AI Tool Invoked] Fetching logs...");
        String result = LogFetcher.fetchErrorsAndWarnings(logPath, 200);
        return (result == null || result.isBlank())
                ? "No data available from this tool."
                : result;
    }

    @Tool("Retrieves the Process ID (PID) of the Lab Rat microservice.")
    public String getLabRatPid() {
        System.out.println("[AI Tool Invoked] Discovering PID...");
        try {
            // Resolve jcmd from the current JVM's JAVA_HOME so it works regardless of PATH.
            String jcmdPath = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();

            Process p = new ProcessBuilder(jcmdPath)
                    .redirectErrorStream(true) // merge stderr into stdout — prevents pipe-buffer deadlock
                    .start();

            // Drain all output before checking exit code to avoid blocking the subprocess.
            String output;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                output = in.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Error: jcmd timed out after 5 seconds.";
            }
            if (p.exitValue() != 0) {
                return "Error: jcmd exited with code " + p.exitValue() + ": " + output;
            }

            for (String line : output.split("\n")) {
                if (line.contains("lab-rat")) {
                    String pidToken = line.trim().split("\\s+")[0];
                    // Validate it's actually a number before returning to the LLM.
                    if (pidToken.matches("\\d+")) {
                        return pidToken;
                    }
                }
            }
            return "Error: Could not find lab-rat process in jcmd output.";
        } catch (Exception e) {
            return "Error fetching PID: " + e.getMessage();
        }
    }

    @Tool("Dynamically injects a Java profiler into a running JVM to measure method execution times. Pass the PID retrieved from getLabRatPid() and the target package 'com.sentinel.lab_rat'.")
    public String runDynamicProfiler(String pid, String targetPackage) {
        if (!pid.matches("\\d{1,7}")) {
            return "Error: invalid PID format.";
        }
        if (!targetPackage.matches("[a-zA-Z0-9_.]+")) {
            return "Error: invalid package name.";
        }
        System.out.println("[AI Tool Invoked] Running dynamic profiler on PID " + pid + "...");
        try {
            String result = ProfilerAttacher.attach(pid, agentJarPath, targetPackage);
            return (result == null || result.isBlank())
                    ? "Profiler report was empty — no allocation data captured during the profiling window."
                    : result;
        } catch (Exception e) {
            return "Failed to run profiler: " + e.getMessage();
        }
    }

    @Tool("Proposes a source code fix based on the diagnostic findings. Formats the output as a unified diff or code block. Provide the specific file name and the patched code. Use this ONLY after identifying the root cause.")
    public String proposeFix(String fileToFix, String proposedCodeOverride) {
        System.out.println("[Sentinel FixProposer] Fix generated for " + fileToFix + "!");
        // Stores the proposal only — any future automation that applies this to the filesystem
        // must treat the content as untrusted and require explicit human approval before execution.
        return "SUCCESS. Fix registered in system memory for: " + fileToFix + "\n\n" + proposedCodeOverride;
    }

    @Tool("Retrieves the operational runbook for the given alert name. Returns step-by-step diagnosis and resolution procedures written by the SRE team. ALWAYS call this first at the start of any investigation.")
    public String lookupRunbook(String alertName) {
        System.out.println("[AI Tool Invoked] Looking up runbook for: " + alertName);
        return runbookRepository.findByAlertNameIgnoreCase(alertName)
                .map(r -> "RUNBOOK — " + r.getTitle() + " [" + r.getSeverity().toUpperCase() + "]\n\n"
                        + r.getDescription().trim() + "\n\nDIAGNOSIS STEPS:\n" + r.getSteps())
                .orElse("No runbook found for alert '" + alertName + "'. Proceed with general diagnostics.");
    }

    @Tool("Retrieves the last 5 completed investigations for the same alert type within the past 30 days. Use this to detect recurring issues and reference what fixes were previously proposed. Pass the exact alert name.")
    public String lookupPastInvestigations(String alertName) {
        System.out.println("[AI Tool Invoked] Looking up past investigations for: " + alertName);
        java.time.Instant since = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
        java.util.List<Investigation> past = investigationRepository
                .findByAlertNameAndStatusAndStartedAtAfterOrderByStartedAtDesc(alertName, "COMPLETE", since);
        if (past.isEmpty()) {
            return "No past completed investigations found for '" + alertName + "' in the last 30 days. This appears to be a new issue.";
        }
        StringBuilder sb = new StringBuilder("PAST INVESTIGATIONS for '").append(alertName).append("' (").append(past.size()).append(" found):\n\n");
        for (int i = 0; i < Math.min(5, past.size()); i++) {
            Investigation inv = past.get(i);
            sb.append("─── Investigation ").append(i + 1).append(" — ").append(inv.getStartedAt()).append(" ───\n");
            if (inv.getRootCause() != null) sb.append("Root Cause: ").append(inv.getRootCause()).append("\n");
            if (inv.getProposedFix() != null) sb.append("Proposed Fix: ").append(inv.getProposedFix()).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }
}
