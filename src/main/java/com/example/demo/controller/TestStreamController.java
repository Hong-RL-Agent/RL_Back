package com.example.demo.controller;

import com.example.demo.service.TestStreamService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "http://localhost:5173")
public class TestStreamController {

    private final TestStreamService testStreamService;

    public TestStreamController(TestStreamService testStreamService) {
        this.testStreamService = testStreamService;
    }

    @GetMapping("/{sessionId}/stream")
    public SseEmitter stream(@PathVariable String sessionId) {
        return testStreamService.connect(sessionId);
    }
}