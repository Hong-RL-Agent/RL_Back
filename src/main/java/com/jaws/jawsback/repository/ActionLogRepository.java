package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.ActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long> {

    List<ActionLog> findBySessionSessionUuidOrderByCreatedAtAsc(String sessionUuid);
}
