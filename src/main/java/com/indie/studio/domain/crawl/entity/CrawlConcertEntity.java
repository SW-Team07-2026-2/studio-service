package com.indie.studio.domain.crawl.entity;

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
 * 크롤러가 넘긴 공연 후보 한 건. PENDING(확인 대기) → APPROVED(Concert 에 등록) · REJECTED(등록 안 함).
 * 값을 바꾸면 V2__studio_values.sql 의 ck_crawl_concert_status 도 함께 고칩니다.
 */
@Entity
@Table(name = "crawl_concert")
public class CrawlConcertEntity {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String source;
    private String externalId;
    private String sourceHash;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String payloadJson;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Status status;
    private Instant receivedAt;
    private Long reviewedBy;
    private Instant reviewedAt;
    private Long concertId;

    protected CrawlConcertEntity() {}

    public CrawlConcertEntity(String source, String externalId, String sourceHash, String payloadJson, Instant now) {
        this.source = source;
        this.externalId = externalId;
        this.sourceHash = sourceHash;
        this.payloadJson = payloadJson;
        this.status = Status.PENDING;
        this.receivedAt = now;
    }

    /** 확인 전에 다시 받은 값 */
    public void received(String sourceHash, String payloadJson, Instant now) {
        this.sourceHash = sourceHash;
        this.payloadJson = payloadJson;
        this.receivedAt = now;
    }

    public void approved(long concertId, long adminId, Instant now) {
        this.status = Status.APPROVED;
        this.concertId = concertId;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    public void rejected(long adminId, Instant now) {
        this.status = Status.REJECTED;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }
    public String getSourceHash() { return sourceHash; }
    public String getPayloadJson() { return payloadJson; }
    public Status getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Long getConcertId() { return concertId; }
}
