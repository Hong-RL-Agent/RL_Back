package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.StaticIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaticIssueRepository extends JpaRepository<StaticIssue, Long> {

    List<StaticIssue> findBySessionSessionUuidOrderByIdAsc(String sessionUuid);

    int countBySessionSessionUuid(String sessionUuid);

    int countBySessionId(Long sessionId);
}
