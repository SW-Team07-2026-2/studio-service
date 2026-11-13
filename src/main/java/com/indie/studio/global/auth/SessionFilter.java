package com.indie.studio.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.studio.domain.auth.service.AuthService;
import com.indie.studio.domain.auth.service.SessionCookies;
import com.indie.studio.global.common.exception.CommonErrorCode;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * /api/studio/** 는 로그인(1차 + OTP)을 마친 세션만 지나갑니다. 로그인 두 경로만 예외입니다.
 * 🔴 라우팅보다 앞에서 막습니다 — 로그인하지 않은 사람에게는 있는 경로든 없는 경로든 똑같이 401 이라 어떤 API 가 있는지 알 수 없습니다.
 * 역할별 권한은 그다음 PermissionInterceptor 가 봅니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)   // CorrelationIdFilter(+0) · CorsFilter(+5) 다음
public class SessionFilter extends OncePerRequestFilter {

    public static final Set<String> PUBLIC_PATHS = Set.of("/api/studio/auth/login", "/api/studio/auth/otp");
    private static final String PREFIX = "/api/studio/";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public SessionFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(PREFIX) || PUBLIC_PATHS.contains(path) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AdminPrincipal admin = authService.authenticate(cookie(request));
        if (admin == null) {
            CommonErrorCode code = CommonErrorCode.UNAUTHENTICATED;
            response.setStatus(code.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.onFailure(code, code.getMessage(), null));
            return;
        }
        request.setAttribute(AdminPrincipal.ATTRIBUTE, admin);
        chain.doFilter(request, response);
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (SessionCookies.SESSION.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
