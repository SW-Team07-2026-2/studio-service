package com.indie.studio.domain.auth.service;

import com.indie.studio.global.config.StudioProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 쿠키 두 개 - 둘 다 HttpOnly 라 화면 스크립트가 읽지 못합니다.
 * STUDIO_2FA (1차 통과 ~ OTP, /api/studio/auth 에만), STUDIO_SESSION (로그인 세션, /api/studio 에만).
 */
@Component
public class SessionCookies {

    public static final String SESSION = "STUDIO_SESSION";
    public static final String CHALLENGE = "STUDIO_2FA";
    private static final String SESSION_PATH = "/api/studio";
    private static final String CHALLENGE_PATH = "/api/studio/auth";

    private final StudioProperties props;

    public SessionCookies(StudioProperties props) {
        this.props = props;
    }

    /**
     * HttpOnly 관리자 세션 쿠키를 생성합니다.
     *
     * @param token 관리자 세션 토큰
     */
    public String session(String token) {
        return cookie(SESSION, token, SESSION_PATH, Duration.ofHours(props.session().ttlHours()));
    }

    /** 관리자 세션을 삭제할 만료 쿠키를 생성합니다. */
    public String clearSession() {
        return cookie(SESSION, "", SESSION_PATH, Duration.ZERO);
    }

    /**
     * OTP 인증 대기 쿠키를 생성합니다.
     *
     * @param token OTP 대기 토큰
     */
    public String challenge(String token) {
        return cookie(CHALLENGE, token, CHALLENGE_PATH, Duration.ofMinutes(props.login().challengeMinutes()));
    }

    /** OTP 대기를 삭제할 만료 쿠키를 생성합니다. */
    public String clearChallenge() {
        return cookie(CHALLENGE, "", CHALLENGE_PATH, Duration.ZERO);
    }

    private String cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(props.session().cookieSecure())
                .sameSite(props.session().cookieSameSite()).path(path).maxAge(maxAge).build().toString();
    }
}
