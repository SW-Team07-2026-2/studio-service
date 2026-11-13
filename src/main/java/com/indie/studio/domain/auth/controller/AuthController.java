package com.indie.studio.domain.auth.controller;

import com.indie.studio.domain.auth.dto.AuthDtos.LoginRequest;
import com.indie.studio.domain.auth.dto.AuthDtos.LoginResponse;
import com.indie.studio.domain.auth.dto.AuthDtos.MeResponse;
import com.indie.studio.domain.auth.dto.AuthDtos.OtpRequest;
import com.indie.studio.domain.auth.dto.AuthDtos.PasswordChangeRequest;
import com.indie.studio.domain.auth.service.AuthService;
import com.indie.studio.domain.auth.service.SessionCookies;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.common.response.ApiResponse;
import com.indie.studio.global.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 · 내 계정. login · otp 두 개만 로그인 없이 부를 수 있습니다 (SessionFilter.PUBLIC_PATHS).
 * 소셜 로그인으로는 관리자가 될 수 없고, 사용자 서비스(member)의 토큰은 여기서 통하지 않습니다.
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final SessionCookies cookies;

    public AuthController(AuthService authService, SessionCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    /** 1차 — 아이디 · 비밀번호. 통과해도 아직 로그인이 아닙니다 */
    @PostMapping("/api/studio/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.LoginResult result = authService.login(request.loginId(), request.password(), ClientIp.of(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.challenge(result.challengeToken()))
                .body(ApiResponse.onSuccess(new LoginResponse(result.next(), result.otpauthUri(), result.secret())));
    }

    /** 2차 — OTP 6자리. 통과하면 세션 쿠키가 내려가고 대시보드에 들어갈 수 있습니다 */
    @PostMapping("/api/studio/auth/otp")
    public ResponseEntity<ApiResponse<MeResponse>> otp(@Valid @RequestBody OtpRequest request,
                                                       @CookieValue(name = SessionCookies.CHALLENGE, required = false) String challenge,
                                                       HttpServletRequest http) {
        AuthService.SessionIssued issued = authService.verifyOtp(challenge, request.code(), ClientIp.of(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.session(issued.sessionToken()))
                .header(HttpHeaders.SET_COOKIE, cookies.clearChallenge())
                .body(ApiResponse.onSuccess(MeResponse.of(issued.admin())));
    }

    @PostMapping("/api/studio/auth/logout")
    @RequirePermission(Permission.SELF)
    public ResponseEntity<ApiResponse<Void>> logout(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        authService.logout(admin);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clearSession()).body(ApiResponse.onSuccess(null));
    }

    /** 대시보드가 처음 부르는 API — 내 역할의 탭(menus)과 CSRF 토큰 */
    @GetMapping("/api/studio/me")
    @RequirePermission(Permission.SELF)
    public ApiResponse<MeResponse> me(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(MeResponse.of(admin));
    }

    @PutMapping("/api/studio/me/password")
    @RequirePermission(Permission.SELF)
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                            @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        authService.changePassword(admin, request.currentPassword(), request.newPassword());
        return ApiResponse.onSuccess(null);
    }
}
