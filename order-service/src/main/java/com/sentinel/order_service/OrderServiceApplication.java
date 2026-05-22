package com.sentinel.order_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-service — stub microservice in the Sentinel demo topology.
 *
 * <p>The actual "business logic" is intentionally tiny ({@link OrderController})
 * because the point of this service in the demo is to be a second monitored
 * target — something Prometheus discovers as a scrape job, something
 * Sentinel can investigate when an alert fires. The interesting code is
 * {@link ChaosController}, which simulates the failure modes a real orders
 * service might exhibit (slow DB queries, runaway threads, error-rate
 * spikes) without the complexity of an actual database or downstream call.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
