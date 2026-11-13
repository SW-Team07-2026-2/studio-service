package com.indie.studio.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.studio.domain.auth.service.Totp;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 앱 전체 + 진짜 MySQL 8.4 (Testcontainers · Flyway V1 + ddl-auto validate) + 가짜 AWS 서비스 · GPU 서버.
 * 🔴 Docker 가 꺼져 있으면 이 테스트들은 실패합니다 (건너뛰지 않음).
 * DB 는 테스트 사이에 비우지 않습니다 — 테스트마다 다른 아이디를 씁니다 (newLoginId).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    public static final String SHARED_SECRET = "test-internal-shared-secret-0123456789abcdef";
    protected static final String MASTER_ID = "master";
    protected static final String MASTER_PASSWORD = "correct-horse-battery";
    protected static final String PASSWORD = "sub-admin-password-1";
    protected static final StubServer STUB = new StubServer();

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("studio").withUsername("studio_app").withPassword("test-db-password");
    private static final Map<String, byte[]> OTP_SECRETS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static {
        MYSQL.start();
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("db.password", MYSQL::getPassword);
        registry.add("internal.shared-secret", () -> SHARED_SECRET);
        registry.add("totp.encryption-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");   // "0123456789abcdef" × 2
        registry.add("gpu.token", () -> "gpu-test-token");
        registry.add("bootstrap.master-password", () -> MASTER_PASSWORD);
        registry.add("app.bootstrap.master-login-id", () -> MASTER_ID);
        registry.add("app.login.bcrypt-strength", () -> "4");
        registry.add("app.services.concert", STUB::url);
        registry.add("app.services.catalog", STUB::url);
        registry.add("app.services.member", STUB::url);
        registry.add("app.services.payment", STUB::url);
        registry.add("app.gpu.base-url", STUB::url);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected MutableClock clock;

    /** 로그인한 관리자 — 세션 쿠키와 CSRF 토큰 */
    public record Session(Cookie cookie, String csrf, JsonNode me) {}

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    protected static String newLoginId(String prefix) {
        return prefix + "." + SEQUENCE.incrementAndGet();
    }

    /** 1차(아이디 · 비밀번호) → 2차(OTP · 처음이면 등록). 같은 OTP 코드를 두 번 쓰지 않도록 로그인마다 시계를 31초 넘깁니다 */
    protected Session login(String loginId, String password) throws Exception {
        clock.advance(Duration.ofSeconds(31));
        MvcResult first = mvc.perform(post("/api/studio/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", password)))).andExpect(status().isOk()).andReturn();
        JsonNode next = result(first);
        if ("OTP_ENROLL".equals(next.get("next").asText())) OTP_SECRETS.put(loginId, base32Decode(next.get("secret").asText()));
        MvcResult second = mvc.perform(post("/api/studio/auth/otp").cookie(first.getResponse().getCookie("STUDIO_2FA"))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", otp(loginId))))).andExpect(status().isOk()).andReturn();
        JsonNode me = result(second);
        return new Session(second.getResponse().getCookie("STUDIO_SESSION"), me.get("csrfToken").asText(), me);
    }

    protected Session loginMaster() throws Exception {
        return login(MASTER_ID, MASTER_PASSWORD);
    }

    /** 마스터가 계정을 만들고 그 계정으로 로그인까지 */
    protected Session newAdmin(String role) throws Exception {
        String loginId = newLoginId(role.toLowerCase());
        createAccount(loginMaster(), loginId, role);
        return login(loginId, PASSWORD);
    }

    protected long createAccount(Session master, String loginId, String role) throws Exception {
        MvcResult created = mvc.perform(as(master, post("/api/studio/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("loginId", loginId, "name", "테스트 " + role, "role", role, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return result(created).get("id").asLong();
    }

    /** 지금 시각의 OTP 코드 */
    protected String otp(String loginId) {
        return Totp.code(OTP_SECRETS.get(loginId), Totp.step(clock.instant()));
    }

    /** 세션 쿠키 + CSRF 헤더 */
    protected MockHttpServletRequestBuilder as(Session session, MockHttpServletRequestBuilder request) {
        return request.cookie(session.cookie()).header("X-CSRF-Token", session.csrf());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected JsonNode result(MvcResult result) throws Exception {
        return body(result).get("result");
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static byte[] base32Decode(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
