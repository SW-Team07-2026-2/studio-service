package com.indie.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.support.IntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 크롤러 → Studio (11/17 결정) — 공연은 후보로 받아 관리자가 등록, 아티스트 · 앨범은 바로 Catalog 로 */
class CrawlIntakeTest extends IntegrationTest {

    @Test
    void onlyCrawlerTokenOpensIntake() throws Exception {
        String body = "{\"items\":[{\"source\":\"crawler\",\"external_id\":\"x\",\"concert\":{}}]}";
        mvc.perform(post("/internal/crawl/concerts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // 서비스 공유 시크릿으로 만든 토큰 · 다른 발급자 · 다른 대상 · 만료 — 전부 401
        mvc.perform(crawler(post("/internal/crawl/concerts"), token(SHARED_SECRET, "crawler", "studio", 60)).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(crawler(post("/internal/crawl/concerts"), token(CRAWLER_SECRET, "studio", "studio", 60)).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(crawler(post("/internal/crawl/concerts"), token(CRAWLER_SECRET, "crawler", "catalog", 60)).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(crawler(post("/internal/crawl/concerts"), token(CRAWLER_SECRET, "crawler", "studio", -60)).content(body))
                .andExpect(status().isUnauthorized());
        // 크롤러 토큰으로 관리자 API 는 열리지 않습니다 (세션이 없으니 401)
        mvc.perform(crawler(get("/api/studio/crawl/concerts"), crawlerToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void concertWaitsForAdminThenRegistersWithSourceKey() throws Exception {
        String ext = "c-" + UUID.randomUUID();
        String item = "{\"source\":\"crawler\",\"external_id\":\"" + ext + "\",\"source_hash\":\"%s\",\"concert\":{\"title\":\"%s\",\"venueName\":\"롤링홀\"}}";
        JsonNode first = intake(item.formatted("h1", "여름 공연"));
        assertThat(first.at("/items/0/result").asText()).isEqualTo("CREATED");
        long id = first.at("/items/0/id").asLong();
        assertThat(intake(item.formatted("h1", "여름 공연")).at("/items/0/result").asText()).isEqualTo("UNCHANGED");
        assertThat(intake(item.formatted("h2", "여름 공연 (추가 회차)")).at("/items/0/result").asText()).isEqualTo("UPDATED");

        Session concert = newAdmin("CONCERT");
        JsonNode list = result(mvc.perform(as(concert, get("/api/studio/crawl/concerts"))).andExpect(status().isOk()).andReturn());
        JsonNode mine = null;
        for (JsonNode c : list) if (c.get("id").asLong() == id) mine = c;
        assertThat(mine).isNotNull();
        assertThat(mine.at("/concert/title").asText()).isEqualTo("여름 공연 (추가 회차)");
        assertThat(mine.get("status").asText()).isEqualTo("PENDING");

        // 다른 역할은 수집 대기를 보지 못합니다 (권한 밖 = 없는 경로)
        mvc.perform(as(newAdmin("GOODS"), get("/api/studio/crawl/concerts"))).andExpect(status().isNotFound());

        STUB.ok("POST", "/internal/admin/concerts", "{\"id\":77,\"title\":\"여름 공연 (추가 회차)\"}");
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":3,\"title\":\"여름 공연 (추가 회차)\",\"type\":\"SOLO\",\"artistIds\":[1],\"source\":\"other\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.id").value(77));
        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/concerts").get(0).bodyText());
        assertThat(sent.get("source").asText()).isEqualTo("crawler");
        assertThat(sent.get("externalId").asText()).isEqualTo(ext);
        assertThat(sent.get("venueId").asLong()).isEqualTo(3);

        // 처리한 뒤에는 다시 와도 바뀌지 않고, 두 번 등록 · 등록 안 함도 막힙니다
        assertThat(intake(item.formatted("h3", "바뀐 제목")).at("/items/0/result").asText()).isEqualTo("REVIEWED");
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CRAWL_CONCERT_REVIEWED"));
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/reject")))
                .andExpect(status().isConflict());
        JsonNode approved = result(mvc.perform(as(concert, get("/api/studio/crawl/concerts").param("status", "APPROVED")))
                .andExpect(status().isOk()).andReturn());
        JsonNode row = null;
        for (JsonNode c : approved) if (c.get("id").asLong() == id) row = c;
        assertThat(row.get("concertId").asLong()).isEqualTo(77);
        assertThat(row.at("/concert/title").asText()).isEqualTo("여름 공연 (추가 회차)");
        assertThat(row.get("reviewedBy").asLong()).isEqualTo(concert.me().get("id").asLong());
    }

    @Test
    void rejectedConcertStaysRejected() throws Exception {
        String item = "{\"source\":\"crawler\",\"external_id\":\"r-" + UUID.randomUUID() + "\",\"concert\":{\"title\":\"중복 공연\"}}";
        long id = intake(item).at("/items/0/id").asLong();
        Session concert = newAdmin("CONCERT");
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/reject")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("REJECTED"));
        assertThat(intake(item).at("/items/0/result").asText()).isEqualTo("REVIEWED");
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/999999/reject")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CRAWL_CONCERT_NOT_FOUND"));
    }

    @Test
    void catalogItemsGoStraightToCatalogAdminIngestAsStudio() throws Exception {
        STUB.ok("POST", "/internal/admin/catalog/ingest/artists",
                "{\"items\":[{\"external_id\":\"a1\",\"id\":5,\"result\":\"CREATED\"},{\"external_id\":\"a2\",\"result\":\"FAILED\",\"error\":\"genres\"}]}");
        String body = "{\"items\":[{\"source\":\"crawler\",\"external_id\":\"a1\",\"name\":\"새소년\"},{\"source\":\"crawler\",\"external_id\":\"a2\",\"name\":\"x\"}]}";
        mvc.perform(crawler(post("/internal/crawl/catalog/artists"), crawlerToken()).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.items[0].result").value("CREATED"))
                .andExpect(jsonPath("$.result.items[1].error").value("genres"));

        var call = STUB.received("POST", "/internal/admin/catalog/ingest/artists").get(0);
        assertThat(objectMapper.readTree(call.bodyText())).isEqualTo(objectMapper.readTree(body));   // 본문은 그대로
        SignedJWT token = SignedJWT.parse(call.headers().get("x-internal-token"));
        assertThat(token.verify(new MACVerifier(SHARED_SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo("studio");
        assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("catalog");
        assertThat(call.headers()).doesNotContainKey("x-admin-id");

        STUB.ok("POST", "/internal/admin/catalog/ingest/diff", "{\"new\":[\"b1\"],\"changed\":[],\"unchanged\":[]}");
        mvc.perform(crawler(post("/internal/crawl/catalog/diff"), crawlerToken())
                        .content("{\"source\":\"crawler\",\"type\":\"ALBUM\",\"items\":[{\"external_id\":\"b1\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.new[0]").value("b1"));

        // Catalog 가 본문을 거절하면 그 400 을 그대로 돌려줍니다
        STUB.reply("POST", "/internal/admin/catalog/ingest/albums", 400,
                "{\"isSuccess\":false,\"code\":\"INVALID_REQUEST\",\"message\":\"items: 비어 있을 수 없습니다\"}");
        mvc.perform(crawler(post("/internal/crawl/catalog/albums"), crawlerToken()).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JsonNode intake(String item) throws Exception {
        return result(mvc.perform(crawler(post("/internal/crawl/concerts"), crawlerToken()).content("{\"items\":[" + item + "]}"))
                .andExpect(status().isOk()).andReturn());
    }

    private static MockHttpServletRequestBuilder crawler(MockHttpServletRequestBuilder request, String token) {
        return request.header("X-Internal-Token", token).contentType(MediaType.APPLICATION_JSON);
    }

    private String crawlerToken() throws Exception {
        return token(CRAWLER_SECRET, "crawler", "studio", 60);
    }

    /** 만료는 앱의 시계 기준 — 다른 테스트가 시계를 넘겨 둡니다 */
    private String token(String secret, String iss, String aud, long expInSeconds) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer(iss).audience(aud).expirationTime(Date.from(clock.instant().plusSeconds(expInSeconds))).build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
