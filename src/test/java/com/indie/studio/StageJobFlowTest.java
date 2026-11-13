package com.indie.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.support.IntegrationTest;
import com.indie.studio.support.StubServer;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 무대 그리기 — 그린 도면 → GPU 서버 검증 → 관리자가 결과 확인 → Concert 로 전송 */
class StageJobFlowTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] RESULT_PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};
    private static final String DRAWING = "{\"floor\":\"1F\",\"section\":\"A\",\"stage\":[[0.3,0.1],[0.7,0.1],[0.7,0.2],[0.3,0.2]],\"points\":[[0.1,0.3],[0.4,0.3],[0.4,0.6],[0.1,0.6]]}";
    private static final String SECTION = "{\"venueId\":7,\"venueName\":\"롤링홀\",\"floor\":\"1F\","
            + "\"section\":{\"name\":\"A\",\"shape\":[[0.1,0.3],[0.4,0.3],[0.4,0.6],[0.1,0.6]],\"angle\":0,\"rows\":1,\"seatCount\":2},"
            + "\"seats\":[{\"seatId\":\"1F-A-1-1\",\"row\":1,\"number\":1,\"x\":0.15,\"y\":0.35,\"angle\":0},{\"seatId\":\"1F-A-1-2\",\"row\":1,\"number\":2,\"x\":0.2,\"y\":0.35,\"angle\":0}]}";

    @Test
    void gpuVerifiesThenAdminConfirmsThenConcertReceives() throws Exception {
        Session stage = newAdmin("STAGE");
        STUB.ok("GET", "/api/concerts/venues/7", "{\"id\":7,\"name\":\"롤링홀\",\"region\":\"SEOUL\"}");
        STUB.reply("POST", "/v1/stage/verify", 200, "{\"image\":\"" + Base64.getEncoder().encodeToString(RESULT_PNG)
                + "\",\"imageContentType\":\"image/png\",\"values\":" + SECTION + "}");
        STUB.ok("PUT", "/internal/admin/concerts/venues/7/sections", "{\"venueId\":7,\"floorId\":1,\"sectionId\":11,\"seatCount\":2}");

        // ① 요청 — 바로 돌아오고 검증은 뒤에서 돕니다
        MvcResult created = mvc.perform(as(stage, multipart("/api/studio/venues/7/stage-jobs")
                        .file(new MockMultipartFile("image", "floor.png", "image/png", PNG)).param("drawing", DRAWING)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.venueName").value("롤링홀")).andReturn();
        long jobId = result(created).get("id").asLong();

        // ② GPU 서버가 검증 — 사진 · 그린 값 · 토큰이 그대로 갔는지
        JsonNode job = awaitStatus(stage, jobId, "REVIEW");
        StubServer.Received gpu = STUB.received("POST", "/v1/stage/verify").get(0);
        assertThat(gpu.headers().get("x-gpu-token")).isEqualTo("gpu-test-token");
        assertThat(gpu.headers().get("content-type")).startsWith("multipart/form-data");
        assertThat(gpu.bodyText()).contains("name=\"drawing\"").contains("\"section\":\"A\"").contains("name=\"venueId\"").contains("롤링홀")
                .contains("filename=\"stage.png\"");

        // ③ 관리자가 볼 것 — GPU 가 돌려준 값과 사진. 🔴 확인 전에는 Concert 에 아무것도 가지 않습니다
        assertThat(job.get("values")).isEqualTo(objectMapper.readTree(SECTION));
        assertThat(job.get("drawing")).isEqualTo(objectMapper.readTree(DRAWING));
        assertThat(job.get("resultImage").asBoolean()).isTrue();
        MvcResult image = mvc.perform(get("/api/studio/stage-jobs/" + jobId + "/image").cookie(stage.cookie()))
                .andExpect(status().isOk()).andReturn();
        assertThat(image.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(image.getResponse().getContentAsByteArray()).isEqualTo(RESULT_PNG);
        assertThat(mvc.perform(get("/api/studio/stage-jobs/" + jobId + "/image").param("kind", "ORIGINAL").cookie(stage.cookie()))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PNG);
        assertThat(STUB.received("PUT", "/internal/admin/concerts/venues/7/sections")).isEmpty();

        // ④ 확인 → Concert 구역 저장 API 로 (iss=studio · aud=concert)
        mvc.perform(as(stage, post("/api/studio/stage-jobs/" + jobId + "/confirm")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].sectionId").value(11));
        StubServer.Received sent = STUB.received("PUT", "/internal/admin/concerts/venues/7/sections").get(0);
        assertThat(objectMapper.readTree(sent.bodyText())).isEqualTo(objectMapper.readTree(SECTION));
        assertThat(SignedJWT.parse(sent.headers().get("x-internal-token")).getJWTClaimsSet().getAudience()).containsExactly("concert");
        assertThat(sent.headers().get("x-admin-id")).isEqualTo(stage.me().get("id").asText());

        mvc.perform(get("/api/studio/stage-jobs/" + jobId).cookie(stage.cookie()))
                .andExpect(jsonPath("$.result.status").value("SENT")).andExpect(jsonPath("$.result.sentBy").value(stage.me().get("id").asLong()));
        // 한 번 보낸 작업은 다시 못 보냅니다
        mvc.perform(as(stage, post("/api/studio/stage-jobs/" + jobId + "/confirm")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAGE_JOB_NOT_REVIEWABLE"));
        mvc.perform(get("/api/studio/venues/7/stage-jobs").cookie(stage.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].id").value(jobId)).andExpect(jsonPath("$.result[0].values").doesNotExist());
    }

    @Test
    void adminCanEditValuesBeforeSendingAndRetryAfterConcertRejects() throws Exception {
        Session stage = newAdmin("STAGE");
        STUB.ok("GET", "/api/concerts/venues/7", "{\"id\":7,\"name\":\"롤링홀\"}");
        STUB.reply("POST", "/v1/stage/verify", 200, "{\"values\":" + SECTION + "}");   // 사진 없이 값만 돌려줘도 됩니다
        long jobId = result(mvc.perform(as(stage, multipart("/api/studio/venues/7/stage-jobs")
                .file(new MockMultipartFile("image", "floor.png", "image/png", PNG)).param("drawing", DRAWING))).andReturn()).get("id").asLong();
        assertThat(awaitStatus(stage, jobId, "REVIEW").get("resultImage").asBoolean()).isFalse();
        mvc.perform(get("/api/studio/stage-jobs/" + jobId + "/image").cookie(stage.cookie()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STAGE_IMAGE_NOT_FOUND"));

        // Concert 가 거절하면(그 구역 좌석을 파는 공연이 있음) 그 응답이 그대로 오고 작업은 REVIEW 로 남습니다
        STUB.reply("PUT", "/internal/admin/concerts/venues/7/sections", 409, "{\"isSuccess\":false,\"code\":\"SECTION_IN_USE\",\"message\":\"좌석을 파는 공연이 있습니다\"}");
        mvc.perform(as(stage, post("/api/studio/stage-jobs/" + jobId + "/confirm")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SECTION_IN_USE"));
        mvc.perform(get("/api/studio/stage-jobs/" + jobId).cookie(stage.cookie())).andExpect(jsonPath("$.result.status").value("REVIEW"));

        // 관리자가 고친 값(구역 두 개)을 보내면 구역마다 한 번씩 갑니다
        String edited = "[" + SECTION + "," + SECTION.replace("\"name\":\"A\"", "\"name\":\"B\"") + "]";
        STUB.ok("PUT", "/internal/admin/concerts/venues/7/sections", "{\"venueId\":7,\"floorId\":1,\"sectionId\":11,\"seatCount\":2}");
        mvc.perform(as(stage, post("/api/studio/stage-jobs/" + jobId + "/confirm")).contentType(MediaType.APPLICATION_JSON).content(edited))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.length()").value(2));
        assertThat(STUB.received("PUT", "/internal/admin/concerts/venues/7/sections")).hasSize(3);
        assertThat(STUB.received("PUT", "/internal/admin/concerts/venues/7/sections").get(2).bodyText()).contains("\"name\":\"B\"");
        mvc.perform(get("/api/studio/stage-jobs/" + jobId).cookie(stage.cookie()))
                .andExpect(jsonPath("$.result.status").value("SENT")).andExpect(jsonPath("$.result.sent.length()").value(2));
    }

    @Test
    void rejectsBadUploadsAndReportsGpuFailure() throws Exception {
        Session stage = newAdmin("STAGE");
        STUB.ok("GET", "/api/concerts/venues/7", "{\"id\":7,\"name\":\"롤링홀\"}");

        // 사진이 아닌 파일 — Content-Type 을 image/png 로 속여도 파일 앞부분으로 거릅니다
        mvc.perform(as(stage, multipart("/api/studio/venues/7/stage-jobs")
                        .file(new MockMultipartFile("image", "floor.png", "image/png", "<script>alert(1)</script>".getBytes())).param("drawing", DRAWING)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STAGE_IMAGE_INVALID"));
        mvc.perform(as(stage, multipart("/api/studio/venues/7/stage-jobs")
                        .file(new MockMultipartFile("image", "floor.png", "image/png", PNG)).param("drawing", "not json")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STAGE_DRAWING_INVALID"));
        // 없는 공연장 — Concert 의 404 가 그대로
        mvc.perform(as(stage, multipart("/api/studio/venues/999/stage-jobs")
                        .file(new MockMultipartFile("image", "floor.png", "image/png", PNG)).param("drawing", DRAWING)))
                .andExpect(status().isNotFound());
        assertThat(STUB.received("POST", "/v1/stage/verify")).isEmpty();

        // GPU 서버 오류 — 작업이 FAILED 로 닫히고 사유가 남습니다. Concert 로는 못 보냅니다
        STUB.reply("POST", "/v1/stage/verify", 500, "{\"error\":\"cuda out of memory\"}");
        long jobId = result(mvc.perform(as(stage, multipart("/api/studio/venues/7/stage-jobs")
                .file(new MockMultipartFile("image", "floor.png", "image/png", PNG)).param("drawing", DRAWING))).andReturn()).get("id").asLong();
        assertThat(awaitStatus(stage, jobId, "FAILED").get("errorMessage").asText()).startsWith("GPU 검증에 실패했습니다");
        mvc.perform(as(stage, post("/api/studio/stage-jobs/" + jobId + "/confirm"))).andExpect(status().isConflict());

        // 공연 관리자는 무대 그리기 API 가 있는지도 모릅니다
        Session concert = newAdmin("CONCERT");
        mvc.perform(get("/api/studio/stage-jobs/" + jobId).cookie(concert.cookie())).andExpect(status().isNotFound());
    }

    private JsonNode awaitStatus(Session session, long jobId, String status) {
        JsonNode[] holder = new JsonNode[1];
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() -> {
            holder[0] = result(mvc.perform(get("/api/studio/stage-jobs/" + jobId).cookie(session.cookie())).andReturn());
            return status.equals(holder[0].get("status").asText());
        });
        return holder[0];
    }
}
