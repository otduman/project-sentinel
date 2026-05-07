package com.sentinel.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link ProposedPatch}. One investigation can carry at
 * most one patch in {@code PENDING_REVIEW} status at a time — enforced at the
 * service layer, not via a unique constraint, because rejected patches stay
 * in the table for audit.
 */
public interface ProposedPatchRepository extends JpaRepository<ProposedPatch, UUID> {

    /**
     * Returns the most recent patch for an investigation, regardless of status.
     * The dashboard uses this to display the diff viewer + decision UI.
     */
    Optional<ProposedPatch> findFirstByInvestigationIdOrderByCreatedAtDesc(UUID investigationId);

    /**
     * Returns all patches for an investigation in chronological order — useful
     * for showing a history of agent proposals if the agent ever proposes
     * multiple fixes within a single investigation.
     */
    List<ProposedPatch> findByInvestigationIdOrderByCreatedAtAsc(UUID investigationId);
}
