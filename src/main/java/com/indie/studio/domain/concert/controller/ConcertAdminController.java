package com.indie.studio.domain.concert.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공연 관리자 — 공연 상세 · 약관 · 이미지 · 요구사항 · 라인업을 정해 Concert 로 보내고, 공연의 좌석별 가격을 정합니다.
 * 본문은 Concert 내부 API 의 것 그대로 넘깁니다 (검증은 Concert). 공연장을 고르고 좌석 가격을 매기는 데 필요한 조회만
 * /options 아래에 따로 뒀습니다 — 공연 관리자는 공연장을 읽기만 하고, 공연장 관리 API(/api/studio/venues)는 볼 수 없습니다.
 */
@RestController
public class ConcertAdminController {

    private final ServiceClient serviceClient;

    public ConcertAdminController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /** ?from=&to=&region=&genre=&type=&artistId=&page=&size= */
    @GetMapping("/api/studio/concerts")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> list(@RequestParam MultiValueMap<String, String> query,
                                      @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts", query, admin));
    }

    @GetMapping("/api/studio/concerts/{id}")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> get(@PathVariable @Positive long id,
                                     @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/" + id, null, admin));
    }

    /** 공연 등록 — venueId · title · type · startsAt · maxSeatsPerUser · artistIds … + description · terms · requirements · posterUrl · imageUrls */
    @PostMapping("/api/studio/concerts")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> create(@RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts", body, admin));
    }

    /** 공연 수정 — 보낸 값만 바뀝니다 */
    @PatchMapping("/api/studio/concerts/{id}")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> update(@PathVariable @Positive long id, @RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.PATCH, "/internal/admin/concerts/" + id, body, admin));
    }

    /** 라인업 전체 교체 — { artistIds: [...] } (첫 번째 = 헤드라이너) */
    @PutMapping("/api/studio/concerts/{id}/lineup")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> lineup(@PathVariable @Positive long id, @RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.PUT, "/internal/admin/concerts/" + id + "/lineup", body, admin));
    }

    /** 이 공연에 지금 매겨진 좌석 · 가격 · 판매 상태 */
    @GetMapping("/api/studio/concerts/{id}/seats")
    @RequirePermission(Permission.SEAT_PRICE)
    public ApiResponse<JsonNode> seats(@PathVariable @Positive long id,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/" + id + "/seats", null, admin));
    }

    /** 좌석별 가격 — { seats: [{ seatId, price }] }. seatId 는 공연장 배치 좌석 id (아래 layout 에서 받음). 목록에 없는 좌석은 팔지 않습니다 */
    @PostMapping("/api/studio/concerts/{id}/seats")
    @RequirePermission(Permission.SEAT_PRICE)
    public ApiResponse<JsonNode> priceSeats(@PathVariable @Positive long id, @RequestBody JsonNode body,
                                            @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/" + id + "/seats", body, admin));
    }

    /** 공연장 고르기 — ?q=&region= */
    @GetMapping("/api/studio/concerts/options/venues")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> venueOptions(@RequestParam MultiValueMap<String, String> query,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/venues", query, admin));
    }

    /** 그 공연장의 배치 (층 → 구역 → 좌석 id) — 좌석별 가격을 매길 때 씁니다 */
    @GetMapping("/api/studio/concerts/options/venues/{venueId}/layout")
    @RequirePermission(Permission.SEAT_PRICE)
    public ApiResponse<JsonNode> venueLayout(@PathVariable @Positive long venueId,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/venues/" + venueId + "/layout", null, admin));
    }

    /** 라인업에 넣을 아티스트 찾기 — ?q=&genre=&page=&size= */
    @GetMapping("/api/studio/concerts/options/artists")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> artistOptions(@RequestParam MultiValueMap<String, String> query,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/artists", query, admin));
    }
}
