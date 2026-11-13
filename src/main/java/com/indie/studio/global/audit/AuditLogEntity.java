package com.indie.studio.global.audit;

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

import java.time.Instant;

/** 작업 기록 — 누가 · 언제 · 무엇을 불렀고 결과가 어땠나. 🔴 검색어 · 요청 본문 · 개인정보 값은 남기지 않습니다 (경로의 id 까지만) */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    /** DENIED = 권한 밖 경로를 부름 (응답은 404) */
    public enum Result { OK, FAILED, DENIED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant occurredAt;
    private Long adminId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Role role;
    private String method;
    private String path;
    private int status;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Result result;
    private String clientIp;
    private int durationMs;

    protected AuditLogEntity() {}

    public AuditLogEntity(Instant occurredAt, long adminId, Role role, String method, String path, int status,
                          Result result, String clientIp, int durationMs) {
        this.occurredAt = occurredAt;
        this.adminId = adminId;
        this.role = role;
        this.method = method;
        this.path = path;
        this.status = status;
        this.result = result;
        this.clientIp = clientIp;
        this.durationMs = durationMs;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getAdminId() { return adminId; }
    public Role getRole() { return role; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public int getStatus() { return status; }
    public Result getResult() { return result; }
    public String getClientIp() { return clientIp; }
    public int getDurationMs() { return durationMs; }
}
