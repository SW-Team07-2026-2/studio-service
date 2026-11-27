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
 * 크롤러 -> Studio 내부 수집 API -> 후보 저장과 관리자 검수.
 * 해시 비교는 Catalog에 전달하고 검수 완료 내역은 크롤러에 반환합니다.
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

    /**
     * 공연 후보 묶음을 저장합니다.
     *
     * @param batch 공연 후보 묶음
     */
    @PostMapping("/internal/crawl/concerts")
    public ApiResponse<BatchResult> concerts(@Valid @RequestBody ConcertBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlConcertService.receive(batch.items())));
    }

    /**
     * 아티스트 후보 묶음을 저장합니다.
     *
     * @param batch 아티스트 또는 앨범 후보 묶음
     */
    @PostMapping("/internal/crawl/catalog/artists")
    public ApiResponse<BatchResult> artists(@Valid @RequestBody CatalogBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlCatalogService.receive(Type.ARTIST, batch.items())));
    }

    /**
     * 앨범 후보 묶음을 저장합니다.
     *
     * @param batch 아티스트 또는 앨범 후보 묶음
     */
    @PostMapping("/internal/crawl/catalog/albums")
    public ApiResponse<BatchResult> albums(@Valid @RequestBody CatalogBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlCatalogService.receive(Type.ALBUM, batch.items())));
    }

    /** 검수 완료 후보의 식별자와 상태를 반환합니다. */
    @GetMapping("/internal/crawl/reviewed")
    public ApiResponse<Reviewed> reviewed() {
        return ApiResponse.onSuccess(new Reviewed(crawlConcertService.reviewed(),
                crawlCatalogService.reviewed(Type.ARTIST), crawlCatalogService.reviewed(Type.ALBUM)));
    }

    /**
     * Catalog에 수집 해시 비교를 요청합니다.
     *
     * @param body 대상 서비스에 전달할 요청 본문
     */
    @PostMapping("/internal/crawl/catalog/diff")
    public ApiResponse<JsonNode> diff(@RequestBody JsonNode body) {
        return ApiResponse.onSuccess(serviceClient.sendAsCrawler(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/ingest/diff", body));
    }
}
