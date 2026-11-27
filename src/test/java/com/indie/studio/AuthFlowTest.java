package com.indie.studio;

import com.indie.studio.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인 - 1차 아이디, 비밀번호(DB) -> 2차 메일 인증 코드 -> 대시보드 */
class AuthFlowTest extends IntegrationTest {

    @Test
    void passwordThenMailCodeThenDashboard() throws Exception {
        String loginId = newLoginId("cs");
        createAccount(loginMaster(), loginId, "CS");

        // 로그인 전에는 무엇을 불러도 401 - 있는 경로든 없는 경로든 같습니다
        mvc.perform(get("/api/studio/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/studio/no-such-api")).andExpect(status().isUnauthorized());

        // 1차 - 틀린 비밀번호와 없는 아이디는 같은 응답이고, 메일은 가지 않습니다
        int mailsBefore = mail.count();
        mvc.perform(loginRequest(loginId, "wrong-password-123")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        mvc.perform(loginRequest("nobody.here", PASSWORD)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        assertThat(mail.count()).isEqualTo(mailsBefore);

        // 1차 통과 - 인증 코드 메일이 한 통 갑니다. 응답에는 가린 주소만 있고 코드는 없습니다. 아직 로그인된 것이 아닙니다
        MvcResult first = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isOk()).andReturn();
        assertThat(result(first).get("next").asText()).isEqualTo("OTP");
        assertThat(result(first).get("sentTo")).hasSize(1);
        assertThat(result(first).get("sentTo").get(0).asText()).isEqualTo("cs***@studio.test");
        assertThat(mail.count()).isEqualTo(mailsBefore + 1);
        SimpleMailMessage message = mail.to(email(loginId)).get(0);
        assertThat(message.getFrom()).isEqualTo("indig <noreply@indig.co.kr>");
        assertThat(message.getSubject()).contains("인증 코드");
        String code = otp(loginId);
        assertThat(first.getResponse().getContentAsString()).doesNotContain(code);
        Cookie challenge = first.getResponse().getCookie("STUDIO_2FA");
        assertThat(challenge.isHttpOnly()).isTrue();
        assertThat(first.getResponse().getCookie("STUDIO_SESSION")).isNull();
        mvc.perform(get("/api/studio/me").cookie(challenge)).andExpect(status().isUnauthorized());

        // 2차 - 틀린 코드는 거절, 대기 쿠키 없이는 맞는 코드도 처음부터
        mvc.perform(otpRequest(challenge, wrong(code))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        mvc.perform(post("/api/studio/auth/otp").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_CHALLENGE_EXPIRED"));

        // 맞는 코드 - 세션 쿠키가 내려오고 내 역할의 탭만 보입니다
        MvcResult second = mvc.perform(otpRequest(challenge, code)).andExpect(status().isOk()).andReturn();
        Cookie sessionCookie = second.getResponse().getCookie("STUDIO_SESSION");
        assertThat(sessionCookie.isHttpOnly()).isTrue();
        assertThat(sessionCookie.getPath()).isEqualTo("/api/studio");
        Session session = new Session(sessionCookie, result(second).get("csrfToken").asText(), result(second));
        assertThat(session.me().get("role").asText()).isEqualTo("CS");
        mvc.perform(get("/api/studio/me").cookie(session.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.loginId").value(loginId))
                .andExpect(jsonPath("$.result.menus.length()").value(1))
                .andExpect(jsonPath("$.result.menus[0].key").value("cs"));

        // 쓴 코드는 다시 못 씁니다 (대기는 1회용)
        mvc.perform(otpRequest(challenge, code)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_CHALLENGE_EXPIRED"));

        // 로그아웃하면 그 세션은 끝
        mvc.perform(as(session, post("/api/studio/auth/logout"))).andExpect(status().isOk());
        mvc.perform(get("/api/studio/me").cookie(session.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void sharedAccountSendsSameCodeToEveryAddress() throws Exception {
        // bootstrap.admins 로 심은 공용 계정 (메일 2개, 파일에는 대문자, 공백이 섞여 있음)
        MvcResult first = mvc.perform(loginRequest(SHARED_ID, PASSWORD)).andExpect(status().isOk()).andReturn();
        assertThat(result(first).get("sentTo")).hasSize(2);
        String code = mail.lastCode(SHARED_EMAILS.get(0));
        assertThat(mail.lastCode(SHARED_EMAILS.get(1))).isEqualTo(code);
        // 한 통에 한 사람 - 받는 사람끼리 주소가 보이지 않습니다
        assertThat(mail.to(SHARED_EMAILS.get(0))).allSatisfy(m -> assertThat(m.getTo()).hasSize(1));

        mvc.perform(otpRequest(first.getResponse().getCookie("STUDIO_2FA"), code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.loginId").value(SHARED_ID)).andExpect(jsonPath("$.result.role").value("CS"));
    }

    @Test
    void codeExpiresAfterFiveMinutesAndFiveWrongTries() throws Exception {
        String loginId = newLoginId("checker");
        createAccount(loginMaster(), loginId, "CHECKER");

        // 5분이 지나면 맞는 코드도 처음부터
        MvcResult late = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isOk()).andReturn();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        mvc.perform(otpRequest(late.getResponse().getCookie("STUDIO_2FA"), otp(loginId)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_CHALLENGE_EXPIRED"));

        // 다시 로그인하면 새 코드가 옵니다. 5번 틀리면 그 대기는 버려지고 (맞는 코드도 거절) 계정이 15분 잠깁니다
        MvcResult guess = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isOk()).andReturn();
        Cookie challenge = guess.getResponse().getCookie("STUDIO_2FA");
        String code = otp(loginId);
        for (int i = 0; i < 5; i++) {
            mvc.perform(otpRequest(challenge, wrong(code))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        mvc.perform(otpRequest(challenge, code)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_CHALLENGE_EXPIRED"));
        mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        clock.advance(Duration.ofMinutes(16));
        assertThat(login(loginId, PASSWORD).me().get("role").asText()).isEqualTo("CHECKER");
    }

    @Test
    void mailFailureDoesNotLeaveChallenge() throws Exception {
        String loginId = newLoginId("goods");
        createAccount(loginMaster(), loginId, "GOODS");

        // 메일 서버가 받지 않으면 503 - 2단계 대기 쿠키도 내려가지 않습니다. 다시 로그인하면 됩니다
        mail.failNext();
        MvcResult failed = mvc.perform(loginRequest(loginId, PASSWORD)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OTP_MAIL_FAILED")).andReturn();
        assertThat(failed.getResponse().getCookie("STUDIO_2FA")).isNull();
        assertThat(login(loginId, PASSWORD).me().get("role").asText()).isEqualTo("GOODS");
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
        for (int i = 0; i < 20; i++) {   // 25분마다 요청 - 쉬지 않아도 8시간이 지나면 끝
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

    /** 맞는 코드와 다른 6자리 */
    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }
}
