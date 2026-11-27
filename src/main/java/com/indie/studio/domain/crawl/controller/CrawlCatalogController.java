package com.indie.studio.domain.crawl.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ApproveMany;
import com.indie.studio.domain.crawl.dto.CrawlDtos.ApproveResult;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CatalogCandidateView;
import com.indie.studio.domain.crawl.entity.CrawlCatalogEntity;
import com.indie.studio.domain.crawl.service.CrawlCatalogService;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 상품 관리자 화면 -> Studio 후보 검수 -> Catalog 내부 적재 API. */
@RestController
public class CrawlCatalogController {

    private final CrawlCatalogService crawlCatalogService;

    public CrawlCatalogController(CrawlCatalogService crawlCatalogService) {
        this.crawlCatalogService = crawlCatalogService;
    }

    /**
     * ?status=PENDING|APPROVED|REJECTED (기본 PENDING) &type=ARTIST|ALBUM (없으면 둘 다) &beforeId= &size= - 최신순
     *
     * @param status 조회할 후보 상태
     * @param type 처리할 종류
     * @param beforeId 이 ID보다 이전 항목부터 조회
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/studio/crawl/catalog")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<List<CatalogCandidateView>> list(@RequestParam(defaultValue = "PENDING") CrawlCatalogEntity.Status status,
                                                        @RequestParam(required = false) CrawlCatalogEntity.Type type,
                                                        @RequestParam(required = false) @Positive Long beforeId,
                                                        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return ApiResponse.onSuccess(crawlCatalogService.list(status, type, beforeId, size));
    }

    /**
     * 승인 -> Catalog 적재. 본문을 비우면 받은 값 그대로, 넣으면 고친 항목(받은 항목과 같은 모양, snake_case)으로 저장해 보냅니다.
     * 409 CRAWL_ARTIST_NOT_APPROVED = 앨범의 아티스트를 먼저 승인, 422 CRAWL_CATALOG_FAILED = Catalog 가 거절 (사유는 message, lastError)
     *
     * @param candidateId 수집 후보 ID
     * @param item 관리자가 검수한 수집 항목
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/crawl/catalog/{candidateId}/approve")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<CatalogCandidateView> approve(@PathVariable @Positive long candidateId, @RequestBody(required = false) ObjectNode item,
                                                     @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(crawlCatalogService.approve(candidateId, item, admin));
    }

    /**
     * 선택한 수집 후보를 승인하고 Catalog에 적재합니다.
     *
     * @param request 승인할 수집 후보 ID 목록
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/crawl/catalog/approve")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<List<ApproveResult>> approveMany(@Valid @RequestBody ApproveMany request,
                                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(crawlCatalogService.approveMany(request.ids(), admin));
    }

    /**
     * 적재하지 않음 - 같은 후보가 다시 와도 대기 목록에 올리지 않습니다
     *
     * @param candidateId 수집 후보 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/crawl/catalog/{candidateId}/reject")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<CatalogCandidateView> reject(@PathVariable @Positive long candidateId,
                                                    @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(crawlCatalogService.reject(candidateId, admin));
    }
}
