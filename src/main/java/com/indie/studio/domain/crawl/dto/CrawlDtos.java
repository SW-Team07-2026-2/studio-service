package com.indie.studio.domain.crawl.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 크롤러 <-> Studio 공연 후보. 크롤러 쪽 본문은 Catalog 수집 계약과 같이 snake_case 입니다 */
public final class CrawlDtos {

    private CrawlDtos() {}

    /** POST /internal/crawl/concerts - 한 요청 최대 100건 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ConcertBatch(@NotEmpty @Size(max = 100) List<@Valid @NotNull ConcertItem> items) {}

    /** concert - 크롤러가 모은 공연 값 (모양은 크롤러 계약 문서). Studio 는 그대로 보관해 관리자 화면에 보여 줍니다 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ConcertItem(@NotBlank @Size(max = 32) String source,
                              @NotBlank @Size(max = 128) String externalId,
                              @Size(max = 128) String sourceHash,
                              @NotNull JsonNode concert) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ItemResult(String externalId, long id, Result result) {
        /** REVIEWED - 관리자가 이미 처리한 후보라 바꾸지 않음, CHANGED - 등록한 공연이 출처에서 바뀌어 관리자 확인으로 (공연만) */
        public enum Result { CREATED, UPDATED, UNCHANGED, REVIEWED, CHANGED }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchResult(List<ItemResult> items) {}

    /** GET /internal/crawl/reviewed - 관리자가 처리한 후보 (대기 중인 것은 뺌). 크롤러가 실행 처음에 한 번 불러 거절한 것은 다시 찾지 않음 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Reviewed(List<ReviewedKey> concerts, List<ReviewedKey> artists, List<ReviewedKey> albums) {}

    /** status - REJECTED, APPROVED (공연은 CHANGED 도: 등록한 뒤 출처에서 바뀜) */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReviewedKey(String source, String externalId, String status) {}

    /** 관리자 화면 (camelCase) */
    /** previous - CHANGED 일 때 등록(또는 마지막 확인) 때의 값, 그 밖에는 null */
    public record CandidateView(long id, String source, String externalId, String status, JsonNode concert, JsonNode previous,
                                Instant receivedAt, Long reviewedBy, Instant reviewedAt, Long concertId) {}

    /**
     * POST /internal/crawl/catalog/artists, albums - 한 요청 최대 500건. 항목은 Catalog 수집 계약 그대로 (snake_case)이고
     * 앨범만 artist_id 대신 artist_ref: { source, external_id } (크롤러는 Catalog id 를 모름, 승인할 때 Studio 가 바꿈).
     * 항목 안의 값 검사는 승인할 때 Catalog 가 합니다.
     */
    public record CatalogBatch(@NotEmpty @Size(max = 500) List<@NotNull ObjectNode> items) {}

    /** 관리자 화면 (camelCase) - item 은 받은 항목 그대로, catalogId 가 있으면 이미 Catalog 에 있는 것의 변경 */
    public record CatalogCandidateView(long id, String type, String source, String externalId, String status, JsonNode item,
                                       Instant receivedAt, Long reviewedBy, Instant reviewedAt, Long catalogId, String lastError) {}

    /** 여러 건 승인 - 아티스트부터 처리합니다 */
    public record ApproveMany(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> ids) {}

    /** 여러 건 승인 결과 - 실패한 것은 code, message (나머지는 계속 처리) */
    public record ApproveResult(long id, boolean approved, Long catalogId, String code, String message) {}
}
