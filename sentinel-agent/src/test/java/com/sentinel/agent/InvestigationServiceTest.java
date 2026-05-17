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

    @Mock
    private SentinelMetrics metrics;

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
        when(repository.findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), eq("FAILED"), any(java.time.Instant.class))).thenReturn(Optional.empty());
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
        assertThat(saved.getAlertResolvedAt()).isNull();
        assertThat(result.investigation().getId()).isEqualTo(id);
        assertThat(result.wasReused()).isFalse();
    }

    @Test
    void start_reusesActiveEpisodeForSameAlert() {
        // A still-firing alert re-sent by AlertManager must dedupe to the
        // existing active-episode investigation — no new row, no Gemini call.
        Investigation existing = Investigation.create("HighCpuAlert", "critical");
        existing.setId(id);
        // alertResolvedAt is null by default → episode is still active
        when(repository.findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), eq("FAILED"), any(java.time.Instant.class)))
                .thenReturn(Optional.of(existing));

        InvestigationService.StartResult result = service.start("HighCpuAlert", "critical");

        assertThat(result.wasReused()).isTrue();
        assertThat(result.investigation()).isSameAs(existing);
        verify(repository, never()).save(any(Investigation.class));
    }

    @Test
    void start_excludesFailedInvestigationsFromDedupe() {
        // Locks in: a previous FAILED investigation must NOT be reused.
        // The finder receives "FAILED" as the excluded status — verifying this
        // captures the contract; the actual SQL behaviour is tested via Spring
        // Data's derived-query naming.
        when(repository.findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), eq("FAILED"), any(java.time.Instant.class)))
                .thenReturn(Optional.empty());
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> {
            Investigation arg = inv.getArgument(0);
            arg.setId(id);
            return arg;
        });

        InvestigationService.StartResult result = service.start("HighCpuAlert", "critical");

        assertThat(result.wasReused()).isFalse();
        // Verify the call was made with "FAILED" as the excluded status — if
        // someone changes the contract, this assertion catches it.
        verify(repository).findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), eq("FAILED"), any(java.time.Instant.class));
    }

    @Test
    void start_createsFreshInvestigationAfterEpisodeResolved() {
        // After resolveEpisode runs, the previous investigation has alertResolvedAt
        // set, so the active-episode finder returns empty. Next firing webhook
        // must create a fresh investigation.
        when(repository.findFirstByAlertNameAndAlertResolvedAtIsNullAndStatusNotAndStartedAtAfterOrderByStartedAtDesc(
                eq("HighCpuAlert"), eq("FAILED"), any(java.time.Instant.class)))
                .thenReturn(Optional.empty());
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> {
            Investigation arg = inv.getArgument(0);
            arg.setId(id);
            return arg;
        });

        InvestigationService.StartResult result = service.start("HighCpuAlert", "critical");

        assertThat(result.wasReused()).isFalse();
        verify(repository).save(any(Investigation.class));
    }

    // -------------------------------------------------------------------------
    // resolveEpisode()
    // -------------------------------------------------------------------------

    @Test
    void resolveEpisode_marksAllActiveInvestigationsResolved() {
        Investigation a = Investigation.create("HighCpuAlert", "critical");
        a.setId(UUID.randomUUID());
        Investigation b = Investigation.create("HighCpuAlert", "critical");
        b.setId(UUID.randomUUID());
        when(repository.findByAlertNameAndAlertResolvedAtIsNull("HighCpuAlert"))
                .thenReturn(java.util.List.of(a, b));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveEpisode("HighCpuAlert");

        assertThat(a.getAlertResolvedAt()).isNotNull();
        assertThat(b.getAlertResolvedAt()).isNotNull();
        verify(repository, times(2)).save(any(Investigation.class));
    }

    @Test
    void resolveEpisode_noActiveInvestigations_isNoOp() {
        when(repository.findByAlertNameAndAlertResolvedAtIsNull("HighCpuAlert"))
                .thenReturn(java.util.List.of());

        service.resolveEpisode("HighCpuAlert");

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
    void complete_parsesGeminiNestedHashHeadings() {
        // Regression: when the report uses ### for the document title, Gemini
        // emits #### for subsections. The parser must accept the full markdown
        // heading range (h1-h6), not just h1-h3, otherwise the entire report
        // falls back into the symptoms field.
        String report = """
                ### Investigation Report: HighHeapUsage

                #### Symptoms
                Heap exceeded 200 MB.

                #### Evidence
                Logs show 21 allocations.

                #### Root Cause
                Static memoryLeakList accumulates 10MB blocks.

                #### Proposed Fix
                Clear the list and remove the endpoint.
                """;
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, report);

        assertThat(investigation.getSymptoms()).contains("Heap exceeded 200 MB");
        assertThat(investigation.getEvidence()).contains("21 allocations");
        assertThat(investigation.getRootCause()).contains("memoryLeakList");
        assertThat(investigation.getProposedFix()).contains("Clear the list");
    }

    @Test
    void complete_parsesColonInsideBoldHeader() {
        // Regression: Gemini sometimes emits "**Section:**" (colon inside the
        // bold markers) instead of "**Section**:". The trailing ** must be
        // consumed as part of the header, not leaked into the section body.
        String report = """
                **Symptoms:**
                Heap exceeded 200 MB.

                **Root Cause:**
                Static memoryLeakList accumulates blocks.
                """;
        when(repository.findById(id)).thenReturn(Optional.of(investigation));
        when(repository.save(any(Investigation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(id, report);

        assertThat(investigation.getSymptoms()).contains("Heap exceeded 200 MB");
        assertThat(investigation.getSymptoms()).doesNotStartWith("**");
        assertThat(investigation.getRootCause()).contains("memoryLeakList");
        assertThat(investigation.getRootCause()).doesNotStartWith("**");
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
