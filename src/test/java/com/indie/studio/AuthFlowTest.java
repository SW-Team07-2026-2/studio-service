package com.indie.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인 — 1차 아이디 · 비밀번호(DB) → 2차 OTP → 대시보드 */
class AuthFlowTest extends IntegrationTest {

    @Test
    void passwordThenOtpThenDashboard() throws Exception {
        String loginId = newLoginId("cs");
        createAccount(loginMaster(), loginId, "CS");
        clock.advance(Duration.ofSeconds(31));

        // 로그인 전에는 무엇을 불러도 401 — 있는 경로든 없는 경로든 같습니다
        mvc.perform(get("/api/studio/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/studio/no-such-api")).andExpect(status().isUnauthorized());

        // 1차 — 틀린 비밀번호와 없는 아이디는 같은 응답
        mvc.perform(loginRequest(loginId, "wrong-password-123")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        mvc.perform(loginRequest("nobody.here", PASSWORD)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        // 1차 통과 — 첫 로그인이라 OTP 등록 정보가 옵니다. 아직 로그인된 것이 아닙니다
        MvcResult first = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isOk()).andReturn();
        JsonNode next = result(first);
        assertThat(next.get("next").asText()).isEqualTo("OTP_ENROLL");
        assertThat(next.get("otpauthUri").asText()).startsWith("otpauth://totp/indie-studio:" + loginId + "?secret=" + next.get("secret").asText());
        Cookie challenge = first.getResponse().getCookie("STUDIO_2FA");
        assertThat(challenge.isHttpOnly()).isTrue();
        assertThat(first.getResponse().getCookie("STUDIO_SESSION")).isNull();
        mvc.perform(get("/api/studio/me").cookie(challenge)).andExpect(status().isUnauthorized());

        // 2차 — 틀린 코드는 거절, 대기 쿠키 없이는 처음부터
        mvc.perform(otpRequest(challenge, "000000")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        mvc.perform(post("/api/studio/auth/otp").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", "123456"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_CHALLENGE_EXPIRED"));

        // 처음부터 다시 — login() 이 1차 · OTP 등록 · 2차를 끝까지 합니다
        Session session = login(loginId, PASSWORD);
        assertThat(session.cookie().isHttpOnly()).isTrue();
        assertThat(session.cookie().getPath()).isEqualTo("/api/studio");
        assertThat(session.me().get("role").asText()).isEqualTo("CS");

        mvc.perform(get("/api/studio/me").cookie(session.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.loginId").value(loginId))
                .andExpect(jsonPath("$.result.menus.length()").value(1))
                .andExpect(jsonPath("$.result.menus[0].key").value("cs"));

        // 두 번째 로그인부터는 등록 없이 코드만
        clock.advance(Duration.ofSeconds(31));
        MvcResult again = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isOk()).andReturn();
        assertThat(result(again).get("next").asText()).isEqualTo("OTP");
        assertThat(result(again).has("secret")).isFalse();
        String code = otp(loginId);
        mvc.perform(otpRequest(again.getResponse().getCookie("STUDIO_2FA"), code)).andExpect(status().isOk());

        // 방금 통과한 코드는 같은 30초 안에 다시 쓸 수 없습니다 (어깨 너머로 본 코드 재사용)
        MvcResult replay = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isOk()).andReturn();
        mvc.perform(otpRequest(replay.getResponse().getCookie("STUDIO_2FA"), code))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));

        // 로그아웃하면 그 세션은 끝
        mvc.perform(as(session, post("/api/studio/auth/logout"))).andExpect(status().isOk());
        mvc.perform(get("/api/studio/me").cookie(session.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void locksAccountAfterFiveFailures() throws Exception {
        String loginId = newLoginId("goods");
        createAccount(loginMaster(), loginId, "GOODS");

        for (int i = 0; i < 5; i++) {
            mvc.perform(loginRequest(loginId, "wrong-password-123")).andExpect(status().isUnauthorized());
        }
        // 잠긴 동안에는 맞는 비밀번호도 같은 응답으로 거절
        mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        clock.advance(Duration.ofMinutes(16));
        assertThat(login(loginId, PASSWORD).me().get("role").asText()).isEqualTo("GOODS");
    }

    @Test
    void sessionExpiresWhenIdleAndAfterEightHours() throws Exception {
        Session idle = newAdmin("STAGE");
        clock.advance(Duration.ofMinutes(31));
        mvc.perform(get("/api/studio/me").cookie(idle.cookie())).andExpect(status().isUnauthorized());

        Session busy = newAdmin("STAGE");
        for (int i = 0; i < 20; i++) {   // 25분마다 요청 — 쉬지 않아도 8시간이 지나면 끝
            clock.advance(Duration.ofMinutes(25));
            int expected = i < 19 ? 200 : 401;
            mvc.perform(get("/api/studio/me").cookie(busy.cookie())).andExpect(status().is(expected));
        }
    }

    @Test
    void changesOwnPassword() throws Exception {
        String loginId = newLoginId("concert");
        createAccount(loginMaster(), loginId, "CONCERT");
        Session session = login(loginId, PASSWORD);
        Session otherDevice = login(loginId, PASSWORD);

        mvc.perform(as(session, put("/api/studio/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "not-my-password-1", "newPassword", "brand-new-password-9"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        mvc.perform(as(session, put("/api/studio/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        mvc.perform(as(session, put("/api/studio/me/password")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "brand-new-password-9"))))
                .andExpect(status().isOk());

        // 지금 세션은 남고 다른 기기의 세션은 끊깁니다. 옛 비밀번호로는 못 들어옵니다
        mvc.perform(get("/api/studio/me").cookie(session.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/studio/me").cookie(otherDevice.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(login(loginId, "brand-new-password-9").me().get("loginId").asText()).isEqualTo(loginId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String loginId, String password) throws Exception {
        return post("/api/studio/auth/login").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loginId", loginId, "password", password)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder otpRequest(Cookie challenge, String code) throws Exception {
        return post("/api/studio/auth/otp").cookie(challenge).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code)));
    }
}
