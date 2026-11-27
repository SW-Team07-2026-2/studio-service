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
 * 관리자 화면 -> Studio 아이디, 비밀번호와 메일 OTP 인증 -> Redis 세션 발급.
 * 사용자용 Member 토큰은 관리자 인증에 사용할 수 없습니다.
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final SessionCookies cookies;

    public AuthController(AuthService authService, SessionCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    /**
     * 1차 - 아이디, 비밀번호. 통과하면 인증 코드 메일을 보냅니다. 아직 로그인이 아닙니다
     *
     * @param request 관리자 로그인 ID와 비밀번호
     * @param http 현재 HTTP 요청
     */
    @PostMapping("/api/studio/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.LoginResult result = authService.login(request.loginId(), request.password(), ClientIp.of(http));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.challenge(result.challengeToken()))
                .body(ApiResponse.onSuccess(new LoginResponse("OTP", result.sentTo())));
    }

    /**
     * 2차 - 메일로 받은 인증 코드 6자리. 통과하면 세션 쿠키가 내려가고 대시보드에 들어갈 수 있습니다
     *
     * @param request 입력한 메일 인증 코드
     * @param challenge OTP 대기 쿠키 값
     * @param http 현재 HTTP 요청
     */
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

    /**
     * 관리자 세션을 폐기합니다.
     *
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/auth/logout")
    @RequirePermission(Permission.SELF)
    public ResponseEntity<ApiResponse<Void>> logout(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        authService.logout(admin);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clearSession()).body(ApiResponse.onSuccess(null));
    }

    /**
     * 대시보드가 처음 부르는 API - 내 역할의 탭(menus)과 CSRF 토큰
     *
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/me")
    @RequirePermission(Permission.SELF)
    public ApiResponse<MeResponse> me(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(MeResponse.of(admin));
    }

    /**
     * 현재 관리자 비밀번호를 확인하고 새 비밀번호를 저장합니다.
     *
     * @param request 현재 비밀번호와 새 비밀번호
     * @param admin 요청한 관리자와 권한 정보
     */
    @PutMapping("/api/studio/me/password")
    @RequirePermission(Permission.SELF)
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                            @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        authService.changePassword(admin, request.currentPassword(), request.newPassword());
        return ApiResponse.onSuccess(null);
    }
}
