package com.indie.studio.domain.checkin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 검표 관리자 — 공연장 입구에서 회원의 입장 QR 을 찍어 Concert 에 입장을 기록합니다.
 * 공연 목록도 검표 탭 아래의 경로로만 냅니다 — 검표 관리자는 공연 탭 API(/api/studio/concerts)를 볼 수 없습니다.
 */
@RestController
public class CheckInController {

    /** 화면이 보내는 값은 찍은 QR 뿐입니다 */
    public record CheckInRequest(@NotBlank @Size(max = 1000) String token) {}

    private final ServiceClient serviceClient;

    public CheckInController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /** 지금 검표할 수 있는 공연 */
    @GetMapping("/api/studio/check-in/concerts")
    @RequirePermission(Permission.CHECK_IN)
    public ApiResponse<JsonNode> concerts(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/check-in", null, admin));
    }

    /**
     * 입장 처리 — { token } → { bookingId, seats[], checkedInAt }. 이미 입장한 QR 은 409 ALREADY_CHECKED_IN (+ checkedInAt).
     * 🔴 검표자(checkerId)는 세션의 관리자 id 로 채웁니다 — 화면이 보낸 값을 쓰지 않습니다.
     */
    @PostMapping("/api/studio/check-in/concerts/{id}")
    @RequirePermission(Permission.CHECK_IN)
    public ApiResponse<JsonNode> checkIn(@PathVariable @Positive long id, @Valid @RequestBody CheckInRequest request,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/" + id + "/check-in",
                Map.of("token", request.token(), "checkerId", admin.id()), admin));
    }
}
