package com.jaws.jawsback.repository;

import com.jaws.jawsback.entity.SystemHealth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemHealthRepository extends JpaRepository<SystemHealth, Long> {

    Optional<SystemHealth> findByComponentName(String componentName);
}
