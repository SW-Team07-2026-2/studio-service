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
 * CS 관리자 — 회원의 티켓팅(예매)과 굿즈 · 피지컬 앨범 구매(주문 · 결제) 문의를 봅니다.
 * 회원은 Member, 예매는 Concert, 주문은 Catalog, 결제는 Payment 가 주인입니다. 전부 각 서비스의 내부 관리자 API 로 읽습니다.
 * 🔴 회원 개인정보를 보는 화면이라 조회도 작업 기록에 남습니다 (경로의 회원 id 까지 · 검색어는 남기지 않음).
 */
@RestController
public class CsController {

    private final ServiceClient serviceClient;

    public CsController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /** 회원 찾기 — ?q=(회원 id · 닉네임 · 이메일)&page=&size= */
    @GetMapping("/api/studio/cs/members")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> members(@RequestParam MultiValueMap<String, String> query,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.MEMBER, "/internal/admin/members", query, admin));
    }

    @GetMapping("/api/studio/cs/members/{userId}")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> member(@PathVariable @Positive long userId,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.MEMBER, "/internal/admin/members/" + userId, null, admin));
    }

    /** 그 회원의 예매 (티켓팅) — ?page=&size= */
    @GetMapping("/api/studio/cs/members/{userId}/bookings")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> bookings(@PathVariable @Positive long userId, @RequestParam MultiValueMap<String, String> query,
                                          @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/users/" + userId + "/bookings", query, admin));
    }

    /** 그 회원의 굿즈 · 피지컬 앨범 주문 — ?page=&size= */
    @GetMapping("/api/studio/cs/members/{userId}/orders")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> orders(@PathVariable @Positive long userId, @RequestParam MultiValueMap<String, String> query,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/internal/admin/catalog/users/" + userId + "/orders", query, admin));
    }

    /** 그 회원의 결제 (티켓 · 상품) — 카드 번호 같은 결제 수단 정보는 없습니다 */
    @GetMapping("/api/studio/cs/members/{userId}/payments")
    @RequirePermission(Permission.CS_READ)
    public ApiResponse<JsonNode> payments(@PathVariable @Positive long userId, @RequestParam MultiValueMap<String, String> query,
                                          @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.PAYMENT, "/internal/admin/payments/users/" + userId, query, admin));
    }

    /** 예매 취소 대행 — { reason }. 환불 여부 · 금액은 Concert · Payment 의 규칙을 따릅니다 */
    @PostMapping("/api/studio/cs/bookings/{bookingId}/cancel")
    @RequirePermission(Permission.CS_CANCEL)
    public ApiResponse<JsonNode> cancelBooking(@PathVariable @Positive long bookingId, @RequestBody JsonNode body,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/bookings/" + bookingId + "/cancel", body, admin));
    }

    /** 주문 취소 대행 — { reason } */
    @PostMapping("/api/studio/cs/orders/{orderId}/cancel")
    @RequirePermission(Permission.CS_CANCEL)
    public ApiResponse<JsonNode> cancelOrder(@PathVariable @Positive long orderId, @RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/orders/" + orderId + "/cancel", body, admin));
    }
}
