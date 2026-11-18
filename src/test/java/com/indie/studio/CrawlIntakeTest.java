package com.indie.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.support.FakeImageStore;
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

/** 크롤러 → Studio (11/17 결정) — 공연 · 아티스트 · 앨범 모두 후보로 받아 관리자가 확인한 뒤 사용자 서비스로 */
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

        // 두 번 등록 · 등록 안 함은 막힙니다
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CRAWL_CONCERT_REVIEWED"));
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/reject")))
                .andExpect(status().isConflict());
        JsonNode row = row(concert, "APPROVED", id);
        assertThat(row.get("concertId").asLong()).isEqualTo(77);
        assertThat(row.at("/concert/title").asText()).isEqualTo("여름 공연 (추가 회차)");
        assertThat(row.get("previous").isNull()).isTrue();
        assertThat(row.get("reviewedBy").asLong()).isEqualTo(concert.me().get("id").asLong());

        // 등록한 공연이 출처에서 바뀌면 CHANGED — 비교 기준은 등록 때 값 (두 번 바뀌어도) · 확인하면 지금 값이 새 기준
        assertThat(intake(item.formatted("h2", "여름 공연 (추가 회차)")).at("/items/0/result").asText()).isEqualTo("UNCHANGED");
        assertThat(intake(item.formatted("h3", "바뀐 제목")).at("/items/0/result").asText()).isEqualTo("CHANGED");
        assertThat(intake(item.formatted("h4", "또 바뀐 제목")).at("/items/0/result").asText()).isEqualTo("CHANGED");
        JsonNode changed = row(concert, "CHANGED", id);
        assertThat(changed.at("/concert/title").asText()).isEqualTo("또 바뀐 제목");
        assertThat(changed.at("/previous/title").asText()).isEqualTo("여름 공연 (추가 회차)");
        assertThat(changed.get("concertId").asLong()).isEqualTo(77);
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/change-checked")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.previous").isEmpty());
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/change-checked")))
                .andExpect(status().isConflict());
        assertThat(intake(item.formatted("h5", "세 번째 제목")).at("/items/0/result").asText()).isEqualTo("CHANGED");
        assertThat(row(concert, "CHANGED", id).at("/previous/title").asText()).isEqualTo("또 바뀐 제목");
    }

    /** 11/18 — KOPIS 포스터 · 소개 이미지는 승인할 때 Studio 가 받아 우리 버킷 주소로 바꿔 보냄 · 못 받으면 승인 안 됨(관리자가 직접 올림) · 외부 주소는 거절 */
    @Test
    void concertApprovalMovesKopisImagesIntoOurBucket() throws Exception {
        Session concert = newAdmin("CONCERT");
        long id = intake("{\"source\":\"crawler\",\"external_id\":\"p-" + UUID.randomUUID() + "\",\"concert\":{\"title\":\"포스터 공연\"}}")
                .at("/items/0/id").asLong();
        STUB.ok("POST", "/internal/admin/concerts", "{\"id\":78}");

        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"포스터 공연\",\"posterUrl\":\"http://www.kopis.or.kr/upload/broken.gif\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("IMAGE_IMPORT_FAILED"));
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"포스터 공연\",\"posterUrl\":\"https://elsewhere.example/p.jpg\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_URL_NOT_ALLOWED"));
        assertThat(STUB.received("POST", "/internal/admin/concerts")).isEmpty();

        String own = imageStore.uploadedImage(com.indie.studio.domain.image.ImageKind.CONCERT);
        mvc.perform(as(concert, post("/api/studio/crawl/concerts/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"포스터 공연\",\"posterUrl\":\"http://www.kopis.or.kr/upload/pfmPoster/PF_1.gif\","
                                + "\"imageUrls\":[\"http://www.kopis.or.kr/upload/pfmIntroImage/PF_1_a.jpg\",\"" + own + "\"]}"))
                .andExpect(status().isOk());
        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/concerts").get(0).bodyText());
        assertThat(sent.get("posterUrl").asText()).startsWith(FakeImageStore.BASE + "poster/");
        assertThat(sent.at("/imageUrls/0").asText()).startsWith(FakeImageStore.BASE + "concert/");
        assertThat(sent.at("/imageUrls/1").asText()).isEqualTo(own);
        assertThat(imageStore.imported).contains("http://www.kopis.or.kr/upload/pfmPoster/PF_1.gif",
                "http://www.kopis.or.kr/upload/pfmIntroImage/PF_1_a.jpg");
    }

    private JsonNode row(Session admin, String status, long id) throws Exception {
        JsonNode list = result(mvc.perform(as(admin, get("/api/studio/crawl/concerts").param("status", status)))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode c : list) if (c.get("id").asLong() == id) return c;
        throw new AssertionError("no candidate " + id + " in " + status);
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
    void catalogItemsWaitForProductAdminThenGoToCatalog() throws Exception {
        String a = "a-" + UUID.randomUUID(), b = "b-" + UUID.randomUUID();
        String artist = "{\"source\":\"crawler\",\"external_id\":\"" + a + "\",\"source_hash\":\"%s\",\"name\":\"새소년\",\"genres\":[\"ROCK\"]}";
        String album = "{\"source\":\"crawler\",\"external_id\":\"" + b + "\",\"source_hash\":\"h1\",\"title\":\"비적응\",\"type\":\"EP\","
                + "\"artist_ref\":{\"source\":\"crawler\",\"external_id\":\"" + a + "\"}}";
        JsonNode first = intakeCatalog("artists", artist.formatted("h1"));
        assertThat(first.at("/items/0/result").asText()).isEqualTo("CREATED");
        long artistId = first.at("/items/0/id").asLong();
        long albumId = intakeCatalog("albums", album).at("/items/0/id").asLong();
        // 받기만 하고 Catalog 로는 아직 아무것도 보내지 않습니다
        assertThat(STUB.received("POST", "/internal/admin/catalog/ingest/artists")).isEmpty();
        assertThat(STUB.received("POST", "/internal/admin/catalog/ingest/albums")).isEmpty();

        Session goods = newAdmin("GOODS");
        JsonNode pending = result(mvc.perform(as(goods, get("/api/studio/crawl/catalog").param("type", "ALBUM"))).andExpect(status().isOk()).andReturn());
        assertThat(find(pending, albumId).at("/item/artist_ref/external_id").asText()).isEqualTo(a);
        mvc.perform(as(newAdmin("CONCERT"), get("/api/studio/crawl/catalog"))).andExpect(status().isNotFound());

        // 아티스트보다 앨범을 먼저 승인할 수 없습니다
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + albumId + "/approve")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CRAWL_ARTIST_NOT_APPROVED"));

        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"id\":15,\"result\":\"CREATED\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + artistId + "/approve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.status").value("APPROVED")).andExpect(jsonPath("$.result.catalogId").value(15));
        var call = STUB.received("POST", "/internal/admin/catalog/ingest/artists").get(0);
        assertThat(objectMapper.readTree(call.bodyText()).at("/items/0")).isEqualTo(objectMapper.readTree(artist.formatted("h1")));
        SignedJWT token = SignedJWT.parse(call.headers().get("x-internal-token"));
        assertThat(token.verify(new MACVerifier(SHARED_SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("catalog");
        assertThat(call.headers().get("x-admin-id")).isEqualTo(goods.me().get("id").asText());

        // 앨범은 artist_ref 대신 승인된 아티스트의 Catalog id 로 보냅니다
        STUB.ok("POST", "/internal/admin/catalog/ingest/albums", "{\"items\":[{\"external_id\":\"" + b + "\",\"id\":40,\"result\":\"CREATED\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + albumId + "/approve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.catalogId").value(40));
        JsonNode sentAlbum = objectMapper.readTree(STUB.received("POST", "/internal/admin/catalog/ingest/albums").get(0).bodyText()).at("/items/0");
        assertThat(sentAlbum.get("artist_id").asLong()).isEqualTo(15);
        assertThat(sentAlbum.has("artist_ref")).isFalse();
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + albumId + "/approve"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CRAWL_CATALOG_REVIEWED"));

        // 승인된 것도 값이 바뀌어 오면 다시 승인 대기 (새 굿즈 등) — catalogId 는 그대로
        assertThat(intakeCatalog("artists", artist.formatted("h1")).at("/items/0/result").asText()).isEqualTo("UNCHANGED");
        assertThat(intakeCatalog("artists", artist.formatted("h2")).at("/items/0/result").asText()).isEqualTo("UPDATED");
        JsonNode again = find(result(mvc.perform(as(goods, get("/api/studio/crawl/catalog").param("type", "ARTIST"))).andReturn()), artistId);
        assertThat(again.get("status").asText()).isEqualTo("PENDING");
        assertThat(again.get("catalogId").asLong()).isEqualTo(15);
    }

    @Test
    void rejectedByCatalogStaysPendingWithReasonAndAdminCanFixOrReject() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String artist = "{\"source\":\"crawler\",\"external_id\":\"" + a + "\",\"name\":\"x\",\"genres\":[\"ROCK\"]}";
        long id = intakeCatalog("artists", artist).at("/items/0/id").asLong();
        Session goods = newAdmin("GOODS");

        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"result\":\"FAILED\",\"error\":\"name too short\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CRAWL_CATALOG_FAILED"))
                .andExpect(jsonPath("$.message").value("name too short"));
        JsonNode row = find(result(mvc.perform(as(goods, get("/api/studio/crawl/catalog"))).andReturn()), id);
        assertThat(row.get("status").asText()).isEqualTo("PENDING");
        assertThat(row.get("lastError").asText()).isEqualTo("name too short");

        // 관리자가 고친 값으로 승인 — source · external_id 는 Studio 가 원래 값으로 넣습니다
        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"id\":21,\"result\":\"CREATED\"}]}");
        JsonNode fixed = result(mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"other\",\"name\":\"고친 이름\",\"genres\":[\"ROCK\"]}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(fixed.at("/item/name").asText()).isEqualTo("고친 이름");
        assertThat(fixed.path("lastError").asText(null)).isNull();
        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/catalog/ingest/artists").get(1).bodyText()).at("/items/0");
        assertThat(sent.get("source").asText()).isEqualTo("crawler");
        assertThat(sent.get("external_id").asText()).isEqualTo(a);

        // 적재하지 않기로 한 것은 다시 와도 대기 목록에 오르지 않습니다
        long other = intakeCatalog("artists", "{\"source\":\"crawler\",\"external_id\":\"r-" + a + "\",\"name\":\"중복\"}").at("/items/0/id").asLong();
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + other + "/reject"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("REJECTED"));
        assertThat(intakeCatalog("artists", "{\"source\":\"crawler\",\"external_id\":\"r-" + a + "\",\"name\":\"중복2\"}")
                .at("/items/0/result").asText()).isEqualTo("REVIEWED");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/999999/reject"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CRAWL_CATALOG_NOT_FOUND"));
    }

    @Test
    void physicalOptionsNeedAPriceBeforeApproval() throws Exception {
        String a = "a-" + UUID.randomUUID(), b = "b-" + UUID.randomUUID();
        long artistId = intakeCatalog("artists", "{\"source\":\"crawler\",\"external_id\":\"" + a + "\",\"name\":\"검정치마\"}").at("/items/0/id").asLong();
        String physical = "\"physical_albums\":[{\"external_id\":\"discogs-5\",\"format\":\"LP\",\"name\":\"LP\",\"options\":[{\"name\":\"Black\",\"price\":%d}]}]";
        String album = "{\"source\":\"crawler\",\"external_id\":\"" + b + "\",\"title\":\"201\",\"type\":\"ALBUM\","
                + "\"artist_ref\":{\"source\":\"crawler\",\"external_id\":\"" + a + "\"}," + physical + "}";
        long albumId = intakeCatalog("albums", album.formatted(0)).at("/items/0/id").asLong();
        Session goods = newAdmin("GOODS");
        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"id\":51,\"result\":\"CREATED\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + artistId + "/approve"))).andExpect(status().isOk());

        // 크롤러가 보낸 0원 그대로는 승인되지 않음 (여러 건 승인도 같음) — Catalog 로 아무것도 가지 않음
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + albumId + "/approve")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CRAWL_PRICE_REQUIRED"));
        JsonNode many = result(mvc.perform(as(goods, post("/api/studio/crawl/catalog/approve")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + albumId + "]}")).andExpect(status().isOk()).andReturn());
        assertThat(many.get(0).get("code").asText()).isEqualTo("CRAWL_PRICE_REQUIRED");
        assertThat(STUB.received("POST", "/internal/admin/catalog/ingest/albums")).isEmpty();

        // 관리자가 가격을 넣어 승인
        STUB.ok("POST", "/internal/admin/catalog/ingest/albums", "{\"items\":[{\"external_id\":\"" + b + "\",\"id\":52,\"result\":\"CREATED\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + albumId + "/approve")).contentType(MediaType.APPLICATION_JSON)
                .content(album.formatted(39000))).andExpect(status().isOk()).andExpect(jsonPath("$.result.catalogId").value(52));
        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/catalog/ingest/albums").get(0).bodyText()).at("/items/0");
        assertThat(sent.at("/physical_albums/0/options/0/price").asLong()).isEqualTo(39000);
    }

    @Test
    void goodsNeedAPriceAndEvidenceStaysInStudio() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String artist = "{\"source\":\"crawler\",\"external_id\":\"" + a + "\",\"name\":\"실리카겔\","
                + "\"goods\":[{\"external_id\":\"web-1\",\"name\":\"로고 티셔츠 블랙\",\"category\":\"TSHIRT\","
                + "\"options\":[{\"name\":\"S\",\"size\":\"S\",\"price\":%d}]}],"
                + "\"evidence\":[{\"for\":\"goods:web-1\",\"site\":\"websearch\",\"url\":\"https://store.example/tee\"}]}";
        long id = intakeCatalog("artists", artist.formatted(0)).at("/items/0/id").asLong();
        Session goods = newAdmin("GOODS");

        // 목록에서는 출처가 보이고, 0원 굿즈는 승인되지 않음
        JsonNode listed = result(mvc.perform(as(goods, get("/api/studio/crawl/catalog").param("type", "ARTIST"))).andExpect(status().isOk()).andReturn());
        JsonNode mine = null;
        for (JsonNode c : listed) if (c.get("id").asLong() == id) mine = c;
        assertThat(mine.at("/item/evidence/0/url").asText()).isEqualTo("https://store.example/tee");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CRAWL_PRICE_REQUIRED"));
        assertThat(STUB.received("POST", "/internal/admin/catalog/ingest/artists").stream()
                .anyMatch(r -> r.bodyText().contains(a))).isFalse();

        // 가격을 넣어 승인 → Catalog 로는 출처 없이
        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"id\":61,\"result\":\"CREATED\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                .content(artist.formatted(35000))).andExpect(status().isOk()).andExpect(jsonPath("$.result.catalogId").value(61));
        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/catalog/ingest/artists").stream()
                .filter(r -> r.bodyText().contains(a)).findFirst().orElseThrow().bodyText()).at("/items/0");
        assertThat(sent.at("/goods/0/options/0/price").asLong()).isEqualTo(35000);
        assertThat(sent.has("evidence")).isFalse();
    }

    @Test
    void approveManyDoesArtistsFirst() throws Exception {
        String a = "a-" + UUID.randomUUID(), b = "b-" + UUID.randomUUID();
        long artistId = intakeCatalog("artists", "{\"source\":\"crawler\",\"external_id\":\"" + a + "\",\"name\":\"한로로\"}").at("/items/0/id").asLong();
        long albumId = intakeCatalog("albums", "{\"source\":\"crawler\",\"external_id\":\"" + b + "\",\"title\":\"집\",\"type\":\"ALBUM\","
                + "\"artist_ref\":{\"source\":\"crawler\",\"external_id\":\"" + a + "\"}}").at("/items/0/id").asLong();
        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"id\":31,\"result\":\"CREATED\"}]}");
        STUB.ok("POST", "/internal/admin/catalog/ingest/albums", "{\"items\":[{\"external_id\":\"" + b + "\",\"id\":32,\"result\":\"CREATED\"}]}");

        JsonNode results = result(mvc.perform(as(newAdmin("GOODS"), post("/api/studio/crawl/catalog/approve")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + albumId + "," + artistId + ",999999]}")).andExpect(status().isOk()).andReturn());
        assertThat(results.get(0).get("id").asLong()).isEqualTo(artistId);
        assertThat(results.get(1).get("approved").asBoolean()).isTrue();
        assertThat(results.get(1).get("catalogId").asLong()).isEqualTo(32);
        assertThat(results.get(2).get("code").asText()).isEqualTo("CRAWL_CATALOG_NOT_FOUND");
    }

    /** 11/18 — 이미지는 우리 버킷에 올린 주소만 · 판매가 null 은 그대로 Catalog 로 (기존 옵션은 판매가 유지 · 새 옵션은 Catalog 가 거절) */
    @Test
    void catalogApprovalAcceptsOnlyUploadedImagesAndPassesNullPrices() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String artist = "{\"source\":\"crawler\",\"external_id\":\"" + a + "\",\"name\":\"혁오\",\"profile_url\":null,"
                + "\"goods\":[{\"external_id\":\"w-1\",\"name\":\"티셔츠\",\"category\":\"TSHIRT\",\"thumbnail_url\":null,"
                + "\"options\":[{\"name\":\"M\",\"size\":\"M\",\"price\":null}]}]}";
        long id = intakeCatalog("artists", artist).at("/items/0/id").asLong();
        Session goods = newAdmin("GOODS");

        String foreign = artist.replace("\"profile_url\":null", "\"profile_url\":\"https://elsewhere.example/a.jpg\"");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON).content(foreign))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_URL_NOT_ALLOWED"));
        String notUploaded = artist.replace("\"thumbnail_url\":null", "\"thumbnail_url\":\"" + FakeImageStore.BASE + "goods/2026/11/x.jpg\"");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON).content(notUploaded))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_NOT_UPLOADED"));
        assertThat(STUB.received("POST", "/internal/admin/catalog/ingest/artists")).isEmpty();

        String profile = imageStore.uploadedImage(com.indie.studio.domain.image.ImageKind.ARTIST);
        String tee = imageStore.uploadedImage(com.indie.studio.domain.image.ImageKind.GOODS);
        STUB.ok("POST", "/internal/admin/catalog/ingest/artists", "{\"items\":[{\"external_id\":\"" + a + "\",\"id\":61,\"result\":\"UPDATED\"}]}");
        mvc.perform(as(goods, post("/api/studio/crawl/catalog/" + id + "/approve")).contentType(MediaType.APPLICATION_JSON)
                        .content(artist.replace("\"profile_url\":null", "\"profile_url\":\"" + profile + "\"")
                                .replace("\"thumbnail_url\":null", "\"thumbnail_url\":\"" + tee + "\"")))
                .andExpect(status().isOk());
        JsonNode sent = objectMapper.readTree(STUB.received("POST", "/internal/admin/catalog/ingest/artists").get(0).bodyText()).at("/items/0");
        assertThat(sent.get("profile_url").asText()).isEqualTo(profile);
        assertThat(sent.at("/goods/0/thumbnail_url").asText()).isEqualTo(tee);
        assertThat(sent.at("/goods/0/options/0/price").isNull()).isTrue();          // 판매가는 Catalog 에 저장된 값 그대로
    }

    @Test
    void catalogIntakeChecksOnlyKeysAndDiffGoesThrough() throws Exception {
        // 앨범에는 artist_ref 가 필요 · 열쇠가 없으면 요청 전체 400
        mvc.perform(crawler(post("/internal/crawl/catalog/albums"), crawlerToken())
                        .content("{\"items\":[{\"source\":\"crawler\",\"external_id\":\"x\",\"title\":\"t\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(crawler(post("/internal/crawl/catalog/artists"), crawlerToken()).content("{\"items\":[{\"name\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(crawler(post("/internal/crawl/catalog/artists"), crawlerToken()).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        STUB.ok("POST", "/internal/admin/catalog/ingest/diff", "{\"new\":[\"b1\"],\"changed\":[],\"unchanged\":[]}");
        mvc.perform(crawler(post("/internal/crawl/catalog/diff"), crawlerToken())
                        .content("{\"source\":\"crawler\",\"type\":\"ALBUM\",\"items\":[{\"external_id\":\"b1\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.new[0]").value("b1"));
        var call = STUB.received("POST", "/internal/admin/catalog/ingest/diff").get(0);
        assertThat(call.headers()).doesNotContainKey("x-admin-id");
    }

    private JsonNode intakeCatalog(String kind, String item) throws Exception {
        return result(mvc.perform(crawler(post("/internal/crawl/catalog/" + kind), crawlerToken()).content("{\"items\":[" + item + "]}"))
                .andExpect(status().isOk()).andReturn());
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode c : list) if (c.get("id").asLong() == id) return c;
        throw new AssertionError("id " + id + " not in list");
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
