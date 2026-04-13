package com.jaws.jawsback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 필수 기본 생성자
public class User {

    @Id
    @SequenceGenerator(name = "user_account_id_seq", sequenceName = "user_account_id_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_account_id_seq")
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "user_name", nullable = false, length = 50)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "user_role")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(String email, String passwordHash, String userName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.userName = userName;
        this.role = role;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.role == null) this.role = Role.USER;  // 기본값
    }

    public enum Role {
        ADMIN, USER
    }
}