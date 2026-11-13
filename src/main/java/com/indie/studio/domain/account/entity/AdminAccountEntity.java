package com.indie.studio.domain.account.entity;

import com.indie.studio.global.auth.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;

/** 관리자 계정 — 행을 지우지 않습니다 (기록이 이 id 를 가리킴). 그만둔 사람은 DISABLED */
@Entity
@Table(name = "admin_account")
public class AdminAccountEntity {

    public enum Status { ACTIVE, DISABLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String loginId;
    private String name;
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Role role;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Status status;
    private byte[] totpSecretEnc;
    private Long totpLastStep;
    private int failedCount;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected AdminAccountEntity() {}

    public AdminAccountEntity(String loginId, String name, String passwordHash, Role role, Instant now) {
        this.loginId = loginId;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() { return status == Status.ACTIVE; }

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }

    /** 비밀번호 · OTP 실패 — maxFailures 번 연속이면 잠급니다 */
    public void recordFailure(Instant now, int maxFailures, Duration lock) {
        failedCount++;
        if (failedCount >= maxFailures) {
            lockedUntil = now.plus(lock);
            failedCount = 0;
        }
        updatedAt = now;
    }

    /** OTP 통과 — enrolledSecretEnc 가 있으면 이번에 등록한 비밀키입니다 */
    public void loginSucceeded(byte[] enrolledSecretEnc, long totpStep, Instant now) {
        if (enrolledSecretEnc != null) totpSecretEnc = enrolledSecretEnc;
        totpLastStep = totpStep;
        failedCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.failedCount = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** OTP 초기화 — 다음 로그인 때 다시 등록합니다 (기기 분실) */
    public void resetOtp(Instant now) {
        this.totpSecretEnc = null;
        this.totpLastStep = null;
        this.updatedAt = now;
    }

    public void update(String name, Role role, Status status, Instant now) {
        if (name != null) this.name = name;
        if (role != null) this.role = role;
        if (status != null) this.status = status;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public byte[] getTotpSecretEnc() { return totpSecretEnc; }
    public Long getTotpLastStep() { return totpLastStep; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}
