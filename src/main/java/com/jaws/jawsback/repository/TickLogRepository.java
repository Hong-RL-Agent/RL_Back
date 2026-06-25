package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.TickLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TickLogRepository extends JpaRepository<TickLog, Long> {

    List<TickLog> findTop100ByOrderByCreatedAtDesc();

    List<TickLog> findAllByOrderByCreatedAtDesc();

    List<TickLog> findTop100BySessionSessionUuidOrderByTickNumberDesc(String sessionUuid);

    List<TickLog> findBySessionSessionUuidOrderByTickNumberDesc(String sessionUuid);

    long countBySessionSessionUuid(String sessionUuid);

    long countBySessionUserId(Long userId);
}
