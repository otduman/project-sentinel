package com.sentinel.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for {@link InvestigationService}. No Spring context — Mockito only.
 */
@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

    @Mock
    private InvestigationRepository repository;

    @InjectMocks
    private InvestigationService service;

    private UUID id;
    private Investigation investigation;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        investigation = Investigation.create("HighCpuAlert", "critical");
        investigation.setId(id);
    }

    // -------------------------------------------------------------------------
    // start()
    // -------------------------------------------------------------------------

    @Test
    void start_persistsPendingInvestigation() {
        when(repository.findFirstByAlertNameAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), any(java.time.Instant.class))).thenReturn(Optional.empty());
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> {
            Investigation arg = inv.getArgument(0);
            arg.setId(id);
            return arg;
        });

        InvestigationService.StartResult result = service.start("HighCpuAlert", "critical");

        ArgumentCaptor<Investigation> captor = ArgumentCaptor.forClass(Investigation.class);
        verify(repository).save(captor.capture());

        Investigation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getAlertName()).isEqualTo("HighCpuAlert");
        assertThat(saved.getSeverity()).isEqualTo("critical");
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(result.investigation().getId()).isEqualTo(id);
        assertThat(result.wasReused()).isFalse();
    }

    @Test
    void start_reusesRecentInvestigationForSameAlert() {
        // A still-firing alert re-sent by AlertManager at its repeat_interval must
        // not spawn a second investigation row — the existing one should be reused.
        Investigation existing = Investigation.create("HighCpuAlert", "critical");
        existing.setId(id);
        when(repository.findFirstByAlertNameAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), any(java.time.Instant.class)))
                .thenReturn(Optional.of(existing));

        InvestigationService.StartResult result = service.start("HighCpuAlert", "critical");

        assertThat(result.wasReused()).isTrue();
        assertThat(result.investigation()).isSameAs(existing);
        verify(repository, never()).save(any(Investigation.class));
    }

    // -------------------------------------------------------------------------
    // complete() — exercises parseReport() indirectly
    // -------------------------------------------------------------------------

    @Test
    void complete_parsesBoldStarSections() {
        String report = """
                **Symptoms**: High CPU usage observed for 30 minutes.
                **Evidence**: Top method takes 92% of samples.
                **Root Cause**: Recursive Fibonacci with no memoisation.
                **Proposed Fix**: Add memoisation cache.
                """;
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, report);

        assertThat(investigation.getStatus()).isEqualTo("COMPLETE");
        assertThat(investigation.getCompletedAt()).isNotNull();
        assertThat(investigation.getSymptoms()).contains("High CPU usage");
        assertThat(investigation.getEvidence()).contains("92% of samples");
        assertThat(investigation.getRootCause()).contains("Recursive Fibonacci");
        assertThat(investigation.getProposedFix()).contains("memoisation cache");
    }

    @Test
    void complete_parsesMarkdownHeadingVariants() {
        String report = """
                ## Symptoms
                Service is slow.

                # Root Cause
                Database lock contention.
                """;
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, report);

        assertThat(investigation.getSymptoms()).contains("Service is slow");
        assertThat(investigation.getRootCause()).contains("Database lock contention");
        // Sections that weren't supplied stay null.
        assertThat(investigation.getEvidence()).isNull();
        assertThat(investigation.getProposedFix()).isNull();
    }

    @Test
    void complete_unrecognisedReportLandsInSymptoms() {
        String report = "Just some unstructured rambling from the LLM with no recognised headers.";
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, report);

        assertThat(investigation.getSymptoms()).isEqualTo(report.trim());
        assertThat(investigation.getEvidence()).isNull();
        assertThat(investigation.getRootCause()).isNull();
        assertThat(investigation.getProposedFix()).isNull();
        assertThat(investigation.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void complete_emptyReportLeavesSectionsNull() {
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, "");

        assertThat(investigation.getStatus()).isEqualTo("COMPLETE");
        assertThat(investigation.getSymptoms()).isNull();
        assertThat(investigation.getEvidence()).isNull();
        assertThat(investigation.getRootCause()).isNull();
        assertThat(investigation.getProposedFix()).isNull();
    }

    @Test
    void complete_nullReportLeavesSectionsNull() {
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, null);

        assertThat(investigation.getStatus()).isEqualTo("COMPLETE");
        assertThat(investigation.getSymptoms()).isNull();
        assertThat(investigation.getEvidence()).isNull();
        assertThat(investigation.getRootCause()).isNull();
        assertThat(investigation.getProposedFix()).isNull();
    }

    @Test
    void complete_unknownIdIsNoOp() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.complete(id, "**Symptoms**: anything");

        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // fail()
    // -------------------------------------------------------------------------

    @Test
    void fail_setsStatusFailedAndStoresErrorInSymptoms() {
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.fail(id, "boom: connection refused");

        assertThat(investigation.getStatus()).isEqualTo("FAILED");
        assertThat(investigation.getCompletedAt()).isNotNull();
        assertThat(investigation.getSymptoms()).contains("Investigation failed:");
        assertThat(investigation.getSymptoms()).contains("boom: connection refused");
    }

    @Test
    void fail_unknownIdIsNoOp() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.fail(id, "anything");

        verify(repository, never()).save(any());
    }
}
