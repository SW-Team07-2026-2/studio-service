package com.indie.studio.domain.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 저장하기 전에 본문의 이미지 칸을 확인합니다 (11/18) — 보낸 칸만 · null 은 그대로.
 * 우리 버킷에 실제로 올라간 주소만 Catalog · Concert 로 넘어갑니다 (외부 주소 · 올리다 만 주소는 400).
 */
@Component
public class ImageGuard {

    private final ImageStore store;

    public ImageGuard(ImageStore store) {
        this.store = store;
    }

    /** 본문의 이 칸들 — 글자면 그 주소, 배열이면 주소마다 */
    public void check(JsonNode body, String... fields) {
        if (body == null) return;
        for (String field : fields) {
            JsonNode value = body.get(field);
            if (value == null || value.isNull()) continue;
            if (value.isArray()) value.forEach(this::verify);
            else verify(value);
        }
    }

    /** 크롤링 아티스트 · 앨범 후보 (snake_case) — 아티스트 · 앨범 이미지와 굿즈 · 피지컬 앨범 이미지 */
    public void checkCatalogItem(JsonNode item) {
        check(item, "profile_url", "thumbnail_url");
        for (JsonNode goods : item.path("goods")) check(goods, "thumbnail_url");
        for (JsonNode physical : item.path("physical_albums")) check(physical, "thumbnail_url");
    }

    /** 크롤링 공연 등록 — KOPIS 포스터 · 소개 이미지는 Studio 가 받아 버킷으로 옮긴 주소로 바꾸고, 나머지는 확인합니다 */
    public void importConcertImages(ObjectNode concert) {
        JsonNode poster = concert.get("posterUrl");
        if (poster != null && poster.isTextual() && store.importable(poster.asText())) {
            concert.put("posterUrl", store.importFrom(poster.asText(), ImageKind.POSTER));
        }
        if (concert.get("imageUrls") instanceof ArrayNode images) {
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).isTextual() && store.importable(images.get(i).asText())) {
                    images.set(i, store.importFrom(images.get(i).asText(), ImageKind.CONCERT));
                }
            }
        }
        check(concert, "posterUrl", "imageUrls");
    }

    private void verify(JsonNode url) {
        if (url.isNull()) return;
        store.verify(url.isTextual() ? url.asText() : url.toString());
    }
}
