package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.DetectedBug;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DetectedBugRepository extends JpaRepository<DetectedBug, Long> {

    List<DetectedBug> findBySessionSessionUuidOrderByIdAsc(String sessionUuid);

    int countBySessionSessionUuid(String sessionUuid);

    int countBySessionId(Long sessionId);
}
