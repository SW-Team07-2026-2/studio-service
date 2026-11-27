package com.indie.studio.domain.cs.controller;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CS 관리자 화면 -> Studio -> Member, Concert, Catalog, Payment 내부 조회와 취소 API.
 * 회원 개인정보 조회도 관리자 작업 기록에 남깁니다.
 */
@RestController
public class CsController {

    private final ServiceClient serviceClient;

    public CsController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /**
     * Member에 회원 검색을 요청합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/cs/members")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> members(@RequestParam MultiValueMap<String, String> query,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.MEMBER, "/internal/admin/members", query, admin));
    }

    /**
     * Member에 회원 상세를 요청합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/cs/members/{userId}")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> member(@PathVariable @Positive long userId,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.MEMBER, "/internal/admin/members/" + userId, null, admin));
    }

    /**
     * Concert에 회원 예매 내역을 요청합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/cs/members/{userId}/bookings")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> bookings(@PathVariable @Positive long userId, @RequestParam MultiValueMap<String, String> query,
                                          @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/users/" + userId + "/bookings", query, admin));
    }

    /**
     * Catalog에 회원 주문 내역을 요청합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/cs/members/{userId}/orders")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> orders(@PathVariable @Positive long userId, @RequestParam MultiValueMap<String, String> query,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/internal/admin/catalog/users/" + userId + "/orders", query, admin));
    }

    /**
     * Payment에 회원 결제 내역을 요청합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/cs/members/{userId}/payments")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> payments(@PathVariable @Positive long userId, @RequestParam MultiValueMap<String, String> query,
                                          @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.PAYMENT, "/internal/admin/payments/users/" + userId, query, admin));
    }

    /**
     * Concert에 관리자 예매 취소를 요청합니다.
     *
     * @param bookingId 예매 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/cs/bookings/{bookingId}/cancel")
    @RequirePermission(Permission.CS_CANCEL)
    public ApiResponse<JsonNode> cancelBooking(@PathVariable @Positive long bookingId, @RequestBody JsonNode body,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/bookings/" + bookingId + "/cancel", body, admin));
    }

    /**
     * Catalog에 관리자 주문 취소를 요청합니다.
     *
     * @param orderId Catalog 주문 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/cs/orders/{orderId}/cancel")
    @RequirePermission(Permission.CS_CANCEL)
    public ApiResponse<JsonNode> cancelOrder(@PathVariable @Positive long orderId, @RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/orders/" + orderId + "/cancel", body, admin));
    }
}
