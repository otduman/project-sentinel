package com.sentinel.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Proxies Prometheus and AlertManager API calls for the React dashboard.
 * Avoids CORS issues — Prometheus/AlertManager don't expose CORS headers.
 * URLs are profile-aware: localhost in dev, Docker service names in docker profile.
 */
@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private final RestClient restClient = RestClient.create();

    @Value("${sentinel.proxy.prometheus-url}")
    private String prometheusUrl;

    @Value("${sentinel.proxy.alertmanager-url}")
    private String alertmanagerUrl;

    @GetMapping("/heap")
    public ResponseEntity<String> heap() {
        try {
            String body = restClient.get()
                .uri(prometheusUrl + "/api/v1/query?query=jvm_memory_used_bytes%7Barea%3D%22heap%22%7D")
                .retrieve()
                .body(String.class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (Exception e) {
            return ResponseEntity.ok("{\"data\":{\"result\":[]}}");
        }
    }

    @GetMapping("/alerts")
    public ResponseEntity<String> alerts() {
        try {
            String body = restClient.get()
                .uri(alertmanagerUrl + "/api/v2/alerts?active=true&silenced=false&inhibited=false")
                .retrieve()
                .body(String.class);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (Exception e) {
            return ResponseEntity.ok("[]");
        }
    }
}
