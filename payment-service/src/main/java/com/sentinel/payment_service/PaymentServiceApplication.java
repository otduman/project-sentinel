package com.sentinel.payment_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-service — stub microservice in the Sentinel demo topology.
 *
 * <p>Companion to {@code order-service}. Where the orders service
 * simulates DB-flavoured failure (slow queries, stuck threads), payments
 * simulates outbound-dependency failure: timeouts on a downstream PSP,
 * memory pressure during request retries, and elevated error rates from
 * a flaky third-party gateway. {@link ChaosController} arms each.
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
