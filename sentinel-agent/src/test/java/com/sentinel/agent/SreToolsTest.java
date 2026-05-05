package com.sentinel.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SreTools} input validation. Production code
 * is the only thing under test — repositories are mocked, and we never
 * touch the real {@code ProfilerAttacher} (a non-existent JAR path is
 * configured so the static call fails predictably AFTER validation passes).
 */
@ExtendWith(MockitoExtension.class)
class SreToolsTest {

    @Mock
    private RunbookRepository runbookRepository;

    @Mock
    private InvestigationRepository investigationRepository;

    private SreTools tools;

    @BeforeEach
    void setUp() {
        tools = new SreTools(runbookRepository, investigationRepository);
        // @Value-injected fields — set via reflection since we're not bringing up a Spring context.
        ReflectionTestUtils.setField(tools, "logPath", "/tmp/does-not-exist.log");
        ReflectionTestUtils.setField(tools, "agentJarPath", "/tmp/does-not-exist.jar");
    }

    // -------------------------------------------------------------------------
    // PID validation
    // -------------------------------------------------------------------------

    @Test
    void runDynamicProfiler_rejectsNonNumericPid() {
        String result = tools.runDynamicProfiler("notanumber", "com.sentinel.lab_rat");
        assertThat(result).isEqualTo("Error: invalid PID format.");
    }

    @Test
    void runDynamicProfiler_rejectsEmptyPid() {
        String result = tools.runDynamicProfiler("", "com.sentinel.lab_rat");
        assertThat(result).isEqualTo("Error: invalid PID format.");
    }

    @Test
    void runDynamicProfiler_rejectsPidLongerThanSevenDigits() {
        // 8 digits — fails the \d{1,7} bound.
        String result = tools.runDynamicProfiler("12345678", "com.sentinel.lab_rat");
        assertThat(result).isEqualTo("Error: invalid PID format.");
    }

    @Test
    void runDynamicProfiler_rejectsPidWithEmbeddedSpaces() {
        String result = tools.runDynamicProfiler("12 34", "com.sentinel.lab_rat");
        assertThat(result).isEqualTo("Error: invalid PID format.");
    }

    // -------------------------------------------------------------------------
    // Package-name validation
    // -------------------------------------------------------------------------

    @Test
    void runDynamicProfiler_rejectsPackageWithShellMetacharacters() {
        String result = tools.runDynamicProfiler("12345", "com.foo;rm -rf /");
        assertThat(result).isEqualTo("Error: invalid package name.");
    }

    @Test
    void runDynamicProfiler_rejectsPackageWithSlash() {
        String result = tools.runDynamicProfiler("12345", "com/foo");
        assertThat(result).isEqualTo("Error: invalid package name.");
    }

    @Test
    void runDynamicProfiler_rejectsPackageWithSpace() {
        String result = tools.runDynamicProfiler("12345", "com.foo bar");
        assertThat(result).isEqualTo("Error: invalid package name.");
    }

    // -------------------------------------------------------------------------
    // Happy-path validation — gets past the input checks.
    // -------------------------------------------------------------------------
    //
    // ProfilerAttacher.attach() is a static call we cannot mock without
    // mockito-inline. Instead we feed it valid args + a bogus JAR path so
    // it gets PAST validation and into the static call, which then fails
    // with a different (non-validation) error. We assert the message is
    // NOT one of the validation errors — proving the inputs were accepted.
    @Test
    void runDynamicProfiler_validInputsPassValidation() {
        String result = tools.runDynamicProfiler("12345", "com.sentinel.lab_rat");

        assertThat(result).isNotEqualTo("Error: invalid PID format.");
        assertThat(result).isNotEqualTo("Error: invalid package name.");
        // It either propagates the attach failure ("Failed to run profiler: ...")
        // or returns the empty-report sentinel — both prove validation accepted the inputs.
        assertThat(result).satisfiesAnyOf(
                msg -> assertThat(msg).startsWith("Failed to run profiler:"),
                msg -> assertThat(msg).contains("Profiler report was empty"),
                msg -> assertThat(msg).isNotBlank()
        );
    }
}
