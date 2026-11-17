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
import java.util.Arrays;
import java.util.List;

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
    private String otpEmails;   // 쉼표 구분 — getOtpEmails()
    private int failedCount;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected AdminAccountEntity() {}

    public AdminAccountEntity(String loginId, String name, String passwordHash, Role role, List<String> otpEmails, Instant now) {
        this.loginId = loginId;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
        this.otpEmails = joinEmails(otpEmails);
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() { return status == Status.ACTIVE; }

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }

    /** 비밀번호 · 인증 코드 실패 — maxFailures 번 연속이면 잠급니다 */
    public void recordFailure(Instant now, int maxFailures, Duration lock) {
        failedCount++;
        if (failedCount >= maxFailures) {
            lockedUntil = now.plus(lock);
            failedCount = 0;
        }
        updatedAt = now;
    }

    /** 메일 인증 코드 통과 */
    public void loginSucceeded(Instant now) {
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

    public void update(String name, Role role, Status status, List<String> otpEmails, Instant now) {
        if (name != null) this.name = name;
        if (role != null) this.role = role;
        if (status != null) this.status = status;
        if (otpEmails != null) this.otpEmails = joinEmails(otpEmails);
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public List<String> getOtpEmails() { return Arrays.asList(otpEmails.split(",")); }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }

    /** 소문자 · 앞뒤 공백 없이 · 중복 없이 */
    private static String joinEmails(List<String> emails) {
        return String.join(",", emails.stream().map(e -> e.strip().toLowerCase()).distinct().toList());
    }
}
