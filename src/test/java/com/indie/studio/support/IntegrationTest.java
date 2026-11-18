package com.indie.studio.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 앱 전체 + 진짜 MySQL 8.4 (Testcontainers · Flyway V1 + ddl-auto validate) + 진짜 Redis 7.4 (비밀번호 있음) + 가짜 AWS 서비스 · GPU 서버 · 메일 서버(MailBox).
 * 🔴 Docker 가 꺼져 있으면 이 테스트들은 실패합니다 (건너뛰지 않음).
 * DB 는 테스트 사이에 비우지 않습니다 — 테스트마다 다른 아이디를 씁니다 (newLoginId).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    public static final String SHARED_SECRET = "test-internal-shared-secret-0123456789abcdef";
    public static final String CRAWLER_SECRET = "test-crawler-shared-secret-0123456789abcdef";
    protected static final String MASTER_ID = "master";
    protected static final String MASTER_PASSWORD = "correct-horse-battery";
    protected static final String PASSWORD = "sub-admin-password-1";
    /** bootstrap.admins 로 심는 공용 계정 — 인증 코드가 두 주소로 갑니다 */
    protected static final String SHARED_ID = "shared.cs";
    protected static final List<String> SHARED_EMAILS = List.of("first@studio.test", "second@studio.test");
    protected static final StubServer STUB = new StubServer();

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("studio").withUsername("studio_app").withPassword("test-db-password");
    protected static final String REDIS_PASSWORD = "test-redis-password";
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD).withExposedPorts(6379);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static {
        MYSQL.start();
        REDIS.start();
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** 진짜 S3 대신 (S3ImageStoreTest 가 MinIO 로 따로 확인) */
        @Bean
        @Primary
        FakeImageStore fakeImageStore() {
            return new FakeImageStore();
        }

        /** MailSender 빈이 있으면 Spring Boot 가 진짜 SMTP 발송기를 만들지 않습니다 */
        @Bean
        MailBox mailBox() {
            return new MailBox();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("db.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("redis.password", () -> REDIS_PASSWORD);
        registry.add("internal.shared-secret", () -> SHARED_SECRET);
        registry.add("crawler.shared-secret", () -> CRAWLER_SECRET);
        registry.add("gpu.token", () -> "gpu-test-token");
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        String admins = "# 아이디,이름,역할,메일(;),bcrypt\n"
                + MASTER_ID + ",테스트 마스터,MASTER," + email(MASTER_ID) + "," + bcrypt.encode(MASTER_PASSWORD) + "\n\n"
                + SHARED_ID + ",공용 CS,CS," + String.join(" ; ", SHARED_EMAILS).toUpperCase() + "," + bcrypt.encode(PASSWORD) + "\n";
        registry.add("bootstrap.admins", () -> admins);
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
    @Autowired
    protected MailBox mail;
    @Autowired
    protected FakeImageStore imageStore;

    /** 로그인한 관리자 — 세션 쿠키와 CSRF 토큰 */
    public record Session(Cookie cookie, String csrf, JsonNode me) {}

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    protected static String newLoginId(String prefix) {
        return prefix + "." + SEQUENCE.incrementAndGet();
    }

    /** 1차(아이디 · 비밀번호) → 메일로 온 인증 코드 → 2차 */
    protected Session login(String loginId, String password) throws Exception {
        MvcResult first = mvc.perform(post("/api/studio/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", password)))).andExpect(status().isOk()).andReturn();
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
                        .content(json(Map.of("loginId", loginId, "name", "테스트 " + role, "role", role,
                                "otpEmails", List.of(email(loginId)), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return result(created).get("id").asLong();
    }

    /** 테스트 계정이 인증 코드를 받는 주소 (createAccount · 마스터) */
    protected static String email(String loginId) {
        return loginId + "@studio.test";
    }

    /** 이 계정으로 마지막에 온 인증 코드 */
    protected String otp(String loginId) {
        return mail.lastCode(email(loginId));
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
}
