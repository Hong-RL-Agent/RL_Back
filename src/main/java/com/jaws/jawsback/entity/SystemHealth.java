package com.jaws.jawsback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "system_health")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "component_name", nullable = false, unique = true, length = 50)
    private String componentName;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "last_ping", nullable = false)
    private LocalDateTime lastPing;

    @PrePersist
    @PreUpdate
    protected void updatePingTime() {
        this.lastPing = LocalDateTime.now();
    }
}
