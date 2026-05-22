package com.sentinel.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Filesystem-side of the patch approval flow. Multi-service edition:
 * <ol>
 *   <li>{@link #apply(ProposedPatch)} validates + resolves which service the
 *       patch belongs to, writes a backup under that service's source root,
 *       then atomically replaces the target with the LLM-proposed new content.</li>
 *   <li>{@link #rollback(ProposedPatch)} restores the most recent backup for
 *       this patch's file. Used both on apply-failure and on the explicit
 *       rollback endpoint.</li>
 * </ol>
 *
 * <h3>Defence in depth</h3>
 * <ul>
 *   <li>{@link PatchPathValidator#resolve} on the way in — rejects path
 *       traversal, non-Java files, paths outside the allowed service set.</li>
 *   <li>{@link Path#toRealPath} before write — resolves symlinks; the resolved
 *       path must still live under the matched service's package root.</li>
 *   <li>Drift detection — refuses overwrite if the file on disk no longer
 *       matches {@code patch.getOldContent()}.</li>
 *   <li>Backup written before overwrite.</li>
 *   <li>Atomic move via {@link StandardCopyOption#ATOMIC_MOVE} with a
 *       non-atomic fallback for bind-mount filesystems that don't support it.</li>
 * </ul>
 */
@Service
public class PatchApplier {

    /**
     * Prefix that gets prepended to the validator's canonical paths. In
     * production this is {@code ""} (paths are already absolute container
     * paths like {@code /lab-rat-src/...}). In tests it's a {@code @TempDir},
     * so the same canonical paths land under {@code <temp>/lab-rat-src/...}
     * — letting the same code exercise every service without writing to
     * real production-shaped paths on the dev machine.
     */
    @Value("${agent.patch.root-base:}")
    private String rootBase;

    /** Result envelope so the controller can surface failure reasons in the response. */
    public record Result(boolean success, String message, String backupPath) {
        public static Result ok(String backupPath) {
            return new Result(true, "ok", backupPath);
        }
        public static Result fail(String reason) {
            return new Result(false, reason, null);
        }
    }

    /**
     * Apply the patch. Resolves which service it targets, writes a backup
     * under that service's root, then atomically replaces the file with
     * {@code patch.newContent}. Returns a {@link Result} — never throws.
     */
    public Result apply(ProposedPatch patch) {
        PatchPathValidator.Resolved resolved;
        try {
            // Re-validate — the path was checked at proposeFix time, but
            // defense-in-depth means we don't trust DB content as a substitute
            // for a fresh check.
            resolved = PatchPathValidator.resolve(patch.getFilePath());
        } catch (IllegalArgumentException e) {
            return Result.fail("Path validation failed: " + e.getMessage());
        }

        PatchPathValidator.ServiceConfig svc = PatchPathValidator.SERVICES.get(resolved.serviceName());
        if (svc == null) {
            return Result.fail("Unknown service: " + resolved.serviceName());
        }

        Path target = absoluteTargetPath(resolved.canonicalPath());

        // Symlink defence — toRealPath() resolves any symlinks; the resolved
        // path must STILL live under this service's package root. Without
        // this, a symlink planted under the service's package could escape
        // the gate.
        Path real;
        try {
            real = target.toRealPath();
        } catch (NoSuchFileException e) {
            return Result.fail("Target file does not exist: " + target);
        } catch (IOException e) {
            return Result.fail("Could not resolve real path: " + e.getMessage());
        }
        Path expectedRoot = absoluteTargetPath(svc.srcRoot + "/" + svc.packageDir)
                .toAbsolutePath().normalize();
        if (!real.toAbsolutePath().normalize().startsWith(expectedRoot)) {
            return Result.fail("Resolved path escapes " + resolved.serviceName() + " package: " + real);
        }

        // Drift detection — see class Javadoc.
        if (patch.getOldContent() == null || patch.getOldContent().isBlank()) {
            return Result.fail(
                    "Patch missing oldContent — refusing blind overwrite. "
                            + "Re-run the investigation so a fresh proposeFix call captures the current file.");
        }
        try {
            String onDisk = Files.readString(real, StandardCharsets.UTF_8);
            if (!Objects.equals(normaliseLineEndings(onDisk),
                    normaliseLineEndings(patch.getOldContent()))) {
                return Result.fail(
                        "Drift detected: target file changed since the patch was proposed. "
                                + "Refusing to overwrite. Re-run the investigation to generate a fresh patch.");
            }
        } catch (IOException e) {
            return Result.fail("Could not read target for drift check: " + e.getMessage());
        }

        // Write backup before touching the target. Backup name encodes the
        // patch id and timestamp so multiple patches against the same file
        // don't collide.
        Path backupPath;
        try {
            backupPath = backupTarget(real, patch.getId().toString(), svc);
        } catch (IOException e) {
            return Result.fail("Backup failed (refusing to overwrite without backup): " + e.getMessage());
        }

        // Atomic replace via temp file + ATOMIC_MOVE with fallback to
        // REPLACE_EXISTING for bind-mount filesystems that lack atomic move.
        try {
            Path tmp = Files.createTempFile(real.getParent(), ".sentinel-patch-", ".java.tmp");
            Files.writeString(tmp, patch.getNewContent(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicNotSupported) {
                Files.move(tmp, real, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.copy(backupPath, real, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException restoreFail) {
                return Result.fail("Write failed AND restore failed: " + e.getMessage()
                        + " / " + restoreFail.getMessage());
            }
            return Result.fail("Write failed (restored from backup): " + e.getMessage());
        }

        return Result.ok(backupPath.toString());
    }

    /**
     * Restores the most-recent backup for this patch's file, reverting
     * whatever the {@link #apply(ProposedPatch)} call wrote.
     */
    public Result rollback(ProposedPatch patch) {
        PatchPathValidator.Resolved resolved;
        try {
            resolved = PatchPathValidator.resolve(patch.getFilePath());
        } catch (IllegalArgumentException e) {
            return Result.fail("Path validation failed: " + e.getMessage());
        }
        PatchPathValidator.ServiceConfig svc = PatchPathValidator.SERVICES.get(resolved.serviceName());
        if (svc == null) {
            return Result.fail("Unknown service: " + resolved.serviceName());
        }
        Path target = absoluteTargetPath(resolved.canonicalPath());
        Path backupPath = mostRecentBackupFor(target.getFileName().toString(), patch.getId().toString(), svc);
        if (backupPath == null) {
            return Result.fail("No backup found for patch " + patch.getId());
        }
        try {
            Files.copy(backupPath, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return Result.fail("Rollback copy failed: " + e.getMessage());
        }
        return Result.ok(backupPath.toString());
    }

    /**
     * Collapses CR/CRLF/LF to LF before comparing file contents. Required for
     * the drift check so a CRLF/LF style difference doesn't trip drift.
     */
    private static String normaliseLineEndings(String s) {
        if (s == null) return null;
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * Prepends the (optional) test root-base to the validator's canonical
     * path. Production rootBase is "" so the canonical path is used as-is.
     * Tests set rootBase to a temp directory.
     */
    private Path absoluteTargetPath(String canonicalPath) {
        if (rootBase == null || rootBase.isEmpty()) return Path.of(canonicalPath);
        // canonicalPath always starts with "/" — strip it so resolve() works
        // regardless of whether rootBase ends with a separator.
        String tail = canonicalPath.startsWith("/") ? canonicalPath.substring(1) : canonicalPath;
        return Path.of(rootBase).resolve(tail);
    }

    /** Where this service's backups live: {@code <srcRoot>/.sentinel-backups/}. */
    private Path backupRootFor(PatchPathValidator.ServiceConfig svc) {
        return absoluteTargetPath(svc.srcRoot + "/.sentinel-backups");
    }

    /**
     * Copies the target file into the service's backup dir. Pattern:
     * {@code <fileName>.<patchId>.<epochMillis>.bak}. Per-service backup
     * directories mean a rollback for service A can't accidentally restore
     * a file in service B.
     */
    private Path backupTarget(Path target, String patchId, PatchPathValidator.ServiceConfig svc) throws IOException {
        Path backupRoot = backupRootFor(svc);
        Files.createDirectories(backupRoot);
        String name = target.getFileName().toString()
                + "." + patchId
                + "." + Instant.now().toEpochMilli()
                + ".bak";
        Path dest = backupRoot.resolve(name);
        Files.copy(target, dest, StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }

    /**
     * Finds the most recent backup matching this patch id under the given
     * service's backup directory. Returns null if none exist.
     */
    private Path mostRecentBackupFor(String fileName, String patchId, PatchPathValidator.ServiceConfig svc) {
        Path backupRoot = backupRootFor(svc);
        if (!Files.isDirectory(backupRoot)) return null;
        String prefix = fileName + "." + patchId + ".";
        try (var stream = Files.list(backupRoot)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
