package com.sentinel.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link PatchApplier} against a real temp filesystem mirroring the
 * container layout — under the temp root we recreate the path
 * {@code main/java/com/sentinel/lab_rat/Foo.java} so the validator's prefix
 * match still works.
 *
 * <p>We swap {@code PatchPathValidator}'s constants would require static
 * mocking, so we just verify the relative-path mapping by writing files
 * under a directory whose absolute path ends with the expected suffix.
 *
 * <p>The applier's allowlist is enforced via {@link PatchPathValidator} —
 * tests for the validator itself live in {@link PatchPathValidatorTest}.
 * Here we focus on file-write semantics, backups, and symlink defence.
 */
class PatchApplierTest {

    @TempDir
    Path tempRoot;

    private PatchApplier applier;
    private Path labRatPackageDir;
    private Path backupDir;

    @BeforeEach
    void setUp() throws IOException {
        // Recreate the canonical container layout under the temp root, then
        // point the applier's srcRoot at "<temp>/lab-rat-src" so writes land
        // inside the sandbox instead of /lab-rat-src on the test machine.
        Path srcRoot = tempRoot.resolve("lab-rat-src");
        labRatPackageDir = srcRoot.resolve("main/java/com/sentinel/lab_rat");
        Files.createDirectories(labRatPackageDir);
        backupDir = srcRoot.resolve(".sentinel-backups");

        applier = new PatchApplier();
        ReflectionTestUtils.setField(applier, "srcRoot", srcRoot.toString());
        ReflectionTestUtils.setField(applier, "backupDir", backupDir.toString());
    }

    private ProposedPatch buildPatch(String fileName, String oldContent, String newContent) throws IOException {
        Path target = labRatPackageDir.resolve(fileName);
        Files.writeString(target, oldContent);
        // Mirror production: SreTools.proposeFix stores the *canonical* path
        // (validator-normalised), not the short LLM-emitted form. The applier
        // re-validates that canonical path on read — which previously failed
        // because the prefix-strip loop only stripped one layer. This test
        // therefore locks the multi-strip fix in place by exercising the same
        // path shape the controller actually receives at runtime.
        String canonicalPath = PatchPathValidator.normalise(fileName);
        ProposedPatch p = ProposedPatch.create(
                UUID.randomUUID(),
                canonicalPath,
                oldContent,
                newContent,
                "test rationale");
        p.setId(UUID.randomUUID());
        return p;
    }

    @Test
    void apply_writesNewContentAndKeepsBackup() throws IOException {
        String oldContent = "class Foo { void a() {} }";
        String newContent = "class Foo { void a() { return; } }";
        ProposedPatch patch = buildPatch("Foo.java", oldContent, newContent);

        PatchApplier.Result result = applier.apply(patch);

        assertThat(result.success()).isTrue();
        Path target = labRatPackageDir.resolve("Foo.java");
        assertThat(Files.readString(target)).isEqualTo(newContent);

        // A backup must exist matching this patch id.
        Path backup = Path.of(result.backupPath());
        assertThat(backup).exists();
        assertThat(Files.readString(backup)).isEqualTo(oldContent);
        assertThat(backup.getFileName().toString()).contains(patch.getId().toString());
    }

    @Test
    void apply_invalidPathIsRejected() {
        // Path the validator should refuse outright (no .java extension).
        ProposedPatch p = ProposedPatch.create(
                UUID.randomUUID(), "Foo.txt", "old", "new", "test");
        p.setId(UUID.randomUUID());

        PatchApplier.Result result = applier.apply(p);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Path validation failed");
    }

    @Test
    void apply_pathTraversalIsRejected() {
        ProposedPatch p = ProposedPatch.create(
                UUID.randomUUID(), "../../../etc/Foo.java", "old", "new", "test");
        p.setId(UUID.randomUUID());

        PatchApplier.Result result = applier.apply(p);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Path validation failed");
    }

    @Test
    void apply_missingTargetFileIsRejected() {
        // Validator passes, but toRealPath() fails because the file doesn't exist.
        ProposedPatch p = ProposedPatch.create(
                UUID.randomUUID(), "DoesNotExist.java", "old", "new", "test");
        p.setId(UUID.randomUUID());

        PatchApplier.Result result = applier.apply(p);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsAnyOf("does not exist", "real path");
    }

    @Test
    void apply_driftDetected_refusesToOverwrite() throws IOException {
        // Patch was generated against "original", but someone edited the file
        // between proposal and approval. The applier must NOT clobber the
        // out-of-band edit.
        ProposedPatch patch = buildPatch("Drifted.java", "original", "modified");

        // Simulate the manual edit: rewrite the file behind the applier's back.
        Path target = labRatPackageDir.resolve("Drifted.java");
        Files.writeString(target, "human edit while patch was pending");

        PatchApplier.Result result = applier.apply(patch);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Drift detected");
        // Disk content must be untouched.
        assertThat(Files.readString(target)).isEqualTo("human edit while patch was pending");
    }

    @Test
    void apply_lineEndingDifferences_doNotTriggerFalseDrift() throws IOException {
        // A LF-only oldContent in the DB vs. CRLF-on-disk (e.g. Windows editor)
        // is a stylistic difference, not a content change — must not trip drift.
        ProposedPatch patch = buildPatch("Endings.java", "line1\nline2\nline3\n", "line1\nline2 changed\nline3\n");

        // Rewrite with CRLF endings.
        Path target = labRatPackageDir.resolve("Endings.java");
        Files.writeString(target, "line1\r\nline2\r\nline3\r\n");

        PatchApplier.Result result = applier.apply(patch);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("line1\nline2 changed\nline3\n");
    }

    @Test
    void rollback_restoresLastBackup() throws IOException {
        ProposedPatch patch = buildPatch("Bar.java", "original", "modified");

        // First apply — leaves the file modified, with a backup of "original".
        PatchApplier.Result applyResult = applier.apply(patch);
        assertThat(applyResult.success()).isTrue();

        Path target = labRatPackageDir.resolve("Bar.java");
        assertThat(Files.readString(target)).isEqualTo("modified");

        // Rollback should put "original" back.
        PatchApplier.Result rollbackResult = applier.rollback(patch);
        assertThat(rollbackResult.success()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("original");
    }

    @Test
    void rollback_withoutPriorApplyFails() {
        ProposedPatch p = ProposedPatch.create(
                UUID.randomUUID(), "Never.java", "old", "new", "test");
        p.setId(UUID.randomUUID());

        PatchApplier.Result result = applier.rollback(p);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsAnyOf("No backup", "Path validation");
    }
}
