package com.sentinel.agent;

import com.sentinel.core.logging.LogFetcher;
import com.sentinel.core.profiler.ProfilerAttacher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.UUID;
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
    private final ProposedPatchRepository proposedPatchRepository;

    public SreTools(RunbookRepository runbookRepository,
                    InvestigationRepository investigationRepository,
                    ProposedPatchRepository proposedPatchRepository) {
        this.runbookRepository = runbookRepository;
        this.investigationRepository = investigationRepository;
        this.proposedPatchRepository = proposedPatchRepository;
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

    @Tool("Proposes a structured source-code fix to a single .java file inside the lab-rat package " +
            "(com.sentinel.lab_rat). Persists the patch in PENDING_REVIEW status — a human must approve " +
            "or reject it via the dashboard before any file is touched. Provide the EXACT current " +
            "contents of the file as oldCode (so a diff can be shown) and the full replacement " +
            "contents as newCode. A short rationale is required for the audit log. Call this only " +
            "after identifying the root cause and only for files within com.sentinel.lab_rat.")
    public String proposeFix(
            @P("Java file inside lab-rat — e.g. 'ChaosController.java' or " +
                    "'com/sentinel/lab_rat/ChaosController.java'. Must be a .java file under com.sentinel.lab_rat.")
            String filePath,
            @P("EXACT current contents of the target file. Used to build a diff for the human reviewer " +
                    "and (in Phase 2) to detect drift before applying. If unsure, fetch the file first.")
            String oldCode,
            @P("Full proposed contents of the file after the fix. NOT a diff — the complete replacement " +
                    "file. Must compile against Spring Boot 4.0 and Java 21.")
            String newCode,
            @P("One- or two-sentence justification for the change. Stored on the patch row for audit.")
            String rationale) {

        // 1. Look up the active investigation via the per-call ThreadLocal that
        //    InvestigationService populates at investigation start. Without this
        //    we can't link the patch back to its investigation row.
        String memoryId = MemoryIdContext.get();
        UUID investigationId;
        try {
            investigationId = UUID.fromString(memoryId);
        } catch (Exception e) {
            return "ERROR: proposeFix called outside an active investigation context. "
                    + "This tool is only valid mid-investigation.";
        }

        // 2. Validate the path against the allowlist BEFORE persisting anything.
        //    This is the single safety gate that keeps the agent from touching
        //    files outside lab-rat — including its own source.
        String canonicalPath;
        try {
            canonicalPath = PatchPathValidator.normalise(filePath);
        } catch (IllegalArgumentException e) {
            return "REJECTED: " + e.getMessage()
                    + ". Re-call proposeFix with a valid path inside com.sentinel.lab_rat.";
        }

        // 3. Size & shape sanity. 64KB per side is generous for a single
        //    class file. oldCode is REQUIRED — without it the applier cannot
        //    do drift detection and would silently overwrite any out-of-band
        //    edits made between proposal and approval. Refusing a blind
        //    overwrite is the safer default.
        int maxBytes = 64 * 1024;
        if (newCode == null || newCode.isBlank()) {
            return "REJECTED: newCode is empty. proposeFix requires the full replacement file content.";
        }
        if (oldCode == null || oldCode.isBlank()) {
            return "REJECTED: oldCode is empty. proposeFix requires the EXACT current contents "
                    + "of the target file so drift can be detected before overwriting. "
                    + "Read the file first, then re-call with oldCode populated.";
        }
        if (newCode.length() > maxBytes || oldCode.length() > maxBytes) {
            return "REJECTED: file content exceeds the 64KB cap for proposed patches.";
        }

        // 4. Persist as PENDING_REVIEW. Dashboard surfaces this; human approves/rejects.
        ProposedPatch patch = ProposedPatch.create(
                investigationId, canonicalPath, oldCode, newCode, rationale);
        ProposedPatch saved = proposedPatchRepository.save(patch);

        System.out.println("[Sentinel FixProposer] Patch " + saved.getId()
                + " queued for review (file=" + canonicalPath
                + ", investigation=" + investigationId + ")");

        return "SUCCESS. Patch " + saved.getId() + " queued for human approval. "
                + "File: " + canonicalPath + ". Status: PENDING_REVIEW.";
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
