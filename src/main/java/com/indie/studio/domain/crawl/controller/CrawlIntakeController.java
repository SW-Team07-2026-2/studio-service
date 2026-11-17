package com.indie.studio.domain.crawl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.BatchResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ConcertBatch;
import com.indie.studio.domain.crawl.service.CrawlConcertService;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

/**
 * 크롤러가 부르는 곳 (11/17 결정). 인증은 CrawlerTokenFilter.
 * <pre>
 * 공연                       후보로 받아 두고 공연 관리자가 확인한 뒤 등록 (crawl_concert · CrawlConcertController)
 * 아티스트 · 앨범 · LP/CD · 굿즈  확인 없이 바로 Catalog 관리자 적재 경로로 넘김. 본문 · 결과는 Catalog 수집 계약(catalog 문서 7.3 · snake_case) 그대로
 *                            (항목별 CREATED · UPDATED · UNCHANGED · FAILED). 넘긴 결과는 로그 한 줄 (crawl_forwarded) · 고칠 것은 Studio 상품 관리 화면에서
 * </pre>
 */
@RestController
public class CrawlIntakeController {

    private static final Logger log = LoggerFactory.getLogger(CrawlIntakeController.class);

    private final ServiceClient serviceClient;
    private final CrawlConcertService crawlConcertService;

    public CrawlIntakeController(ServiceClient serviceClient, CrawlConcertService crawlConcertService) {
        this.serviceClient = serviceClient;
        this.crawlConcertService = crawlConcertService;
    }

    /** { items: [{ source, external_id, source_hash, concert: {...} }] } 최대 100건 → 항목별 CREATED · UPDATED · UNCHANGED · REVIEWED */
    @PostMapping("/internal/crawl/concerts")
    public ApiResponse<BatchResult> concerts(@Valid @RequestBody ConcertBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(crawlConcertService.receive(batch.items())));
    }

    /** { items: [ArtistUpsert] } 최대 500건 — 굿즈는 아티스트에 담아 보냅니다 */
    @PostMapping("/internal/crawl/catalog/artists")
    public ApiResponse<JsonNode> artists(@RequestBody JsonNode body) {
        return ApiResponse.onSuccess(forward("/internal/admin/catalog/ingest/artists", body));
    }

    /** { items: [AlbumUpsert] } 최대 500건 — 트랙 · LP/CD 는 앨범에 담아 보냅니다 */
    @PostMapping("/internal/crawl/catalog/albums")
    public ApiResponse<JsonNode> albums(@RequestBody JsonNode body) {
        return ApiResponse.onSuccess(forward("/internal/admin/catalog/ingest/albums", body));
    }

    /** { source, type, items: [{ external_id, source_hash }] } → { new, changed, unchanged } — 바뀐 것만 보내기 위한 확인 */
    @PostMapping("/internal/crawl/catalog/diff")
    public ApiResponse<JsonNode> diff(@RequestBody JsonNode body) {
        return ApiResponse.onSuccess(serviceClient.sendAsCrawler(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/ingest/diff", body));
    }

    private JsonNode forward(String path, JsonNode body) {
        JsonNode result = serviceClient.sendAsCrawler(Target.CATALOG, HttpMethod.POST, path, body);
        Map<String, Integer> counts = new TreeMap<>();
        if (result != null) result.path("items").forEach(i -> counts.merge(i.path("result").asText("?"), 1, Integer::sum));
        log.info("crawl_forwarded path={} items={} results={}", path, body.path("items").size(), counts);
        return result;
    }
}
