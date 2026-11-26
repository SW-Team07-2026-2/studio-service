package com.indie.studio.support;

import com.indie.studio.domain.image.ImageErrorCode;
import com.indie.studio.domain.image.ImageKind;
import com.indie.studio.domain.image.ImageStore;
import com.indie.studio.global.common.exception.GeneralException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 테스트용 이미지 저장소 — https://img.test/ 아래에 "올린" 주소만 통과 · KOPIS 주소는 옮긴 것으로 기록 (진짜 S3 동작은 S3ImageStoreTest) */
public class FakeImageStore implements ImageStore {

    public static final String BASE = "https://img.test/";

    private final Set<String> uploaded = ConcurrentHashMap.newKeySet();
    public final List<String> imported = new CopyOnWriteArrayList<>();

    /** 화면이 업로드를 마친 것처럼 */
    public String uploadedImage(ImageKind kind) {
        String url = BASE + kind.path() + "/2026/11/" + UUID.randomUUID() + ".jpg";
        uploaded.add(url);
        return url;
    }

    @Override
    public Upload presign(ImageKind kind, String contentType, long size) {
        if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(contentType)) {
            throw new GeneralException(ImageErrorCode.IMAGE_TYPE_NOT_ALLOWED);
        }
        String url = BASE + kind.path() + "/2026/11/" + UUID.randomUUID() + ".jpg";
        return new Upload("https://s3.test/bucket/" + url.substring(BASE.length()) + "?X-Amz-Signature=x", "PUT",
                Map.of("Content-Type", contentType), url, Instant.now().plusSeconds(300));
    }

    @Override
    public void verify(String url) {
        if (url == null || !url.startsWith(BASE)) throw new GeneralException(ImageErrorCode.IMAGE_URL_NOT_ALLOWED);
        if (!uploaded.contains(url)) throw new GeneralException(ImageErrorCode.IMAGE_NOT_UPLOADED);
    }

    @Override
    public boolean importable(String url) {
        return url != null && (url.startsWith("http://www.kopis.or.kr/") || url.startsWith("https://www.kopis.or.kr/"));
    }

    /** 11/26 상세 이미지 — https://shop.example/ 아래만 받은 것으로 (broken 이 들어가면 못 받음) */
    @Override
    public String importPublic(String url, ImageKind kind) {
        if (!url.startsWith("https://shop.example/")) throw new GeneralException(ImageErrorCode.IMAGE_URL_NOT_ALLOWED);
        return importFrom(url, kind);
    }

    @Override
    public String importFrom(String url, ImageKind kind) {
        if (url.contains("broken")) throw new GeneralException(ImageErrorCode.IMAGE_IMPORT_FAILED);
        imported.add(url);
        return uploadedImage(kind);
    }
}
