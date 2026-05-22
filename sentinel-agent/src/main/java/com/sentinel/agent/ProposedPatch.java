package com.sentinel.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A structured source-code patch proposed by the SRE agent during an
 * investigation. Distinct from the markdown {@code proposedFix} blob stored on
 * {@link Investigation} — that field captures whatever prose Gemini emitted,
 * while this entity is the machine-readable contract used by the approval gate
 * and (later phases) the patch-applier.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   proposeFix tool call    -> PENDING_REVIEW
 *   user clicks Approve     -> APPROVED      (Phase 2: triggers PatchApplier)
 *   user clicks Reject      -> REJECTED
 *   (Phase 3) verifier OK   -> APPLIED
 *   (Phase 3) verifier fail -> ROLLED_BACK
 * </pre>
 *
 * <h3>Why oldContent is stored</h3>
 * For the diff viewer in the dashboard, and as a safety net in Phase 2 — the
 * applier verifies the file's current content still matches {@code oldContent}
 * before overwriting, otherwise the patch is rejected (file changed out from
 * under us).
 */
@Entity
@Table(name = "proposed_patches")
public class ProposedPatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID investigationId;

    @Column(nullable = false, length = 512)
    private String filePath;

    /**
     * Which patchable service this patch targets — populated from the
     * {@code PatchPathValidator.Resolved.serviceName} returned at proposeFix
     * time. Nullable for backwards compatibility with pre-multi-service rows
     * (which were all lab-rat by definition).
     */
    @Column(length = 64)
    private String serviceName;

    @Column(columnDefinition = "TEXT")
    private String oldContent;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String newContent;

    /** PENDING_REVIEW, APPROVED, REJECTED, APPLIED, ROLLED_BACK */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant decidedAt;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    public ProposedPatch() { }

    private ProposedPatch(UUID investigationId, String filePath, String serviceName,
                          String oldContent, String newContent, String rationale) {
        this.investigationId = investigationId;
        this.filePath = filePath;
        this.serviceName = serviceName;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.rationale = rationale;
        this.status = "PENDING_REVIEW";
        this.createdAt = Instant.now();
    }

    /**
     * Factory — the only legal way to construct a fresh PENDING_REVIEW patch.
     * {@code serviceName} may be null on legacy paths (lab-rat is assumed).
     */
    public static ProposedPatch create(UUID investigationId, String filePath, String serviceName,
                                       String oldContent, String newContent, String rationale) {
        return new ProposedPatch(investigationId, filePath, serviceName, oldContent, newContent, rationale);
    }

    /**
     * Legacy 5-arg factory — kept so existing tests don't break.
     * Equivalent to passing {@code serviceName = null}; the applier will
     * still re-resolve via {@code PatchPathValidator} at apply time.
     */
    public static ProposedPatch create(UUID investigationId, String filePath,
                                       String oldContent, String newContent, String rationale) {
        return new ProposedPatch(investigationId, filePath, null, oldContent, newContent, rationale);
    }

    public UUID getId() { return id; }
    public UUID getInvestigationId() { return investigationId; }
    public String getFilePath() { return filePath; }
    public String getServiceName() { return serviceName; }
    public String getOldContent() { return oldContent; }
    public String getNewContent() { return newContent; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getRationale() { return rationale; }

    public void setId(UUID id) { this.id = id; }
    public void setStatus(String status) { this.status = status; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
