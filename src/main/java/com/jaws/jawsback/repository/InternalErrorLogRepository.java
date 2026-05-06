package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.InternalErrorLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InternalErrorLogRepository extends JpaRepository<InternalErrorLog, Long> {
}
