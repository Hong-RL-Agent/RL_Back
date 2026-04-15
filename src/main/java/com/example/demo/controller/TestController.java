package com.example.demo.controller;

import com.example.demo.service.TestStreamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "http://localhost:5173")
public class TestController {

    private final TestStreamService testStreamService;

    public TestController(TestStreamService testStreamService) {
        this.testStreamService = testStreamService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startTest(@RequestBody(required = false) Map<String, String> body) {
        System.out.println("====================================");
        System.out.println("[BACKEND] /api/test/start 호출됨");

        String sessionId = testStreamService.createSession();
        String targetUrl = body != null
                ? body.getOrDefault("targetUrl", "http://localhost:8080/")
                : "http://localhost:8080/";

        System.out.println("[BACKEND] sessionId = " + sessionId);
        System.out.println("[BACKEND] targetUrl = " + targetUrl);

        try {
            String classPath = System.getProperty("java.class.path");

            System.out.println("[BACKEND] classPath = " + classPath);
            System.out.println("[BACKEND] Agent 실행 시작");

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "java",
                    "-cp",
                    classPath,
                    "com.example.demo.agent.AutonomousAgent",
                    targetUrl,
                    sessionId
            );

            processBuilder.inheritIO();
            processBuilder.start();

            System.out.println("[BACKEND] Agent 프로세스 실행 완료");
            System.out.println("====================================");
        } catch (IOException e) {
            System.out.println("[BACKEND] Agent 실행 실패");
            e.printStackTrace();

            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Playwright 실행 실패: " + e.getMessage()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", "running"
        ));
    }
}