package com.indie.studio.domain.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 로그인 세션 — 쿠키 값의 해시만 저장합니다 */
@Entity
@Table(name = "admin_session")
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tokenHash;
    private Long adminId;
    private String csrfToken;
    private String clientIp;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant lastSeenAt;
    private Instant revokedAt;

    protected SessionEntity() {}

    public SessionEntity(String tokenHash, long adminId, String csrfToken, String clientIp, Instant now, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.adminId = adminId;
        this.csrfToken = csrfToken;
        this.clientIp = clientIp;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
    }

    public void seen(Instant now) { this.lastSeenAt = now; }

    public Long getId() { return id; }
    public Long getAdminId() { return adminId; }
    public String getCsrfToken() { return csrfToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
