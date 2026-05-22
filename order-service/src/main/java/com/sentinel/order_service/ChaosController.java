package com.sentinel.order_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * order-service flavour of the chaos sandbox. Same idea as lab-rat's
 * ChaosController but scoped to failure modes a real orders service is
 * likely to hit: slow DB queries, threads that get pinned and never come
 * back, and elevated error rates from a misbehaving dependency.
 *
 * <p>Each endpoint is intentionally cheap — the goal is to make a specific
 * metric (latency, error rate, thread count) move in a predictable way so
 * Sentinel has something to investigate, not to genuinely break the JVM.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    /** Remaining requests that will return 5xx. Decremented per-hit until zero. */
    private final AtomicInteger pendingErrorResponses = new AtomicInteger(0);

    /**
     * Simulates a slow query. Sleeps the request thread for the given duration
     * so /actuator/prometheus shows elevated p99 latency on http_server_requests
     * without us needing a real database to lock up.
     */
    @GetMapping("/slow-query")
    public String slowQuery(@RequestParam(defaultValue = "5000") long milliseconds) {
        log.warn("Order chaos: simulating slow DB query for {}ms", milliseconds);
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Slow query interrupted.";
        }
        return "Slow query completed in " + milliseconds + "ms.";
    }

    /**
     * Pins a fresh thread doing nothing for the specified duration. Used to
     * make jvm_threads_live / jvm_threads_states metrics move so Prometheus
     * can fire a "thread leak" style alert.
     */
    @GetMapping("/stuck-thread")
    public String stuckThread(@RequestParam(defaultValue = "30") int seconds) {
        log.warn("Order chaos: spawning a thread that will park for {}s", seconds);
        Thread parked = new Thread(() -> {
            LockSupport.parkNanos(java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds));
            log.info("Order chaos: parked thread released");
        }, "order-chaos-park-" + System.currentTimeMillis());
        parked.setDaemon(false);
        parked.start();
        return "Parked thread '" + parked.getName() + "' for " + seconds + "s.";
    }

    /**
     * Arms the service to return HTTP 500 on the next N requests to
     * {@code /chaos/check}. Used to spike http_server_requests_seconds_count
     * with status=500, which the alerting rules can pick up.
     */
    @GetMapping("/error-rate/arm")
    public String armErrorRate(@RequestParam(defaultValue = "20") int count) {
        pendingErrorResponses.set(count);
        log.warn("Order chaos: armed {} failing /chaos/check responses", count);
        return "Armed " + count + " failing responses.";
    }

    /**
     * Probe endpoint that returns 500 while {@link #pendingErrorResponses}
     * is positive, then drains back to 200. The 5xx → 200 transition is
     * also what Prometheus uses to flip alert state from firing to resolved.
     */
    @GetMapping("/check")
    public org.springframework.http.ResponseEntity<String> check() {
        int remaining = pendingErrorResponses.getAndUpdate(n -> Math.max(0, n - 1));
        if (remaining > 0) {
            return org.springframework.http.ResponseEntity
                    .status(500)
                    .body("order-service induced failure (remaining=" + (remaining - 1) + ")");
        }
        return org.springframework.http.ResponseEntity.ok("ok");
    }
}
