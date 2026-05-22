package com.sentinel.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates the file path the LLM submits to the {@code proposeFix} tool and
 * resolves which service the path belongs to.
 *
 * <p>Sentinel is allowed to write into a small allowlist of target services
 * — lab-rat, order-service, payment-service — and nothing else. This class
 * is the single choke point that enforces that. Bypassing it (or accepting
 * LLM-supplied paths directly into a file write) would let a manipulated
 * alert or compromised Gemini response touch arbitrary files on disk.
 *
 * <h3>Accepted shapes (resolved to a single canonical container-absolute form)</h3>
 * <ul>
 *   <li>Plain class name (legacy lab-rat form): {@code ChaosController.java}
 *       — resolved to lab-rat for backwards compatibility</li>
 *   <li>Package-prefixed class: {@code com/sentinel/order_service/OrderController.java}
 *       — service inferred from the package name (com.sentinel.<service-package>)</li>
 *   <li>Repo-relative: {@code order-service/src/main/java/com/sentinel/order_service/OrderController.java}</li>
 *   <li>Container-absolute: {@code /order-service-src/main/java/com/sentinel/order_service/OrderController.java}</li>
 * </ul>
 *
 * <h3>Rejected</h3>
 * Anything containing {@code ..} (path traversal), backslashes that don't
 * normalise, references outside the allowed package set, non-{@code .java}
 * extensions, or class-name segments that fail {@link #CLASS_SEGMENT}.
 */
public final class PatchPathValidator {

    /**
     * Definition of one patchable service — the bind-mount root inside the
     * agent container, the Maven-shaped package directory inside that root,
     * and the dotted Java package the path must end up referencing.
     */
    public static final class ServiceConfig {
        public final String name;
        public final String srcRoot;       // e.g. "/order-service-src"
        public final String packageDir;    // e.g. "main/java/com/sentinel/order_service"
        public final String javaPackage;   // e.g. "com/sentinel/order_service"
        public final String[] aliases;     // alternative prefixes the LLM might use

        ServiceConfig(String name, String srcRoot, String packageDir, String javaPackage, String... aliases) {
            this.name = name;
            this.srcRoot = srcRoot;
            this.packageDir = packageDir;
            this.javaPackage = javaPackage;
            this.aliases = aliases;
        }
    }

    /**
     * Registry of every service the agent is allowed to patch. Adding a new
     * one here AND mounting its {@code src} directory at {@code srcRoot} in
     * docker-compose is the only wiring needed; no other code change.
     *
     * <p>Order matters for legacy paths: the first entry wins when the path
     * is ambiguous (e.g. a bare {@code ChaosController.java} with no package
     * prefix maps to lab-rat to preserve the original tool contract).
     */
    public static final Map<String, ServiceConfig> SERVICES = new LinkedHashMap<>();
    static {
        SERVICES.put("lab-rat", new ServiceConfig(
                "lab-rat",
                "/lab-rat-src",
                "main/java/com/sentinel/lab_rat",
                "com/sentinel/lab_rat",
                "lab-rat/src/"
        ));
        SERVICES.put("order-service", new ServiceConfig(
                "order-service",
                "/order-service-src",
                "main/java/com/sentinel/order_service",
                "com/sentinel/order_service",
                "order-service/src/"
        ));
        SERVICES.put("payment-service", new ServiceConfig(
                "payment-service",
                "/payment-service-src",
                "main/java/com/sentinel/payment_service",
                "com/sentinel/payment_service",
                "payment-service/src/"
        ));
    }

    /** Compatibility shims so existing call sites don't break. */
    public static final String LAB_RAT_SRC_ROOT = SERVICES.get("lab-rat").srcRoot;
    public static final String LAB_RAT_PACKAGE_DIR = SERVICES.get("lab-rat").packageDir;

    /** A class name segment: {@code Foo}, {@code Foo$Inner} disallowed (no $). */
    private static final Pattern CLASS_SEGMENT = Pattern.compile("^[A-Z][A-Za-z0-9_]*\\.java$");

    /**
     * Result of a successful validation: the canonical container path AND
     * the service it belongs to. The service name is used downstream for
     * patch-row labelling and per-service backup-dir routing.
     */
    public record Resolved(String canonicalPath, String serviceName) { }

    private PatchPathValidator() { }

    /**
     * Validates and resolves the path. Returns {@link Resolved} on success;
     * throws {@link IllegalArgumentException} on reject — caller (SreTools)
     * surfaces the message back to Gemini so it can correct itself.
     */
    public static Resolved resolve(String rawPath) {
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

        // Walk every service's prefix list and strip layers iteratively. The
        // service that finally consumes a prefix is the match; if multiple
        // services can claim layers (because aliases collide), the earlier
        // one in SERVICES wins by virtue of being tested first.
        ServiceConfig matched = null;
        String tail = trimmed;
        boolean stripped;
        do {
            stripped = false;
            for (ServiceConfig svc : SERVICES.values()) {
                for (String p : prefixesFor(svc)) {
                    if (tail.startsWith(p)) {
                        tail = tail.substring(p.length());
                        stripped = true;
                        if (matched == null) matched = svc;
                        break;
                    }
                }
                if (stripped) break;
            }
        } while (stripped);

        // Default to lab-rat when no service prefix matched — preserves the
        // long-standing "proposeFix('ChaosController.java')" form from the
        // pre-multi-service era. The class-name regex still has to pass so
        // we don't silently route garbage to lab-rat.
        if (matched == null) {
            matched = SERVICES.get("lab-rat");
        }

        if (!CLASS_SEGMENT.matcher(tail).matches()) {
            throw new IllegalArgumentException(
                    "filePath must resolve to a single .java class inside one of "
                            + SERVICES.keySet() + " — got '" + rawPath + "'");
        }

        String canonical = matched.srcRoot + "/" + matched.packageDir + "/" + tail;
        return new Resolved(canonical, matched.name);
    }

    /** Backwards-compatible API — returns just the canonical path. */
    public static String normalise(String rawPath) {
        return resolve(rawPath).canonicalPath();
    }

    /** Pure boolean check — used by tests and quick UI validation. */
    public static boolean isValid(String rawPath) {
        try {
            resolve(rawPath);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Builds the prefix list for one service: the container root, the repo path, the package dir, the dotted package. */
    private static String[] prefixesFor(ServiceConfig svc) {
        return new String[]{
                svc.srcRoot + "/",
                svc.packageDir + "/",
                svc.javaPackage + "/",
                svc.aliases.length > 0 ? svc.aliases[0] : "",
        };
    }
}
