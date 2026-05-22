package com.sentinel.order_service;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Tiny "business endpoint" so the service has something to scrape beyond
 * pure actuator data. Returns canned data; no real persistence. Exists so a
 * load-generator could hammer it during a demo and Prometheus would see
 * RPS / latency activity on this service distinct from lab-rat's.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping
    public List<Map<String, Object>> listOrders() {
        return List.of(
                Map.of("id", "ord-001", "status", "PAID",     "totalCents", 4999),
                Map.of("id", "ord-002", "status", "SHIPPED",  "totalCents", 12300),
                Map.of("id", "ord-003", "status", "PENDING",  "totalCents", 800)
        );
    }
}
