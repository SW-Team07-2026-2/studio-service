package com.indie.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.image.ImageKind;
import com.indie.studio.support.FakeImageStore;
import com.indie.studio.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 11/18 — 이미지 올리기(업로드 주소) · 저장 전 이미지 확인 · 아티스트 · 앨범 이미지 / 트랙 태그 · 공연 변경 공지 */
class ImageAndNoticeTest extends IntegrationTest {

    @Test
    void uploadUrlsAreSplitByRole() throws Exception {
        Session goods = newAdmin("GOODS");
        Session concert = newAdmin("CONCERT");

        JsonNode upload = result(mvc.perform(as(goods, post("/api/studio/products/images")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ALBUM\",\"contentType\":\"image/png\",\"size\":120000}")).andExpect(status().isOk()).andReturn());
        assertThat(upload.get("method").asText()).isEqualTo("PUT");
        assertThat(upload.at("/headers/Content-Type").asText()).isEqualTo("image/png");
        assertThat(upload.get("imageUrl").asText()).startsWith(FakeImageStore.BASE + "album/");

        mvc.perform(as(goods, post("/api/studio/products/images")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"POSTER\",\"contentType\":\"image/png\",\"size\":1}")).andExpect(status().isBadRequest());
        mvc.perform(as(goods, post("/api/studio/products/images")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ARTIST\",\"contentType\":\"image/svg+xml\",\"size\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"));
        mvc.perform(as(concert, post("/api/studio/concerts/images")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"POSTER\",\"contentType\":\"image/jpeg\",\"size\":1}")).andExpect(status().isOk());
        mvc.perform(as(concert, post("/api/studio/products/images")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ARTIST\",\"contentType\":\"image/jpeg\",\"size\":1}")).andExpect(status().isNotFound());   // 권한 밖
    }

    @Test
    void artistAlbumAndProductImagesMustBeUploadedFirst() throws Exception {
        Session goods = newAdmin("GOODS");
        STUB.ok("PATCH", "/internal/admin/catalog/artists/7", "{\"id\":7}");
        STUB.ok("PATCH", "/internal/admin/catalog/albums/9", "{\"id\":9}");
        STUB.ok("PATCH", "/internal/admin/catalog/products/11", "{\"id\":11}");

        mvc.perform(as(goods, patch("/api/studio/products/artists/7")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileUrl\":\"https://elsewhere.example/a.jpg\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_URL_NOT_ALLOWED"));
        mvc.perform(as(goods, patch("/api/studio/products/11")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"thumbnailUrl\":\"https://elsewhere.example/g.jpg\"}")).andExpect(status().isBadRequest());
        assertThat(STUB.received("PATCH", "/internal/admin/catalog/artists/7")).isEmpty();

        String profile = imageStore.uploadedImage(ImageKind.ARTIST);
        mvc.perform(as(goods, patch("/api/studio/products/artists/7")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileUrl\":\"" + profile + "\"}")).andExpect(status().isOk());
        String cover = imageStore.uploadedImage(ImageKind.ALBUM);
        String tags = "{\"thumbnailUrl\":\"" + cover + "\",\"tracks\":[{\"trackNo\":1,\"tempo\":\"MID\",\"moods\":[\"DREAMY\"]}]}";
        mvc.perform(as(goods, patch("/api/studio/products/albums/9")).contentType(MediaType.APPLICATION_JSON).content(tags))
                .andExpect(status().isOk());
        mvc.perform(as(goods, patch("/api/studio/products/11")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"이름만 바꿈\"}")).andExpect(status().isOk());                   // 이미지 칸이 없으면 확인 안 함

        assertThat(STUB.received("PATCH", "/internal/admin/catalog/artists/7").get(0).bodyText()).contains(profile);
        JsonNode sentAlbum = objectMapper.readTree(STUB.received("PATCH", "/internal/admin/catalog/albums/9").get(0).bodyText());
        assertThat(sentAlbum.at("/tracks/0/moods/0").asText()).isEqualTo("DREAMY");
    }

    @Test
    void concertImagesAndNotices() throws Exception {
        Session concert = newAdmin("CONCERT");
        STUB.ok("PATCH", "/internal/admin/concerts/5", "{\"id\":5}");
        mvc.perform(as(concert, patch("/api/studio/concerts/5")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageUrls\":[\"https://elsewhere.example/1.jpg\"]}")).andExpect(status().isBadRequest());
        String poster = imageStore.uploadedImage(ImageKind.POSTER);
        mvc.perform(as(concert, patch("/api/studio/concerts/5")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"posterUrl\":\"" + poster + "\",\"imageUrls\":[]}")).andExpect(status().isOk());

        STUB.ok("GET", "/internal/admin/concerts/5/notices", "[]");
        STUB.ok("POST", "/internal/admin/concerts/5/notices", "{\"id\":1,\"kind\":\"SCHEDULE\"}");
        STUB.ok("PATCH", "/internal/admin/concerts/5/notices/1", "{\"id\":1}");
        STUB.ok("DELETE", "/internal/admin/concerts/5/notices/1", "null");
        mvc.perform(as(concert, get("/api/studio/concerts/5/notices"))).andExpect(status().isOk());
        mvc.perform(as(concert, post("/api/studio/concerts/5/notices")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SCHEDULE\",\"title\":\"시간 변경\",\"body\":\"19시 → 18시\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.kind").value("SCHEDULE"));
        mvc.perform(as(concert, patch("/api/studio/concerts/5/notices/1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"19시 → 18시 30분\"}")).andExpect(status().isOk());
        mvc.perform(as(concert, delete("/api/studio/concerts/5/notices/1"))).andExpect(status().isOk());
        mvc.perform(as(newAdmin("GOODS"), get("/api/studio/concerts/5/notices"))).andExpect(status().isNotFound());   // 권한 밖

        var sent = STUB.received("POST", "/internal/admin/concerts/5/notices").get(0);
        assertThat(sent.bodyText()).contains("시간 변경");
        assertThat(sent.headers()).containsKey("x-admin-id");
        assertThat(STUB.received("DELETE", "/internal/admin/concerts/5/notices/1")).hasSize(1);
    }
}
