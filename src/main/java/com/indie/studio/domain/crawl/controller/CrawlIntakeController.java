package com.indie.studio.domain.crawl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.BatchResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CatalogBatch;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ConcertBatch;
import com.indie.studio.domain.crawl.dto.CrawlDtos.Reviewed;
import com.indie.studio.domain.crawl.entity.CrawlCatalogEntity.Type;
import com.indie.studio.domain.crawl.service.CrawlCatalogService;
import com.indie.studio.domain.crawl.service.CrawlConcertService;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 크롤러가 부르는 곳 (11/17 결정). 인증은 CrawlerTokenFilter. 크롤러가 보낸 것은 전부 관리자 승인을 거쳐 사용자 서비스로 갑니다.
 * <pre>
 * 공연                       후보로 받아 두고 공연 관리자가 확인한 뒤 등록 (crawl_concert · CrawlConcertController)
 * 아티스트 · 앨범 · LP/CD · 굿즈  후보로 받아 두고 상품 관리자가 승인한 뒤 Catalog 관리자 적재 (crawl_catalog · CrawlCatalogController)
 * diff                       조회라 그대로 Catalog 로
 * reviewed                   관리자가 처리한 후보의 키 · 상태 (크롤러가 거절한 것을 다시 찾지 않게 · 11/24)
 * </pre>
 * 응답은 항목별 CREATED · UPDATED · UNCHANGED · REVIEWED (REVIEWED = 관리자가 적재하지 않기로 했거나 등록을 끝낸 후보라 바꾸지 않음).
 */
@RestController
public class CrawlIntakeController {

    private final ServiceClient serviceClient;
    private final CrawlConcertService crawlConcertService;
    private final CrawlCatalogService crawlCatalogService;

    public CrawlIntakeController(ServiceClient serviceClient, CrawlConcertService crawlConcertService, CrawlCatalogService crawlCatalogService) {
        this.serviceClient = serviceClient;
        this.crawlConcertService = crawlConcertService;
        this.crawlCatalogService = crawlCatalogService;
    }

    /** { items: [{ source, external_id, source_hash, concert: {...} }] } 최대 100건 */
    @PostMapping("/internal/crawl/concerts")
    public ApiResponse<BatchResult> concerts(@Valid @RequestBody ConcertBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlConcertService.receive(batch.items())));
    }

    /** { items: [ArtistUpsert] } 최대 500건 — 굿즈는 아티스트에 담아 보냅니다 */
    @PostMapping("/internal/crawl/catalog/artists")
    public ApiResponse<BatchResult> artists(@Valid @RequestBody CatalogBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlCatalogService.receive(Type.ARTIST, batch.items())));
    }

    /** { items: [AlbumUpsert — artist_id 대신 artist_ref: { source, external_id }] } 최대 500건 — 트랙 · LP/CD 는 앨범에 담아 보냅니다 */
    @PostMapping("/internal/crawl/catalog/albums")
    public ApiResponse<BatchResult> albums(@Valid @RequestBody CatalogBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlCatalogService.receive(Type.ALBUM, batch.items())));
    }

    /** → { concerts, artists, albums: [{ source, external_id, status }] } — 관리자가 처리한 후보만 (REJECTED · APPROVED · 공연은 CHANGED 도).
     *  크롤러가 실행 처음에 한 번 불러, 거절한 것은 다시 찾지 않고 등록한 공연은 예매 오픈을 찾지 않음 (11/24 — Gemini · 검색 크레딧 아끼기) */
    @GetMapping("/internal/crawl/reviewed")
    public ApiResponse<Reviewed> reviewed() {
        return ApiResponse.onSuccess(new Reviewed(crawlConcertService.reviewed(),
                crawlCatalogService.reviewed(Type.ARTIST), crawlCatalogService.reviewed(Type.ALBUM)));
    }

    /** { source, type, items: [{ external_id, source_hash }] } → { new, changed, unchanged } — Catalog 에 이미 들어간 것과 비교 */
    @PostMapping("/internal/crawl/catalog/diff")
    public ApiResponse<JsonNode> diff(@RequestBody JsonNode body) {
        return ApiResponse.onSuccess(serviceClient.sendAsCrawler(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/ingest/diff", body));
    }
}
