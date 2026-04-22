package com.sentinel.agent;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * REST + SSE API for the React dashboard to retrieve investigation history
 * and subscribe to real-time status updates.
 */
@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    private final InvestigationService investigationService;

    public InvestigationController(InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    /**
     * Returns all investigations started within the last 30 days, newest first.
     * GET /api/investigations
     */
    @GetMapping
    public List<Investigation> listRecent() {
        return investigationService.getLast30Days();
    }

    /**
     * Returns a single investigation by id.
     * GET /api/investigations/{id}
     * 404 if no investigation with that id exists.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Investigation> getById(@PathVariable UUID id) {
        return investigationService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Opens a Server-Sent Events stream.  The dashboard subscribes here to
     * receive real-time notifications whenever an investigation is started,
     * completed, or fails — without polling.
     * GET /api/investigations/stream
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return investigationService.subscribe();
    }
}
