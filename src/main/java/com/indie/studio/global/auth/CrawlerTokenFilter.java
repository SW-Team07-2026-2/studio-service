package com.indie.studio.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.studio.global.common.exception.CommonErrorCode;
import com.indie.studio.global.common.response.ApiResponse;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;

/**
 * /internal/crawl/** — 같은 KT compose 안의 크롤러(Python)가 부르는 경로 (11/17 결정 A1). 관리자 세션이 아니라 X-Internal-Token 으로 받습니다.
 * {iss: crawler, aud: studio, exp} HS256 · 시계 오차 30초. 시크릿은 configtree `crawler.shared-secret` —
 * 🔴 서비스 공유 시크릿(internal.shared-secret)과 다른 값입니다. 같으면 크롤러가 iss=studio 토큰을 만들어 Studio 를 건너뛸 수 있습니다.
 * 시크릿 파일이 없으면 이 경로는 전부 401 입니다. KT 밖에서는 닿지 않습니다 — admin.indig.co.kr 은 Cloudflare WAF ④ 가 /api/studio/ 밖을 막습니다 (manifests-kt README).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)   // SessionFilter 와 같은 자리 — 서로 다른 경로만 봅니다
public class CrawlerTokenFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/internal/crawl/";
    public static final String HEADER = "X-Internal-Token";
    private static final String ISSUER = "crawler";
    private static final String AUDIENCE = "studio";
    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final Logger log = LoggerFactory.getLogger(CrawlerTokenFilter.class);

    private final MACVerifier verifier;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public CrawlerTokenFilter(@Value("${crawler.shared-secret:}") String secret, Clock clock, ObjectMapper objectMapper)
            throws JOSEException {
        this.verifier = secret.isBlank() ? null : new MACVerifier(secret.trim().getBytes(StandardCharsets.UTF_8));   // 256bit 미만이면 기동 실패
        this.clock = clock;
        this.objectMapper = objectMapper;
        if (verifier == null) log.warn("crawler_secret_missing — /internal/crawl/** 는 전부 401");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String reason = reject(request.getHeader(HEADER));
        if (reason != null) {
            log.warn("crawler_token_rejected reason={} path={}", reason, request.getRequestURI());
            CommonErrorCode code = CommonErrorCode.UNAUTHENTICATED;
            response.setStatus(code.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.onFailure(code, code.getMessage(), null));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 통과면 null, 아니면 거절 사유 (로그용) */
    private String reject(String token) {
        if (verifier == null) return "secret_missing";
        if (token == null || token.isBlank()) return "missing";
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) return "alg";
            if (!jwt.verify(verifier)) return "signature";
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) return "issuer";
            if (claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)) return "audience";
            Date exp = claims.getExpirationTime();
            if (exp == null) return "exp";
            return clock.instant().isBefore(exp.toInstant().plusSeconds(CLOCK_SKEW_SECONDS)) ? null : "expired";
        } catch (Exception e) {
            return "parse";
        }
    }
}
