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
 * 크롤러가 넘긴 아티스트, 앨범 후보 한 건. PENDING(승인 대기) -> APPROVED(Catalog 에 적재), REJECTED(적재 안 함).
 * 승인된 뒤 값이 바뀌어 다시 오면 PENDING 으로 돌아갑니다 (catalog_id 는 그대로 - 화면에서 "변경" 으로 보임).
 * 값을 바꾸면 V2__studio_values.sql 의 ck_crawl_catalog_type, ck_crawl_catalog_status 도 함께 고칩니다.
 */
@Entity
@Table(name = "crawl_catalog")
public class CrawlCatalogEntity {

    public enum Type { ARTIST, ALBUM }

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Type type;
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
    private Long catalogId;
    private String lastError;

    protected CrawlCatalogEntity() {}

    public CrawlCatalogEntity(Type type, String source, String externalId, String sourceHash, String payloadJson, Instant now) {
        this.type = type;
        this.source = source;
        this.externalId = externalId;
        this.sourceHash = sourceHash;
        this.payloadJson = payloadJson;
        this.status = Status.PENDING;
        this.receivedAt = now;
    }

    /** 다시 받은 값 - 승인된 것이었으면 다시 승인 대기로 */
    public void received(String sourceHash, String payloadJson, Instant now) {
        this.sourceHash = sourceHash;
        this.payloadJson = payloadJson;
        this.receivedAt = now;
        this.status = Status.PENDING;
        this.lastError = null;
    }

    /** 관리자가 고쳐서 승인한 값 */
    public void edited(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public void approved(long catalogId, long adminId, Instant now) {
        this.status = Status.APPROVED;
        this.catalogId = catalogId;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
        this.lastError = null;
    }

    public void failed(String error) {
        this.lastError = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
    }

    public void rejected(long adminId, Instant now) {
        this.status = Status.REJECTED;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    public Long getId() { return id; }
    public Type getType() { return type; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }
    public String getSourceHash() { return sourceHash; }
    public String getPayloadJson() { return payloadJson; }
    public Status getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Long getCatalogId() { return catalogId; }
    public String getLastError() { return lastError; }
}
