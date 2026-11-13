package com.indie.studio.global.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.common.exception.CommonErrorCode;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.common.filter.CorrelationIdFilter;
import com.indie.studio.global.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * AWS 서비스(Concert · Catalog · Member · Payment) 호출 — Studio 는 업무 DB 에 직접 붙지 않고 전부 여기로 읽고 씁니다.
 * KT 노드에서 WireGuard 터널을 건너가므로 느릴 수 있습니다 (제한 시간 app.services.read-timeout-ms).
 * <pre>
 * 붙이는 헤더  X-Internal-Token (iss=studio · aud=대상) · X-Admin-Id (누가 시켰나 — 대상 서비스의 기록용) · X-Correlation-Id
 * 성공        ApiResponse 의 result 만 꺼내 돌려줍니다
 * 4xx         대상 서비스의 상태 · code · message 를 그대로 (UpstreamError)
 * 401 · 403   Studio 의 내부 토큰 문제 → 502 UPSTREAM_ERROR. 🔴 401 로 내보내면 화면이 관리자를 로그아웃시킵니다
 * 5xx         502 UPSTREAM_ERROR  ·  연결 실패 · 시간 초과 → 503 UPSTREAM_UNAVAILABLE
 * </pre>
 */
@Component
public class ServiceClient {

    public enum Target { CONCERT, CATALOG, MEMBER, PAYMENT }

    public static final String ADMIN_HEADER = "X-Admin-Id";
    public static final String TOKEN_HEADER = "X-Internal-Token";
    private static final Logger log = LoggerFactory.getLogger(ServiceClient.class);
    private static final MultiValueMap<String, String> NO_QUERY = new LinkedMultiValueMap<>();

    private final Map<Target, RestClient> clients = new EnumMap<>(Target.class);
    private final InternalTokenIssuer tokenIssuer;
    private final ObjectMapper objectMapper;

    public ServiceClient(StudioProperties props, InternalTokenIssuer tokenIssuer, ObjectMapper objectMapper) {
        StudioProperties.Services s = props.services();
        clients.put(Target.CONCERT, build(s.concert(), s));
        clients.put(Target.CATALOG, build(s.catalog(), s));
        clients.put(Target.MEMBER, build(s.member(), s));
        clients.put(Target.PAYMENT, build(s.payment(), s));
        this.tokenIssuer = tokenIssuer;
        this.objectMapper = objectMapper;
    }

    public JsonNode get(Target target, String path, MultiValueMap<String, String> query, AdminPrincipal admin) {
        return call(target, HttpMethod.GET, path, query, null, admin);
    }

    public JsonNode send(Target target, HttpMethod method, String path, Object body, AdminPrincipal admin) {
        return call(target, method, path, null, body, admin);
    }

    private JsonNode call(Target target, HttpMethod method, String path, MultiValueMap<String, String> query, Object body,
                          AdminPrincipal admin) {
        RestClient.RequestBodySpec request = clients.get(target).method(method)
                .uri(b -> b.path(path).queryParams(query == null ? NO_QUERY : query).build())
                .header(TOKEN_HEADER, tokenIssuer.issue(target.name().toLowerCase()))
                .header(ADMIN_HEADER, String.valueOf(admin.id()));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) request.header(CorrelationIdFilter.HEADER, correlationId);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        try {
            return request.exchange((req, res) -> read(target, method, path, res));
        } catch (ResourceAccessException e) {
            log.warn("upstream_unreachable target={} method={} path={} error={}", target, method, path, e.getMessage());
            throw new GeneralException(CommonErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    private JsonNode read(Target target, HttpMethod method, String path, ClientHttpResponse res) throws java.io.IOException {
        int status = res.getStatusCode().value();
        JsonNode body = null;
        try {
            body = objectMapper.readTree(res.getBody());
        } catch (Exception ignored) {
            // 본문이 JSON 이 아님 (프록시 오류 페이지 등) — 상태 코드만으로 판단합니다
        }
        if (status < 300) {
            JsonNode result = body == null ? null : body.get("result");
            return result == null || result.isNull() ? null : result;
        }
        log.warn("upstream_error target={} method={} path={} status={} code={}", target, method, path, status,
                body == null ? null : body.path("code").asText(null));
        if (status == 401 || status == 403 || status >= 500) throw new GeneralException(CommonErrorCode.UPSTREAM_ERROR);

        String code = body == null ? null : body.path("code").asText(null);
        String message = body == null ? null : body.path("message").asText(null);
        GeneralException e = new GeneralException(new UpstreamError(HttpStatus.valueOf(status),
                code == null ? "UPSTREAM_" + status : code, message == null ? CommonErrorCode.INVALID_REQUEST.getMessage() : message));
        if (body != null && body.path("result").isObject()) {
            body.get("result").fields().forEachRemaining(f -> e.with(f.getKey(), f.getValue()));
        }
        throw e;
    }

    private static RestClient build(String baseUrl, StudioProperties.Services s) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(s.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(s.readTimeoutMs()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
