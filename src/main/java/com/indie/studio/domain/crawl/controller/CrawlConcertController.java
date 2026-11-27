package com.indie.studio.domain.crawl.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indie.studio.domain.crawl.dto.CrawlDtos.CandidateView;
import com.indie.studio.domain.crawl.entity.CrawlConcertEntity;
import com.indie.studio.domain.crawl.service.CrawlConcertService;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.common.response.ApiResponse;
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

/** 공연 관리자 화면 -> Studio 후보 검수 -> Concert 내부 등록 API. */
@RestController
public class CrawlConcertController {

    private final CrawlConcertService crawlConcertService;

    public CrawlConcertController(CrawlConcertService crawlConcertService) {
        this.crawlConcertService = crawlConcertService;
    }

    /**
     * ?status=PENDING|APPROVED|REJECTED|CHANGED (기본 PENDING) &beforeId= &size= - 최신순. CHANGED 는 등록한 공연이 출처에서 바뀐 것 (previous = 전 값)
     *
     * @param status 조회할 후보 상태
     * @param beforeId 이 ID보다 이전 항목부터 조회
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/studio/crawl/concerts")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<List<CandidateView>> list(@RequestParam(defaultValue = "PENDING") CrawlConcertEntity.Status status,
                                                 @RequestParam(required = false) @Positive Long beforeId,
                                                 @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return ApiResponse.onSuccess(crawlConcertService.list(status, beforeId, size));
    }

    /**
     * 등록 - 본문은 POST /api/studio/concerts 와 같습니다 (후보 값을 화면에서 채우고 공연장, 라인업 id 등을 보완). source, externalId 는 Studio 가 넣습니다
     *
     * @param candidateId 수집 후보 ID
     * @param concert 검수한 공연 등록 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/crawl/concerts/{candidateId}/approve")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> approve(@PathVariable @Positive long candidateId, @RequestBody ObjectNode concert,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(crawlConcertService.approve(candidateId, concert, admin));
    }

    /**
     * 바뀐 공연 확인 - 공연 수정(PATCH /api/studio/concerts/{concertId}), 변경 공지를 한 뒤 누릅니다. 다음 변경은 지금 값과 비교
     *
     * @param candidateId 수집 후보 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/crawl/concerts/{candidateId}/change-checked")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<CandidateView> changeChecked(@PathVariable @Positive long candidateId,
                                                    @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(crawlConcertService.changeChecked(candidateId, admin));
    }

    /**
     * 등록하지 않음 - 같은 후보가 다시 와도 대기 목록에 다시 올리지 않습니다
     *
     * @param candidateId 수집 후보 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/crawl/concerts/{candidateId}/reject")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<CandidateView> reject(@PathVariable @Positive long candidateId,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(crawlConcertService.reject(candidateId, admin));
    }
}
