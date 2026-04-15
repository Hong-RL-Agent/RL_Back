package com.example.demo.service;

import com.example.demo.model.TestEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TestStreamService {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, List<TestEvent>> eventStore = new ConcurrentHashMap<>();

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        eventStore.put(sessionId, new CopyOnWriteArrayList<>());
        System.out.println("[SSE] 세션 생성 - sessionId=" + sessionId);
        return sessionId;
    }

    public SseEmitter connect(String sessionId) {
        System.out.println("[SSE] connect 요청 - sessionId=" + sessionId);

        SseEmitter emitter = new SseEmitter(60L * 60L * 1000L);
        emitters.put(sessionId, emitter);

        emitter.onCompletion(() -> {
            System.out.println("[SSE] completed - sessionId=" + sessionId);
            emitters.remove(sessionId);
        });

        emitter.onTimeout(() -> {
            System.out.println("[SSE] timeout - sessionId=" + sessionId);
            emitters.remove(sessionId);
        });

        emitter.onError((e) -> {
            System.out.println("[SSE] error - sessionId=" + sessionId + ", error=" + e.getMessage());
            emitters.remove(sessionId);
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data("SSE connected"));
            System.out.println("[SSE] connected event 전송 완료 - sessionId=" + sessionId);

            List<TestEvent> history = eventStore.getOrDefault(sessionId, List.of());
            for (TestEvent event : history) {
                emitter.send(SseEmitter.event().name(event.getType()).data(event));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    public void pushEvent(String sessionId, TestEvent event) {
        System.out.println("[SSE] pushEvent 호출 - sessionId=" + sessionId + ", type=" + event.getType());

        eventStore.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(event);

        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            System.out.println("[SSE] emitter 없음 - sessionId=" + sessionId);
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(event));
            System.out.println("[SSE] 이벤트 전송 성공 - sessionId=" + sessionId + ", type=" + event.getType());
        } catch (IOException e) {
            System.out.println("[SSE] 이벤트 전송 실패 - " + e.getMessage());
            emitter.complete();
            emitters.remove(sessionId);
        }
    }

    public void complete(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } finally {
                emitters.remove(sessionId);
            }
        }
    }
}