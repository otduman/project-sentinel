package com.sentinel.agent;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "runbooks")
public class Runbook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique alert name this runbook covers — used as the lookup key.
    @Column(nullable = false, unique = true)
    private String alertName;

    @Column(nullable = false)
    private String title;

    private String severity;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Steps stored as a single newline-delimited string for simplicity.
    @Column(columnDefinition = "TEXT")
    private String steps;

    @Column(nullable = false)
    private Instant lastUpdated;

    public Runbook() {}

    // Getters and setters for all fields
    public Long getId() { return id; }
    public String getAlertName() { return alertName; }
    public void setAlertName(String alertName) { this.alertName = alertName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSteps() { return steps; }
    public void setSteps(String steps) { this.steps = steps; }
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
}
