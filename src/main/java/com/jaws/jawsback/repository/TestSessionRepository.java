package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.TestSession;
import com.jaws.jawsback.entity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestSessionRepository extends JpaRepository<TestSession, Long> {

    Optional<TestSession> findBySessionUuid(String sessionUuid);

    List<TestSession> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<TestSession> findTop20ByOrderByCreatedAtDesc();

    List<TestSession> findAllByOrderByCreatedAtDesc();

    long countByUserId(Long userId);

    long countByStatus(SessionStatus status);
}
