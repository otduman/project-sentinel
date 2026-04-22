package com.sentinel.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JPA repository for {@link Investigation} entities.
 */
public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    /**
     * Returns all investigations started after the given instant, newest first.
     * Used to fetch the rolling 30-day history for the dashboard.
     */
    List<Investigation> findByStartedAtAfterOrderByStartedAtDesc(Instant since);

    /**
     * Bulk-deletes investigations older than the given cutoff.
     * Called nightly by the scheduled cleanup job in InvestigationService.
     */
    void deleteByStartedAtBefore(Instant cutoff);

    /**
     * Returns up to the last 5 completed investigations for a given alert name within a time window.
     * Used by the lookupPastInvestigations tool for recurrence detection.
     */
    List<Investigation> findByAlertNameAndStatusAndStartedAtAfterOrderByStartedAtDesc(
            String alertName, String status, java.time.Instant since);
}
