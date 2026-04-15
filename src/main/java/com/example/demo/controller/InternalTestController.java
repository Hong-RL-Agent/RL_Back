package com.example.demo.controller;

import com.example.demo.model.TestEvent;
import com.example.demo.service.TestStreamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/test")
public class InternalTestController {

    private final TestStreamService testStreamService;

    public InternalTestController(TestStreamService testStreamService) {
        this.testStreamService = testStreamService;
    }

    @PostMapping("/{sessionId}/event")
    public ResponseEntity<Void> receiveEvent(
            @PathVariable String sessionId,
            @RequestBody TestEvent event
    ) {
        System.out.println("[BACKEND] event 수신 - sessionId=" + sessionId
                + ", type=" + event.getType()
                + ", label=" + event.getLabel()
                + ", message=" + event.getMessage());

        testStreamService.pushEvent(sessionId, event);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<Void> complete(@PathVariable String sessionId) {
        System.out.println("[BACKEND] complete 수신 - sessionId=" + sessionId);
        testStreamService.complete(sessionId);
        return ResponseEntity.ok().build();
    }
}