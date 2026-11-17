package com.indie.studio.domain.auth.dto;

import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Menu;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 32) String loginId, @NotBlank @Size(max = 128) String password) {}

    /**
     * 1차 통과 — 계정에 등록된 메일로 인증 코드 6자리를 보냈습니다. next 는 항상 OTP, sentTo 는 받는 주소(가림 처리 · 화면 안내용).
     * 아직 로그인된 것이 아닙니다. 2단계 대기 쿠키(STUDIO_2FA · 5분)만 내려갑니다. 코드를 못 받았으면 1차부터 다시 (새 코드 발송).
     */
    public record LoginResponse(String next, List<String> sentTo) {}

    public record OtpRequest(@NotNull @Pattern(regexp = "\\d{6}") String code) {}

    public record PasswordChangeRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {}

    /**
     * 내 계정 — menus 가 대시보드에 그릴 탭 전부입니다 (다른 역할의 탭은 목록에 없습니다).
     * csrfToken 은 상태를 바꾸는 요청(POST · PUT · PATCH · DELETE)의 X-CSRF-Token 헤더에 넣습니다.
     */
    public record MeResponse(long id, String loginId, String name, Role role, List<Menu.Item> menus,
                             Set<Permission> permissions, String csrfToken) {
        public static MeResponse of(AdminPrincipal admin) {
            return new MeResponse(admin.id(), admin.loginId(), admin.name(), admin.role(), Menu.of(admin.role()),
                    new TreeSet<>(admin.role().permissions()), admin.csrfToken());
        }
    }
}
