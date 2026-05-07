package com.sentinel.agent;

import java.util.regex.Pattern;

/**
 * Validates the file path the LLM submits to the {@code proposeFix} tool.
 *
 * <p>Sentinel only ever fixes lab-rat — never itself, never sentinel-core,
 * never anything outside the lab-rat Java source tree. This class is the single
 * choke point that enforces that. Bypassing it (or accepting LLM-supplied paths
 * directly into a file write) would let a manipulated alert or compromised
 * Gemini response touch arbitrary files on disk.
 *
 * <h3>Accepted shape</h3>
 * <ul>
 *   <li>Plain class name with optional sub-package: {@code ChaosController.java}
 *       or {@code com/sentinel/lab_rat/ChaosController.java}</li>
 *   <li>Repo-relative: {@code lab-rat/src/main/java/com/sentinel/lab_rat/ChaosController.java}</li>
 *   <li>Container-absolute: {@code /lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java}</li>
 * </ul>
 * All three are normalised by {@link #normalise(String)} to the canonical
 * container-absolute form for downstream consumers.
 *
 * <h3>Rejected shape</h3>
 * Anything containing {@code ..} (path traversal), backslashes, leading
 * {@code /} that isn't {@code /lab-rat-src/}, references outside
 * {@code com/sentinel/lab_rat/}, or non-{@code .java} extensions.
 */
public final class PatchPathValidator {

    /** Container-side root where {@code lab-rat/src} is bind-mounted in Phase 2. */
    public static final String LAB_RAT_SRC_ROOT = "/lab-rat-src";

    /** Java package the agent is allowed to modify. */
    public static final String LAB_RAT_PACKAGE_DIR = "main/java/com/sentinel/lab_rat";

    /** A class name segment: {@code Foo}, {@code Foo$Inner} disallowed (no $). */
    private static final Pattern CLASS_SEGMENT = Pattern.compile("^[A-Z][A-Za-z0-9_]*\\.java$");

    private PatchPathValidator() { }

    /**
     * Validates and returns the canonical container-absolute path. Throws
     * {@link IllegalArgumentException} on any reject — caller (SreTools) catches
     * this and returns the message to Gemini so it can correct itself.
     */
    public static String normalise(String rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException("filePath is null");
        }
        String trimmed = rawPath.trim().replace('\\', '/');
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("filePath is empty");
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("filePath must not contain '..' (path traversal)");
        }

        // Strip every leading layer that matches a known prefix. The previous
        // single-pass version broke on the first match, so a fully qualified
        // path like /lab-rat-src/main/java/com/sentinel/lab_rat/Foo.java only
        // had `/lab-rat-src/` stripped — leaving slashes in `tail` that then
        // failed the classname regex. Iterating until no prefix matches lets
        // any nesting of valid prefixes collapse to just the classname.
        String tail = trimmed;
        String[] knownPrefixes = {
                "/lab-rat-src/",
                "lab-rat/src/",
                LAB_RAT_PACKAGE_DIR + "/",
                "com/sentinel/lab_rat/",
        };
        boolean stripped;
        do {
            stripped = false;
            for (String p : knownPrefixes) {
                if (tail.startsWith(p)) {
                    tail = tail.substring(p.length());
                    stripped = true;
                    break;
                }
            }
        } while (stripped);

        // After prefix-stripping, tail should be a class name (with optional
        // sub-path under com/sentinel/lab_rat). We don't permit sub-packages
        // for now — keeps the surface area tight; expand when the LLM has a
        // legitimate reason to touch nested packages.
        if (!CLASS_SEGMENT.matcher(tail).matches()) {
            throw new IllegalArgumentException(
                    "filePath must be a single .java class inside com.sentinel.lab_rat — got '" + rawPath + "'");
        }

        return LAB_RAT_SRC_ROOT + "/" + LAB_RAT_PACKAGE_DIR + "/" + tail;
    }

    /**
     * Pure boolean check — used by tests and quick UI validation.
     */
    public static boolean isValid(String rawPath) {
        try {
            normalise(rawPath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
