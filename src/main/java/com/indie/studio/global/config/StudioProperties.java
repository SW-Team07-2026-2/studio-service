package com.indie.studio.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.yml 의 app.* */
@ConfigurationProperties(prefix = "app")
public record StudioProperties(Session session, Login login, Totp totp, Bootstrap bootstrap, InternalToken internalToken,
                               Services services, Gpu gpu, Cors cors) {

    public record Session(int ttlHours, int idleMinutes, boolean cookieSecure, String cookieSameSite) {}

    public record Login(int challengeMinutes, int maxFailures, int lockMinutes, int bcryptStrength) {}

    public record Totp(String issuer) {}

    public record Bootstrap(String masterLoginId, String masterName) {}

    public record InternalToken(String issuer, long ttlSeconds) {}

    public record Services(String concert, String catalog, String member, String payment,
                           int connectTimeoutMs, int readTimeoutMs) {}

    public record Gpu(String baseUrl, int connectTimeoutMs, int readTimeoutMs, long maxImageBytes) {}

    public record Cors(List<String> allowedOrigins) {}
}
