package com.indie.studio.domain.crawl.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 크롤러 ↔ Studio 공연 후보. 크롤러 쪽 본문은 Catalog 수집 계약과 같이 snake_case 입니다 */
public final class CrawlDtos {

    private CrawlDtos() {}

    /** POST /internal/crawl/concerts — 한 요청 최대 100건 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ConcertBatch(@NotEmpty @Size(max = 100) List<@Valid @NotNull ConcertItem> items) {}

    /** concert — 크롤러가 모은 공연 값 (모양은 크롤러 계약 문서). Studio 는 그대로 보관해 관리자 화면에 보여 줍니다 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ConcertItem(@NotBlank @Size(max = 32) String source,
                              @NotBlank @Size(max = 128) String externalId,
                              @Size(max = 128) String sourceHash,
                              @NotNull JsonNode concert) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ItemResult(String externalId, long id, Result result) {
        /** REVIEWED — 관리자가 이미 처리한 후보라 바꾸지 않음 */
        public enum Result { CREATED, UPDATED, UNCHANGED, REVIEWED }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchResult(List<ItemResult> items) {}

    /** 관리자 화면 (camelCase) */
    public record CandidateView(long id, String source, String externalId, String status, JsonNode concert,
                                Instant receivedAt, Long reviewedBy, Instant reviewedAt, Long concertId) {}
}
