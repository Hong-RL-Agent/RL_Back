package com.jaws.jawsback.controller;

import com.jaws.jawsback.dto.TestDto.TestIssuesResponse;
import com.jaws.jawsback.dto.TestDto.TestHistoryResponse;
import com.jaws.jawsback.dto.TestDto.TestLogsResponse;
import com.jaws.jawsback.dto.TestDto.TestProgressResponse;
import com.jaws.jawsback.dto.TestDto.TestReportResponse;
import com.jaws.jawsback.dto.TestDto.TestStartRequest;
import com.jaws.jawsback.dto.TestDto.TestStartResponse;
import com.jaws.jawsback.service.TestSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final TestSessionService testSessionService;

    @PostMapping("/start")
    public ResponseEntity<TestStartResponse> start(@Valid @RequestBody TestStartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(testSessionService.start(request));
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId) {
        return testSessionService.stream(sessionId);
    }

    @GetMapping("/{sessionId}/progress")
    public ResponseEntity<TestProgressResponse> progress(@PathVariable String sessionId) {
        return ResponseEntity.ok(testSessionService.progress(sessionId));
    }

    @GetMapping("/{sessionId}/logs")
    public ResponseEntity<TestLogsResponse> logs(@PathVariable String sessionId) {
        return ResponseEntity.ok(testSessionService.logs(sessionId));
    }

    @GetMapping("/{sessionId}/issues")
    public ResponseEntity<TestIssuesResponse> issues(@PathVariable String sessionId) {
        return ResponseEntity.ok(testSessionService.issues(sessionId));
    }

    @GetMapping("/history")
    public ResponseEntity<TestHistoryResponse> history() {
        return ResponseEntity.ok(testSessionService.history());
    }

    @PostMapping("/{sessionId}/report")
    public ResponseEntity<TestReportResponse> report(@PathVariable String sessionId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(testSessionService.report(sessionId));
    }
}
