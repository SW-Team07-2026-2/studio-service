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

/** 검표 관리자 화면 -> Studio -> Concert 내부 API로 공연 조회와 QR 입장 처리를 요청합니다. */
@RestController
public class CheckInController {

    /** 화면이 보내는 값은 찍은 QR 뿐입니다 */
    public record CheckInRequest(@NotBlank @Size(max = 1000) String token) {}

    private final ServiceClient serviceClient;

    public CheckInController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /**
     * Concert에서 검표 가능한 공연을 조회합니다.
     *
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/check-in/concerts")
    @RequirePermission(Permission.CHECK_IN)
    public ApiResponse<JsonNode> concerts(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/check-in", null, admin));
    }

    /**
     * Concert에 QR 입장 처리를 요청합니다.
     *
     * @param concertId 공연 ID
     * @param request 입장 QR 토큰과 검표자 정보
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/check-in/concerts/{concertId}")
    @RequirePermission(Permission.CHECK_IN)
    public ApiResponse<JsonNode> checkIn(@PathVariable @Positive long concertId, @Valid @RequestBody CheckInRequest request,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/" + concertId + "/check-in",
                Map.of("token", request.token(), "checkerId", admin.id()), admin));
    }
}
