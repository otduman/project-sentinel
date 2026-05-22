package com.sentinel.payment_service;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Pretend payment-status endpoint. Returns canned data — exists so the
 * service has a "business" route Prometheus can see RPS/latency on,
 * distinct from /actuator/* traffic. No real PSP integration.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    @GetMapping
    public List<Map<String, Object>> listPayments() {
        return List.of(
                Map.of("id", "pay-001", "method", "CARD",   "status", "CAPTURED", "amountCents", 4999),
                Map.of("id", "pay-002", "method", "WALLET", "status", "AUTHORIZED", "amountCents", 12300),
                Map.of("id", "pay-003", "method", "CARD",   "status", "PENDING",  "amountCents", 800)
        );
    }
}
