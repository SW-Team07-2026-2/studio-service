package com.indie.studio.domain.crawl.exception;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum CrawlErrorCode implements BaseErrorCode {
    CRAWL_CONCERT_NOT_FOUND(HttpStatus.NOT_FOUND, "수집된 공연 후보를 찾을 수 없습니다"),
    /** 이미 등록했거나 등록하지 않기로 한 후보 - 다른 관리자가 먼저 처리한 경우 */
    CRAWL_CONCERT_REVIEWED(HttpStatus.CONFLICT, "이미 처리한 후보입니다"),
    CRAWL_CATALOG_NOT_FOUND(HttpStatus.NOT_FOUND, "수집된 아티스트 · 앨범 후보를 찾을 수 없습니다"),
    /** 이미 승인했거나 적재하지 않기로 한 후보 - 다른 관리자가 먼저 처리한 경우 */
    CRAWL_CATALOG_REVIEWED(HttpStatus.CONFLICT, "이미 처리한 후보입니다"),
    /** 앨범의 artist_ref 가 가리키는 아티스트 후보가 아직 Catalog 에 없음 */
    CRAWL_ARTIST_NOT_APPROVED(HttpStatus.CONFLICT, "아티스트를 먼저 승인해 주세요"),
    /** Catalog 가 그 항목을 거절함 (항목 FAILED) - 사유는 message, 후보에 last_error 로 남음 */
    CRAWL_CATALOG_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Catalog 가 이 항목을 거절했습니다"),
    /** 넣은 판매가가 0 이하 - 크롤러는 판매가를 비워 보내고(null) 관리자가 넣습니다. 새 옵션에 판매가가 없으면 Catalog 가 거절(CRAWL_CATALOG_FAILED) */
    CRAWL_PRICE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "피지컬 앨범 · 굿즈 옵션의 판매 가격을 넣어 주세요");

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
