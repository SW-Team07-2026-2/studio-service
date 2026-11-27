package com.indie.studio.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.yml 의 app.* */
@ConfigurationProperties(prefix = "app")
public record StudioProperties(Session session, Login login, InternalToken internalToken,
                               Services services, Gpu gpu, Cors cors, Image image) {

    public record Session(int ttlHours, int idleMinutes, boolean cookieSecure, String cookieSameSite) {}

    public record Login(int challengeMinutes, int maxFailures, int lockMinutes, int bcryptStrength, String mailFrom) {}

    public record InternalToken(String issuer, long ttlSeconds) {}

    public record Services(String concert, String catalog, String member, String payment,
                           int connectTimeoutMs, int readTimeoutMs) {}

    public record Gpu(String baseUrl, int connectTimeoutMs, int readTimeoutMs, long maxImageBytes) {}

    public record Cors(List<String> allowedOrigins) {}

    /** 공개 이미지 저장소 - bucket 이 비면 업로드, 이미지 저장을 막습니다. endpoint 는 로컬 MinIO 만 */
    public record Image(String bucket, String region, String publicBaseUrl, String endpoint, long maxBytes, int uploadTtlSeconds,
                        List<String> importHosts) {}
}
