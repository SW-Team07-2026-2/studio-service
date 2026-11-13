package com.indie.studio.domain.stage.entity;

import jakarta.persistence.Column;
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

/**
 * 무대 그리기 작업 한 건.
 * PENDING(접수) → ANALYZING(GPU 검증 중) → REVIEW(결과 도착 · 관리자 확인 대기) → SENT(Concert 로 보냄) · 어디서든 FAILED.
 * 값을 바꾸면 V1__studio_schema.sql 의 ck_stage_job_status 도 함께 고칩니다.
 */
@Entity
@Table(name = "stage_job")
public class StageJobEntity {

    public enum Status { PENDING, ANALYZING, REVIEW, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long venueId;
    private String venueName;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Status status;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String drawingJson;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String resultJson;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String sentJson;
    private String errorMessage;
    private Long requestedBy;
    private Long sentBy;
    private Instant createdAt;
    private Instant analyzedAt;
    private Instant sentAt;

    protected StageJobEntity() {}

    public StageJobEntity(long venueId, String venueName, String drawingJson, long requestedBy, Instant now) {
        this.venueId = venueId;
        this.venueName = venueName;
        this.drawingJson = drawingJson;
        this.requestedBy = requestedBy;
        this.status = Status.PENDING;
        this.createdAt = now;
    }

    public void analyzing() { this.status = Status.ANALYZING; }

    public void reviewReady(String resultJson, Instant now) {
        this.status = Status.REVIEW;
        this.resultJson = resultJson;
        this.analyzedAt = now;
    }

    public void failed(String message, Instant now) {
        this.status = Status.FAILED;
        this.errorMessage = message == null ? null : message.length() > 255 ? message.substring(0, 255) : message;
        this.analyzedAt = now;
    }

    public void sent(String sentJson, long adminId, Instant now) {
        this.status = Status.SENT;
        this.sentJson = sentJson;
        this.sentBy = adminId;
        this.sentAt = now;
    }

    public Long getId() { return id; }
    public Long getVenueId() { return venueId; }
    public String getVenueName() { return venueName; }
    public Status getStatus() { return status; }
    public String getDrawingJson() { return drawingJson; }
    public String getResultJson() { return resultJson; }
    public String getSentJson() { return sentJson; }
    public String getErrorMessage() { return errorMessage; }
    public Long getRequestedBy() { return requestedBy; }
    public Long getSentBy() { return sentBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public Instant getSentAt() { return sentAt; }
}
