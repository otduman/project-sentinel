package com.sentinel.agent;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private final RestClient restClient;

    public ProxyController() {
        this.restClient = RestClient.create();
    }

    @GetMapping("/alerts")
    public ResponseEntity<String> alerts() {
        try {
            String body = restClient.get()
                .uri("http://localhost:9093/api/v2/alerts")
                .retrieve()
                .body(String.class);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        } catch (Exception e) {
            return ResponseEntity.ok("[]");
        }
    }

    @GetMapping("/heap")
    public ResponseEntity<String> heap() {
        try {
            String body = restClient.get()
                .uri("http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Barea%3D%22heap%22%7D")
                .retrieve()
                .body(String.class);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        } catch (Exception e) {
            return ResponseEntity.ok("{\"data\":{\"result\":[]}}");
        }
    }
}
