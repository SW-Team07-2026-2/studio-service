package com.indie.studio.domain.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 로그인 2단계 대기 — 1차(아이디 · 비밀번호)를 통과한 사람만 가집니다. 5분 · 1회용 */
@Entity
@Table(name = "admin_login_challenge")
public class LoginChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tokenHash;
    private Long adminId;
    private byte[] enrollSecretEnc;
    private String clientIp;
    private int attempts;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt;

    protected LoginChallengeEntity() {}

    public LoginChallengeEntity(String tokenHash, long adminId, byte[] enrollSecretEnc, String clientIp, Instant now, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.adminId = adminId;
        this.enrollSecretEnc = enrollSecretEnc;
        this.clientIp = clientIp;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public void failedAttempt() { attempts++; }

    public Long getId() { return id; }
    public Long getAdminId() { return adminId; }
    public byte[] getEnrollSecretEnc() { return enrollSecretEnc; }
    public int getAttempts() { return attempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
}
