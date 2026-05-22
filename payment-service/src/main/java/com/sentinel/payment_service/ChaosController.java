package com.sentinel.payment_service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * payment-service flavour of chaos. Simulates the failure modes a payments
 * service typically suffers in production: timeouts when talking to a
 * downstream payment gateway, memory pressure under retry storms, and
 * elevated downstream error rates.
 *
 * <p>Endpoints intentionally don't make real outbound calls — they just
 * move the relevant Prometheus metric (latency / error count / heap size)
 * so Sentinel has a real, gradeable signal to investigate.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    /** Counts probe responses queued to fail. Drains as /chaos/check is hit. */
    private final AtomicInteger pendingDownstreamErrors = new AtomicInteger(0);

    /** Retained byte buffers — simulates retry-storm memory pressure. */
    private final List<byte[]> retainedBuffers = new ArrayList<>();

    /**
     * Simulates a downstream PSP timing out. Sleeps the request thread the
     * way a blocking outbound call would, so http_server_requests p99 spikes
     * and the alerting rule can pick up "elevated payment latency".
     */
    @GetMapping("/downstream-timeout")
    public String downstreamTimeout(@RequestParam(defaultValue = "8000") long milliseconds) {
        log.warn("Payment chaos: simulating downstream PSP timeout for {}ms", milliseconds);
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Downstream timeout simulation interrupted.";
        }
        return "Downstream call returned after " + milliseconds + "ms.";
    }

    /**
     * Arms the service to fail the next N {@code /chaos/check} hits with a
     * 503 — mimicking a flaky downstream that's intermittently up. Use this
     * to drive a "payment-error-rate" alert.
     */
    @GetMapping("/error-rate/arm")
    public String armErrorRate(@RequestParam(defaultValue = "20") int count) {
        pendingDownstreamErrors.set(count);
        log.warn("Payment chaos: armed {} failing /chaos/check responses", count);
        return "Armed " + count + " failing responses.";
    }

    @GetMapping("/check")
    public org.springframework.http.ResponseEntity<String> check() {
        int remaining = pendingDownstreamErrors.getAndUpdate(n -> Math.max(0, n - 1));
        if (remaining > 0) {
            return org.springframework.http.ResponseEntity
                    .status(503)
                    .body("payment-service downstream failure (remaining=" + (remaining - 1) + ")");
        }
        return org.springframework.http.ResponseEntity.ok("ok");
    }

    /**
     * Allocates {@code megabytes}MB and retains it indefinitely. Different
     * shape than lab-rat's leak — here we simulate retry-buffer accumulation
     * under load, not a static-list bug. The runbook for HighHeapUsage on
     * payment-service points investigators here.
     */
    @GetMapping("/memory-pressure")
    public String memoryPressure(@RequestParam(defaultValue = "20") int megabytes) {
        log.warn("Payment chaos: retaining {}MB of buffers (simulated retry storm)", megabytes);
        retainedBuffers.add(new byte[megabytes * 1024 * 1024]);
        return "Retained " + megabytes + "MB. Total retained: " + (retainedBuffers.size() * megabytes) + "MB.";
    }

    /** Releases all retained buffers — counterpart to {@link #memoryPressure}. */
    @GetMapping("/memory-pressure/clear")
    public String clearMemoryPressure() {
        int n = retainedBuffers.size();
        retainedBuffers.clear();
        log.info("Payment chaos: cleared {} retained buffers", n);
        return "Cleared " + n + " buffers.";
    }
}
