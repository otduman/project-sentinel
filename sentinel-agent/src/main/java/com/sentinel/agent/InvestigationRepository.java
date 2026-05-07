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
     * Returns the most recent investigation for the given alert name whose
     * {@code alertResolvedAt} is still {@code null} (i.e. the alert episode is
     * still considered firing), whose status is NOT {@code FAILED}, AND that
     * started within the cutoff. Used by {@code InvestigationService.start}
     * as the episode-based dedupe primary gate.
     *
     * <p>The status exclusion matters: a FAILED investigation means the AI
     * call itself errored (timeout / 5xx / etc.) before producing a diagnosis.
     * Treating it as "active" would dedupe forever and mask the real alert.
     * Excluding it here lets the next firing webhook spawn a fresh attempt.
     *
     * <p>The {@code startedAt > since} clause is the 3-hour backstop: if a
     * {@code resolved} webhook ever gets lost, an active episode would
     * otherwise dedupe forever. Bounding to a generous window self-heals
     * those edge cases without requiring manual intervention.
     */
    Optional<Investigation> findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
            String alertName, String excludedStatus, Instant since);

    /**
     * All currently-firing investigations for an alertName (those with
     * {@code alertResolvedAt} still null). Used by
     * {@code InvestigationService.resolveEpisode} to mark them resolved when
     * a {@code status=resolved} webhook arrives.
     */
    List<Investigation> findByAlertNameAndAlertResolvedAtIsNull(String alertName);
}
