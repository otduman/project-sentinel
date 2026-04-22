package com.sentinel.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted record of one AI SRE investigation triggered by a Prometheus alert.
 * Status lifecycle: PENDING -> INVESTIGATING -> COMPLETE | FAILED
 */
@Entity
@Table(name = "investigations")
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String alertName;

    @Column(nullable = false)
    private String severity;

    /**
     * Lifecycle status: PENDING, INVESTIGATING, COMPLETE, FAILED
     */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant startedAt;

    @Column
    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String symptoms;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String rootCause;

    @Column(columnDefinition = "TEXT")
    private String proposedFix;

    // Required by JPA
    public Investigation() {
    }

    private Investigation(String alertName, String severity, String status, Instant startedAt) {
        this.alertName = alertName;
        this.severity = severity;
        this.status = status;
        this.startedAt = startedAt;
    }

    /**
     * Factory method — creates a new PENDING investigation. JPA generates the id on persist.
     * Transitions to INVESTIGATING once the executor picks it up (see InvestigationService.start).
     */
    public static Investigation create(String alertName, String severity) {
        return new Investigation(alertName, severity, "PENDING", Instant.now());
    }

    // --- Getters ---

    public UUID getId() {
        return id;
    }

    public String getAlertName() {
        return alertName;
    }

    public String getSeverity() {
        return severity;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getSymptoms() {
        return symptoms;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getRootCause() {
        return rootCause;
    }

    public String getProposedFix() {
        return proposedFix;
    }

    // --- Setters ---

    public void setId(UUID id) {
        this.id = id;
    }

    public void setAlertName(String alertName) {
        this.alertName = alertName;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public void setSymptoms(String symptoms) {
        this.symptoms = symptoms;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public void setProposedFix(String proposedFix) {
        this.proposedFix = proposedFix;
    }
}
