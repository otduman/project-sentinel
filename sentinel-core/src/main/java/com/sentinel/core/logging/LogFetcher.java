package com.sentinel.core.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility to fetch and filter logs for the AI Agent.
 */
public class LogFetcher {

    /**
     * Reads the last N lines of a log file and filters for specific severity levels.
     * 
     * @param logPath Path to the application log file.
     * @param maxLines Number of lines to search from the tail.
     * @return Filtered logs containing ERROR or WARN strings.
     */
    public static String fetchErrorsAndWarnings(String logPath, int maxLines) {
        try {
            List<String> allLines = Files.readAllLines(Paths.get(logPath));
            
            // Get the last N lines (tail)
            int startIndex = Math.max(0, allLines.size() - maxLines);
            List<String> tailLines = allLines.subList(startIndex, allLines.size());

            // Filter for ERROR or WARN
            String filtered = tailLines.stream()
                .filter(line -> line.contains("ERROR") || line.contains("WARN"))
                .collect(Collectors.joining("\n"));

            if (filtered.isEmpty()) {
                return "No errors or warnings found in the last " + maxLines + " lines.";
            }
            return filtered;

        } catch (IOException e) {
            return "Failed to read log file at " + logPath + ": " + e.getMessage();
        }
    }
}
