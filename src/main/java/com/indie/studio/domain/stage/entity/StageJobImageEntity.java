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

/** 도면 사진 — S3 대신 DB 에 둡니다. 작업 목록 · 상태 조회에 딸려 오지 않게 따로 뒀습니다 */
@Entity
@Table(name = "stage_job_image")
public class StageJobImageEntity {

    /** ORIGINAL = 관리자가 올린 사진 · RESULT = GPU 서버가 돌려준 사진 */
    public enum Kind { ORIGINAL, RESULT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long jobId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Kind kind;
    private String contentType;
    @Column(columnDefinition = "LONGBLOB")
    private byte[] data;

    protected StageJobImageEntity() {}

    public StageJobImageEntity(long jobId, Kind kind, String contentType, byte[] data) {
        this.jobId = jobId;
        this.kind = kind;
        this.contentType = contentType;
        this.data = data;
    }

    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
