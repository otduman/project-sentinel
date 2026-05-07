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
 * Filesystem-side of the patch approval flow. Phase 2:
 * <ol>
 *   <li>{@link #apply(ProposedPatch)} writes a backup of the existing file to
 *       the configured backup directory, then atomically replaces the target
 *       with the LLM-proposed new content.</li>
 *   <li>Spring DevTools (configured on lab-rat) watches the source tree and
 *       hot-restarts the JVM when the file changes — so no Docker rebuild
 *       is needed for the patch to take effect.</li>
 *   <li>{@link #rollback(ProposedPatch)} restores the most recent backup for
 *       this patch's file. Used both on apply-failure and on the explicit
 *       rollback endpoint.</li>
 * </ol>
 *
 * <h3>Defence in depth</h3>
 * <ul>
 *   <li>{@link PatchPathValidator#normalise} on the way in — rejects path
 *       traversal, non-Java files, paths outside lab-rat.</li>
 *   <li>{@link Path#toRealPath} before write — resolves symlinks; if the
 *       resolved path escapes the allowed root, the apply is rejected. This
 *       defends against a symlink planted under {@code com.sentinel.lab_rat}
 *       that would otherwise let the validator's prefix check be bypassed.</li>
 *   <li>Backup written before overwrite — every apply is reversible.</li>
 *   <li>Atomic move via {@link StandardCopyOption#ATOMIC_MOVE} — partial-write
 *       failure can't leave a corrupted source file.</li>
 * </ul>
 */
@Service
public class PatchApplier {

    /**
     * Where the lab-rat source tree is mounted inside the agent container.
     * Defaults to the production value; overridden in tests via
     * {@code ReflectionTestUtils} to point at a temp directory.
     */
    @Value("${agent.patch.src-root:" + PatchPathValidator.LAB_RAT_SRC_ROOT + "}")
    private String srcRoot;

    @Value("${agent.patch.backup-dir:/lab-rat-src/.sentinel-backups}")
    private String backupDir;

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
     * Apply the patch. Writes a backup, then atomically replaces the target
     * file with {@code patch.newContent}. Returns a {@link Result} — never
     * throws to the caller. The Phase-1 path validator ran when the patch
     * was first persisted; we re-validate here too because the patch row
     * has been at rest in the DB for an unknown duration.
     */
    public Result apply(ProposedPatch patch) {
        Path target;
        try {
            // Re-validate: the path was checked at write time, but defense-in-depth
            // means we don't trust DB content as a substitute for a fresh check.
            // The validator anchors at LAB_RAT_SRC_ROOT; we re-anchor at srcRoot
            // so tests can point at a temp directory without needing root privs.
            target = resolveTargetPath(patch.getFilePath());
        } catch (IllegalArgumentException e) {
            return Result.fail("Path validation failed: " + e.getMessage());
        }

        // Symlink defense — toRealPath() resolves any symlinks and we re-check
        // the prefix. Without this, a symlink under com/sentinel/lab_rat could
        // point to anywhere on the filesystem and the prefix check would still
        // pass (because the path string starts with the right prefix).
        Path resolved;
        try {
            // toRealPath fails if the file doesn't exist; that's fine for a
            // fresh-add case but our patches always replace existing classes.
            resolved = target.toRealPath();
        } catch (NoSuchFileException e) {
            return Result.fail("Target file does not exist: " + target);
        } catch (IOException e) {
            return Result.fail("Could not resolve real path: " + e.getMessage());
        }
        Path expectedRoot = Path.of(srcRoot, PatchPathValidator.LAB_RAT_PACKAGE_DIR)
                .toAbsolutePath().normalize();
        if (!resolved.toAbsolutePath().normalize().startsWith(expectedRoot)) {
            return Result.fail("Resolved path escapes lab-rat package: " + resolved);
        }

        // Drift detection — refuse to overwrite if the file on disk doesn't
        // still match what Gemini saw when it generated newContent. A null
        // oldContent at this point is a contract violation (proposeFix is
        // supposed to refuse blank oldCode); we treat it as a hard fail
        // instead of skip-and-write because skipping would silently clobber
        // any out-of-band edits made between proposal and approval.
        // Line endings are normalised so a CRLF/LF difference (Windows IDE
        // on a Linux container) doesn't trip a false positive.
        if (patch.getOldContent() == null || patch.getOldContent().isBlank()) {
            return Result.fail(
                    "Patch missing oldContent — refusing blind overwrite. "
                            + "Re-run the investigation so a fresh proposeFix call captures the current file.");
        }
        try {
            String onDisk = Files.readString(resolved, StandardCharsets.UTF_8);
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
            backupPath = backupTarget(resolved, patch.getId().toString());
        } catch (IOException e) {
            return Result.fail("Backup failed (refusing to overwrite without backup): " + e.getMessage());
        }

        // Atomic replace via temp file + ATOMIC_MOVE. Crash mid-write leaves
        // either the original or the new file, never a partial. Some Docker
        // bind-mount filesystems (notably gRPC FUSE on Docker Desktop for
        // Windows) don't support ATOMIC_MOVE — we fall back to a non-atomic
        // REPLACE_EXISTING in that case. The window for partial-write damage
        // is bounded by the new content size and the backup is already on
        // disk by this point, so rollback remains possible.
        try {
            Path tmp = Files.createTempFile(resolved.getParent(), ".sentinel-patch-", ".java.tmp");
            Files.writeString(tmp, patch.getNewContent(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicNotSupported) {
                Files.move(tmp, resolved, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Try to put the file back from the backup we just wrote.
            try {
                Files.copy(backupPath, resolved, StandardCopyOption.REPLACE_EXISTING);
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
        Path target;
        try {
            target = resolveTargetPath(patch.getFilePath());
        } catch (IllegalArgumentException e) {
            return Result.fail("Path validation failed: " + e.getMessage());
        }
        Path backupPath = mostRecentBackupFor(target.getFileName().toString(), patch.getId().toString());
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
     * the drift check: a file written with LF on a Linux container is read
     * back as LF, but if a host-side editor inserted CRLFs, raw equality
     * would falsely report drift. We don't care about line-ending style for
     * the safety check — only the actual textual content.
     */
    private static String normaliseLineEndings(String s) {
        if (s == null) return null;
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * Validates the LLM-supplied path via {@link PatchPathValidator}, then
     * re-anchors it under the configured {@link #srcRoot}. The validator's
     * canonical output is anchored at the production
     * {@link PatchPathValidator#LAB_RAT_SRC_ROOT} constant; tests override
     * {@code srcRoot} to a temp directory so we don't need to write to a
     * production-shaped path on the test machine.
     */
    private Path resolveTargetPath(String rawPath) {
        String canonical = PatchPathValidator.normalise(rawPath);
        // Strip the well-known LAB_RAT_SRC_ROOT prefix and "/", leaving just
        // the relative path under the source root (main/java/com/sentinel/lab_rat/Foo.java).
        String relativeTail = canonical.substring(PatchPathValidator.LAB_RAT_SRC_ROOT.length() + 1);
        return Path.of(srcRoot).resolve(relativeTail);
    }

    // ------------------------------------------------------------------------
    // Backup mechanics
    // ------------------------------------------------------------------------

    /**
     * Copies the target file into the backup dir under a name that encodes
     * the patch id and timestamp. Pattern: {@code <fileName>.<patchId>.<epochMillis>.bak}.
     */
    private Path backupTarget(Path target, String patchId) throws IOException {
        Path backupRoot = Path.of(backupDir);
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
     * Finds the most recent backup matching this patch id, used by rollback.
     * Returns null if none exist.
     */
    private Path mostRecentBackupFor(String fileName, String patchId) {
        Path backupRoot = Path.of(backupDir);
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
