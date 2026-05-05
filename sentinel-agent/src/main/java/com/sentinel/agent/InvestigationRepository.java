package com.sentinel.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Returns the most recent investigation for the given alert name started after
     * the cutoff, regardless of status. Used by {@code InvestigationService.start}
     * to dedupe webhook bursts: AlertManager re-sends the same firing alert every
     * {@code repeat_interval} (5 min by default) while it remains active, and
     * without dedupe each re-send would create a fresh investigation row.
     */
    Optional<Investigation> findFirstByAlertNameAndStartedAtAfterOrderByStartedAtDesc(
            String alertName, Instant since);
}
