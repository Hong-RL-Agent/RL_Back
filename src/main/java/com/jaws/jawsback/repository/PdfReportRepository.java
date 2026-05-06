package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.PdfReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PdfReportRepository extends JpaRepository<PdfReport, Long> {

    Optional<PdfReport> findFirstBySessionSessionUuidOrderByCreatedAtDesc(String sessionUuid);
}
