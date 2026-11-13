package com.indie.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.auth.SessionFilter;
import com.indie.studio.support.IntegrationTest;
import com.indie.studio.support.StubServer;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 역할 — 하위 관리자는 자기 역할의 탭 · API 만 보고, 다른 역할의 것은 있는지도 알 수 없습니다 */
class RbacTest extends IntegrationTest {

    /** 영역마다 조회 하나 — [탭 key, 경로] */
    private static final List<String[]> AREAS = List.of(
            new String[]{"venues", "/api/studio/venues"},
            new String[]{"concerts", "/api/studio/concerts"},
            new String[]{"products", "/api/studio/products/goods"},
            new String[]{"cs", "/api/studio/cs/members"},
            new String[]{"checkin", "/api/studio/check-in/concerts"},
            new String[]{"accounts", "/api/studio/accounts"},
            new String[]{"audit", "/api/studio/audit-logs"});

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void eachRoleSeesAndReachesOnlyItsOwnArea() throws Exception {
        Map<String, List<String>> expected = Map.of(
                "STAGE", List.of("venues"),
                "CONCERT", List.of("concerts"),
                "GOODS", List.of("products"),
                "CS", List.of("cs"),
                "CHECKER", List.of("checkin"));
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            Session session = newAdmin(entry.getKey());
            STUB.fallbackOk("{\"content\":[]}");
            assertThat(menuKeys(session)).as("%s 의 탭", entry.getKey()).isEqualTo(entry.getValue());
            for (String[] area : AREAS) {
                int status = entry.getValue().contains(area[0]) ? 200 : 404;
                mvc.perform(get(area[1]).cookie(session.cookie())).andExpect(status().is(status));
            }
        }

        Session master = loginMaster();
        STUB.fallbackOk("{\"content\":[]}");
        assertThat(menuKeys(master)).containsExactly("venues", "concerts", "products", "cs", "checkin", "accounts", "audit");
        for (String[] area : AREAS) {
            mvc.perform(get(area[1]).cookie(master.cookie())).andExpect(status().isOk());
        }
    }

    @Test
    void forbiddenAreaLooksExactlyLikeAMissingPath() throws Exception {
        Session stage = newAdmin("STAGE");

        MvcResult forbidden = mvc.perform(get("/api/studio/products/goods").cookie(stage.cookie())).andExpect(status().isNotFound()).andReturn();
        MvcResult missing = mvc.perform(get("/api/studio/no-such-api").cookie(stage.cookie())).andExpect(status().isNotFound()).andReturn();
        assertThat(body(forbidden).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(body(forbidden).get("message")).isEqualTo(body(missing).get("message"));
        assertThat(body(forbidden).get("code")).isEqualTo(body(missing).get("code"));

        // 쓰기도 마찬가지 — 권한 밖이면 본문 · CSRF 를 보기 전에 404, 그리고 Catalog 에는 아무것도 가지 않습니다
        mvc.perform(as(stage, post("/api/studio/products")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/studio/products").cookie(stage.cookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(STUB.received("POST", "/internal/admin/catalog/products")).isEmpty();

        // 권한 밖 시도는 작업 기록에 DENIED 로 남습니다 (마스터만 볼 수 있음)
        long stageId = stage.me().get("id").asLong();
        MvcResult logs = mvc.perform(get("/api/studio/audit-logs").param("adminId", String.valueOf(stageId)).cookie(loginMaster().cookie()))
                .andExpect(status().isOk()).andReturn();
        List<String> denied = new ArrayList<>();
        result(logs).forEach(l -> { if ("DENIED".equals(l.get("result").asText())) denied.add(l.get("method").asText() + " " + l.get("path").asText()); });
        assertThat(denied).contains("GET /api/studio/products/goods", "POST /api/studio/products");
    }

    @Test
    void callsAwsServicesWithStudioInternalToken() throws Exception {
        Session stage = newAdmin("STAGE");
        STUB.ok("GET", "/api/concerts/venues", "{\"content\":[{\"id\":7,\"name\":\"롤링홀\"}]}");

        mvc.perform(get("/api/studio/venues").param("q", "롤링").param("region", "SEOUL").cookie(stage.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].name").value("롤링홀"));

        StubServer.Received call = STUB.received("GET", "/api/concerts/venues").get(0);
        assertThat(call.query()).contains("q=롤링").contains("region=SEOUL");
        assertThat(call.headers().get("x-admin-id")).isEqualTo(stage.me().get("id").asText());
        SignedJWT token = SignedJWT.parse(call.headers().get("x-internal-token"));
        assertThat(token.verify(new MACVerifier(SHARED_SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo("studio");
        assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("concert");
    }

    /** 검표 — 검표자는 세션의 관리자입니다. 화면이 다른 checkerId 를 보내도 쓰지 않고, 이미 입장한 QR 은 Concert 의 409 를 그대로 냅니다 */
    @Test
    void checkInSendsSessionAdminAsChecker() throws Exception {
        Session checker = newAdmin("CHECKER");
        long checkerId = checker.me().get("id").asLong();
        STUB.ok("POST", "/internal/admin/concerts/31/check-in", "{\"bookingId\":5,\"seats\":[\"1F A 2열 3번\"],\"checkedInAt\":\"2026-11-13T10:00:00Z\"}");

        mvc.perform(as(checker, post("/api/studio/check-in/concerts/31")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"qr-value\",\"checkerId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.bookingId").value(5));

        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/concerts/31/check-in").get(0).bodyText());
        assertThat(sent.get("token").asText()).isEqualTo("qr-value");
        assertThat(sent.get("checkerId").asLong()).isEqualTo(checkerId);

        STUB.reply("POST", "/internal/admin/concerts/31/check-in", 409,
                "{\"isSuccess\":false,\"code\":\"ALREADY_CHECKED_IN\",\"message\":\"이미 입장한 예매입니다\"}");
        mvc.perform(as(checker, post("/api/studio/check-in/concerts/31")).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"qr-value\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
        mvc.perform(as(checker, post("/api/studio/check-in/concerts/31")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passesUpstreamErrorsThroughButNeverAs401() throws Exception {
        Session stage = newAdmin("STAGE");
        String venue = "{\"name\":\"롤링홀\",\"region\":\"SEOUL\"}";

        STUB.reply("POST", "/internal/admin/concerts/venues", 409, "{\"isSuccess\":false,\"code\":\"VENUE_EXISTS\",\"message\":\"이미 등록된 공연장입니다\"}");
        mvc.perform(as(stage, post("/api/studio/venues")).contentType(MediaType.APPLICATION_JSON).content(venue))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENUE_EXISTS"))
                .andExpect(jsonPath("$.message").value("이미 등록된 공연장입니다"));

        // 대상 서비스가 Studio 의 내부 토큰을 거절 — 관리자 세션 문제가 아니므로 401 이 아니라 502
        STUB.reply("POST", "/internal/admin/concerts/venues", 401, "{\"isSuccess\":false,\"code\":\"INTERNAL_TOKEN_INVALID\",\"message\":\"x\"}");
        mvc.perform(as(stage, post("/api/studio/venues")).contentType(MediaType.APPLICATION_JSON).content(venue))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
        mvc.perform(get("/api/studio/me").cookie(stage.cookie())).andExpect(status().isOk());

        STUB.ok("POST", "/internal/admin/concerts/venues", "{\"id\":7,\"name\":\"롤링홀\",\"region\":\"SEOUL\"}");
        mvc.perform(as(stage, post("/api/studio/venues")).contentType(MediaType.APPLICATION_JSON).content(venue))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.id").value(7));
        assertThat(objectMapper.readTree(STUB.received("POST", "/internal/admin/concerts/venues").get(2).bodyText()))
                .isEqualTo(objectMapper.readTree(venue));
    }

    @Test
    void stateChangingRequestNeedsCsrfToken() throws Exception {
        Session master = loginMaster();
        String account = json(Map.of("loginId", newLoginId("cs"), "name", "CS", "role", "CS", "password", PASSWORD));

        mvc.perform(post("/api/studio/accounts").cookie(master.cookie()).contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/studio/accounts").cookie(master.cookie()).header("X-CSRF-Token", "guess").contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isForbidden());
        mvc.perform(as(master, post("/api/studio/accounts")).contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.otpEnrolled").value(false));

        // 막힌 요청도 작업 기록에 남습니다
        MvcResult logs = mvc.perform(get("/api/studio/audit-logs").param("adminId", master.me().get("id").asText()).cookie(master.cookie()))
                .andExpect(status().isOk()).andReturn();
        List<String> rejected = new ArrayList<>();
        result(logs).forEach(l -> { if (l.get("status").asInt() == 403) rejected.add(l.get("method").asText() + " " + l.get("path").asText()); });
        assertThat(rejected).contains("POST /api/studio/accounts");
    }

    @Test
    void masterManagesAccountsAndChangesApplyImmediately() throws Exception {
        Session master = loginMaster();
        String loginId = newLoginId("stage");
        long id = createAccount(master, loginId, "STAGE");
        Session stage = login(loginId, PASSWORD);
        STUB.fallbackOk("{\"content\":[]}");
        mvc.perform(get("/api/studio/venues").cookie(stage.cookie())).andExpect(status().isOk());

        // 같은 아이디는 두 번 못 만듭니다
        mvc.perform(as(master, post("/api/studio/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("loginId", loginId, "name", "중복", "role", "CS", "password", PASSWORD))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_ID_TAKEN"));

        // 역할을 바꾸면 그 계정의 세션이 바로 끊기고, 다시 들어오면 새 역할의 탭만 보입니다
        mvc.perform(as(master, patch("/api/studio/accounts/" + id)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"GOODS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.role").value("GOODS"));
        mvc.perform(get("/api/studio/venues").cookie(stage.cookie())).andExpect(status().isUnauthorized());
        Session goods = login(loginId, PASSWORD);
        assertThat(menuKeys(goods)).containsExactly("products");
        mvc.perform(get("/api/studio/venues").cookie(goods.cookie())).andExpect(status().isNotFound());

        // OTP 초기화 — 세션이 끊기고 다음 로그인 때 다시 등록합니다
        mvc.perform(as(master, post("/api/studio/accounts/" + id + "/otp-reset"))).andExpect(status().isOk());
        mvc.perform(get("/api/studio/me").cookie(goods.cookie())).andExpect(status().isUnauthorized());
        MvcResult first = mvc.perform(post("/api/studio/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", PASSWORD)))).andExpect(status().isOk()).andReturn();
        assertThat(result(first).get("next").asText()).isEqualTo("OTP_ENROLL");

        // 비밀번호 초기화 — 옛 비밀번호는 끝
        mvc.perform(as(master, post("/api/studio/accounts/" + id + "/password-reset")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"reset-by-master-77\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/studio/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", PASSWORD)))).andExpect(status().isUnauthorized());
        Session afterReset = login(loginId, "reset-by-master-77");

        // 비활성화 — 세션이 끊기고 로그인도 안 됩니다. 계정은 목록에 남습니다
        mvc.perform(as(master, patch("/api/studio/accounts/" + id)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/studio/me").cookie(afterReset.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/studio/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", "reset-by-master-77")))).andExpect(status().isUnauthorized());
        JsonNode accounts = result(mvc.perform(get("/api/studio/accounts").cookie(master.cookie())).andExpect(status().isOk()).andReturn());
        assertThat(accounts.findValuesAsText("loginId")).contains(loginId, MASTER_ID);
        assertThat(accounts.toString()).doesNotContain("passwordHash").doesNotContain("totp");

        // 마스터는 자기 역할 · 상태를 못 바꿉니다 — 활성 마스터가 항상 1명 남습니다
        long masterId = master.me().get("id").asLong();
        mvc.perform(as(master, patch("/api/studio/accounts/" + masterId)).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"CS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_CHANGE_SELF"));
        mvc.perform(as(master, patch("/api/studio/accounts/" + masterId)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict());
    }

    /** 🔴 권한 선언이 빠진 핸들러는 아무도 못 부르지만(기본 거부), 빠뜨린 것 자체를 여기서 잡습니다 */
    @Test
    void everyStudioHandlerDeclaresAPermission() {
        List<String> missing = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/studio/") || SessionFilter.PUBLIC_PATHS.contains(path)) continue;
                if (method.getMethodAnnotation(RequirePermission.class) == null) missing.add(path + " → " + method.getShortLogMessage());
            }
        });
        assertThat(missing).isEmpty();
        assertThat(handlerMapping.getHandlerMethods()).hasSizeGreaterThan(40);
    }

    private List<String> menuKeys(Session session) {
        List<String> keys = new ArrayList<>();
        session.me().get("menus").forEach(m -> keys.add(m.get("key").asText()));
        return keys;
    }
}
