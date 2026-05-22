package com.sentinel.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks down the contract between the LLM-supplied filePath and the patch
 * applier — any change here means a change to the safety surface, so changes
 * should be deliberate.
 */
class PatchPathValidatorTest {

    @Test
    void plainClassName_isNormalisedToContainerAbsolute() {
        String result = PatchPathValidator.normalise("ChaosController.java");
        assertThat(result).isEqualTo("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
    }

    @Test
    void repoRelativePath_isNormalised() {
        String result = PatchPathValidator.normalise("lab-rat/src/main/java/com/sentinel/lab_rat/ChaosController.java");
        assertThat(result).isEqualTo("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
    }

    @Test
    void containerAbsolutePath_isPreserved() {
        String result = PatchPathValidator.normalise("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
        assertThat(result).isEqualTo("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
    }

    @Test
    void packageOnlyPath_isNormalised() {
        String result = PatchPathValidator.normalise("com/sentinel/lab_rat/ChaosController.java");
        assertThat(result).isEqualTo("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
    }

    @Test
    void backslashes_areNormalisedThenAccepted() {
        String result = PatchPathValidator.normalise("com\\sentinel\\lab_rat\\ChaosController.java");
        assertThat(result).isEqualTo("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
    }

    // ------------------------------------------------------------------
    // Reject cases — anything that touches files outside lab-rat
    // ------------------------------------------------------------------

    @Test
    void pathTraversal_isRejected() {
        assertThatThrownBy(() -> PatchPathValidator.normalise("../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void pathTraversalEmbeddedInValidLookingPath_isRejected() {
        assertThatThrownBy(() -> PatchPathValidator.normalise(
                "/lab-rat-src/main/java/com/sentinel/lab_rat/../../../../../sentinel-agent/src/main/Foo.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    void sentinelAgentSource_isRejected() {
        assertThatThrownBy(() -> PatchPathValidator.normalise(
                "sentinel-agent/src/main/java/com/sentinel/agent/AgentController.java"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonJavaFile_isRejected() {
        assertThatThrownBy(() -> PatchPathValidator.normalise("ChaosController.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lowercaseClassName_isRejected() {
        // Java classes start with uppercase by convention — anything else suggests
        // the LLM is confusing this with a package or a script.
        assertThatThrownBy(() -> PatchPathValidator.normalise("chaosController.java"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyAndNullPaths_areRejected() {
        assertThatThrownBy(() -> PatchPathValidator.normalise(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatchPathValidator.normalise("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatchPathValidator.normalise(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValidMatchesNormaliseBehaviour() {
        assertThat(PatchPathValidator.isValid("ChaosController.java")).isTrue();
        assertThat(PatchPathValidator.isValid("../etc/passwd")).isFalse();
    }

    // ------------------------------------------------------------------
    // Multi-service routing — locks in the per-service dispatch behaviour
    // so adding/removing services in SERVICES doesn't silently mis-route.
    // ------------------------------------------------------------------

    @Test
    void plainClassName_defaultsToLabRatForBackwardsCompat() {
        PatchPathValidator.Resolved r = PatchPathValidator.resolve("ChaosController.java");
        assertThat(r.serviceName()).isEqualTo("lab-rat");
        assertThat(r.canonicalPath()).isEqualTo("/lab-rat-src/main/java/com/sentinel/lab_rat/ChaosController.java");
    }

    @Test
    void orderServicePackagePath_routesToOrderService() {
        PatchPathValidator.Resolved r = PatchPathValidator.resolve("com/sentinel/order_service/OrderController.java");
        assertThat(r.serviceName()).isEqualTo("order-service");
        assertThat(r.canonicalPath()).isEqualTo("/order-service-src/main/java/com/sentinel/order_service/OrderController.java");
    }

    @Test
    void paymentServicePackagePath_routesToPaymentService() {
        PatchPathValidator.Resolved r = PatchPathValidator.resolve("com/sentinel/payment_service/PaymentController.java");
        assertThat(r.serviceName()).isEqualTo("payment-service");
        assertThat(r.canonicalPath()).isEqualTo("/payment-service-src/main/java/com/sentinel/payment_service/PaymentController.java");
    }

    @Test
    void orderServiceContainerAbsolutePath_isPreserved() {
        PatchPathValidator.Resolved r = PatchPathValidator.resolve(
                "/order-service-src/main/java/com/sentinel/order_service/OrderController.java");
        assertThat(r.serviceName()).isEqualTo("order-service");
        assertThat(r.canonicalPath()).isEqualTo("/order-service-src/main/java/com/sentinel/order_service/OrderController.java");
    }

    @Test
    void paymentServiceRepoRelativePath_routesToPaymentService() {
        PatchPathValidator.Resolved r = PatchPathValidator.resolve(
                "payment-service/src/main/java/com/sentinel/payment_service/PaymentController.java");
        assertThat(r.serviceName()).isEqualTo("payment-service");
    }
}
