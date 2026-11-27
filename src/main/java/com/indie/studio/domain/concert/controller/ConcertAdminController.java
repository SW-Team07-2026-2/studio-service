package com.indie.studio.domain.concert.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.image.ImageGuard;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 공연 관리자 화면 -> Studio -> Concert 내부 관리자 API.
 * 공연, 공지, 라인업과 좌석 가격을 관리하고 선택용 아티스트는 Catalog에서 조회합니다.
 */
@RestController
public class ConcertAdminController {

    private final ServiceClient serviceClient;
    private final ImageGuard images;

    public ConcertAdminController(ServiceClient serviceClient, ImageGuard images) {
        this.serviceClient = serviceClient;
        this.images = images;
    }

    /**
     * ?from=&to=&region=&genre=&type=&artistId=&page=&size=
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> list(@RequestParam MultiValueMap<String, String> query,
                                      @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts", query, admin));
    }

    /**
     * ConcertAdmin 상세를 조회합니다.
     *
     * @param concertId 공연 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts/{concertId}")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> get(@PathVariable @Positive long concertId,
                                     @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/" + concertId, null, admin));
    }

    /**
     * 공연 등록 - venueId, title, type, startsAt, maxSeatsPerUser, artistIds ... + description, terms, requirements, posterUrl, imageUrls
     *
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/concerts")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> create(@RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "posterUrl", "imageUrls");
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts", body, admin));
    }

    /**
     * 공연 수정 - 보낸 값만 바뀝니다
     *
     * @param concertId 공연 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PatchMapping("/api/studio/concerts/{concertId}")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> update(@PathVariable @Positive long concertId, @RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "posterUrl", "imageUrls");
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.PATCH, "/internal/admin/concerts/" + concertId, body, admin));
    }

    // ── 공연 변경 공지 - 새 공지는 라인업 팔로워 + 확정 예매자에게 알림, 고치기, 지우기는 알림 없음 ──

    /**
     * ConcertAdmin 공지 목록을 조회합니다.
     *
     * @param concertId 공연 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts/{concertId}/notices")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> notices(@PathVariable @Positive long concertId,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/" + concertId + "/notices", null, admin));
    }

    /**
     * { kind: SCHEDULE|VENUE|LINEUP|BOOKING|CANCEL|ETC, title, body } - 공연 값(일정 등)은 공연 수정으로 따로 바꿉니다
     *
     * @param concertId 공연 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/concerts/{concertId}/notices")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> createNotice(@PathVariable @Positive long concertId, @RequestBody JsonNode body,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/" + concertId + "/notices", body, admin));
    }

    /**
     * ConcertAdmin 공지를 수정합니다.
     *
     * @param concertId 공연 ID
     * @param noticeId 공연 공지 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PatchMapping("/api/studio/concerts/{concertId}/notices/{noticeId}")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> updateNotice(@PathVariable @Positive long concertId, @PathVariable @Positive long noticeId, @RequestBody JsonNode body,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.PATCH,
                "/internal/admin/concerts/" + concertId + "/notices/" + noticeId, body, admin));
    }

    /**
     * ConcertAdmin 공지를 삭제합니다.
     *
     * @param concertId 공연 ID
     * @param noticeId 공연 공지 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @DeleteMapping("/api/studio/concerts/{concertId}/notices/{noticeId}")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> deleteNotice(@PathVariable @Positive long concertId, @PathVariable @Positive long noticeId,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.DELETE,
                "/internal/admin/concerts/" + concertId + "/notices/" + noticeId, null, admin));
    }

    /**
     * 공연 라인업을 변경합니다.
     *
     * @param concertId 공연 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PutMapping("/api/studio/concerts/{concertId}/lineup")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> lineup(@PathVariable @Positive long concertId, @RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.PUT, "/internal/admin/concerts/" + concertId + "/lineup", body, admin));
    }

    /**
     * 이 공연에 지금 매겨진 좌석, 가격, 판매 상태 - ?sessionId= (회차가 둘 이상이면 필수, 하나면 생략 가능) 를 그대로 넘깁니다
     *
     * @param concertId 공연 ID
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts/{concertId}/seats")
    @RequirePermission(Permission.SEAT_PRICE)
    public ApiResponse<JsonNode> seats(@PathVariable @Positive long concertId, @RequestParam MultiValueMap<String, String> query,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/" + concertId + "/seats", query, admin));
    }

    /**
     * 공연 판매 좌석과 가격을 설정합니다.
     *
     * @param concertId 공연 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/concerts/{concertId}/seats")
    @RequirePermission(Permission.SEAT_PRICE)
    public ApiResponse<JsonNode> priceSeats(@PathVariable @Positive long concertId, @RequestBody JsonNode body,
                                            @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/" + concertId + "/seats", body, admin));
    }

    /**
     * 공연 등록에 사용할 공연장을 조회합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts/options/venues")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> venueOptions(@RequestParam MultiValueMap<String, String> query,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/venues", query, admin));
    }

    /**
     * 공연장의 좌석 배치를 조회합니다.
     *
     * @param venueId 공연장 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts/options/venues/{venueId}/layout")
    @RequirePermission(Permission.SEAT_PRICE)
    public ApiResponse<JsonNode> venueLayout(@PathVariable @Positive long venueId,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/venues/" + venueId + "/layout", null, admin));
    }

    /**
     * Catalog에서 라인업 후보 아티스트를 조회합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/concerts/options/artists")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<JsonNode> artistOptions(@RequestParam MultiValueMap<String, String> query,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/artists", query, admin));
    }
}
