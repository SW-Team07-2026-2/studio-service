package com.indie.studio.domain.crawl.exception;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum CrawlErrorCode implements BaseErrorCode {
    CRAWL_CONCERT_NOT_FOUND(HttpStatus.NOT_FOUND, "수집된 공연 후보를 찾을 수 없습니다"),
    /** 이미 등록했거나 등록하지 않기로 한 후보 — 다른 관리자가 먼저 처리한 경우 */
    CRAWL_CONCERT_REVIEWED(HttpStatus.CONFLICT, "이미 처리한 후보입니다");

    private final HttpStatus status;
    private final String message;

    CrawlErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
