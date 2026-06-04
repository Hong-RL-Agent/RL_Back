package com.jaws.jawsback.controller;

import com.jaws.jawsback.dto.AdminDto.AdminActivitiesResponse;
import com.jaws.jawsback.dto.AdminDto.AdminIssuesResponse;
import com.jaws.jawsback.dto.AdminDto.AdminLogCollectorsResponse;
import com.jaws.jawsback.dto.AdminDto.AdminSessionDetailResponse;
import com.jaws.jawsback.dto.AdminDto.AdminSessionItem;
import com.jaws.jawsback.dto.AdminDto.AdminSessionsResponse;
import com.jaws.jawsback.dto.AdminDto.AdminSummaryResponse;
import com.jaws.jawsback.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/summary")
    public ResponseEntity<AdminSummaryResponse> summary() {
        return ResponseEntity.ok(adminService.summary());
    }

    @GetMapping("/sessions")
    public ResponseEntity<AdminSessionsResponse> sessions() {
        return ResponseEntity.ok(adminService.sessions());
    }

    @GetMapping("/activities")
    public ResponseEntity<AdminActivitiesResponse> activities() {
        return ResponseEntity.ok(adminService.activities());
    }

    @GetMapping("/issues")
    public ResponseEntity<AdminIssuesResponse> issues() {
        return ResponseEntity.ok(adminService.issues());
    }

    @GetMapping("/log-collectors")
    public ResponseEntity<AdminLogCollectorsResponse> logCollectors() {
        return ResponseEntity.ok(adminService.logCollectors());
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<AdminSessionDetailResponse> sessionDetail(@PathVariable String sessionId) {
        return ResponseEntity.ok(adminService.sessionDetail(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<AdminSessionItem> stopSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(adminService.stopSession(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/restart")
    public ResponseEntity<AdminSessionItem> restartSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(adminService.restartSession(sessionId));
    }
}
