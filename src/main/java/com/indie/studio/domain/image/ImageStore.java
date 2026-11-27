package com.indie.studio.domain.image;

import java.time.Instant;
import java.util.Map;

/**
 * 공개 이미지 저장소 (11/18 결정) — 관리자가 올린 파일 · KOPIS 포스터를 우리 버킷에 두고, DB 에는 우리 주소(img.indig.co.kr/...)만 저장합니다.
 * <pre>
 * 업로드  presign → 화면이 파일을 S3 로 바로 PUT (Studio · KT VM 을 거치지 않음) → imageUrl 을 저장할 값에 넣음
 * 저장 전 verify — 우리 주소 · 키 모양 · 실제로 올라가 있는지 (아니면 400)
 * 포스터  importFrom — 허용한 곳(KOPIS)의 이미지만 Studio 가 받아 버킷에 올리고 우리 주소를 돌려줌
 * </pre>
 * 구현은 S3ImageStore (app.image.bucket 이 있을 때) · DisabledImageStore (없을 때 — 이미지 칸은 비워 둔 채로만 저장).
 */
public interface ImageStore {

    Upload presign(ImageKind kind, String contentType, long size);

    /** 우리 버킷에 올라간 이미지 주소인지 확인합니다 — 아니면 GeneralException(ImageErrorCode) */
    void verify(String url);

    /** 허용한 곳의 이미지면 true (importFrom 으로 옮길 수 있음) */
    boolean importable(String url);

    /** 허용한 곳의 이미지를 받아 버킷에 올리고 우리 주소를 돌려줍니다 */
    String importFrom(String url, ImageKind kind);

    /** method · headers 그대로 uploadUrl 에 파일을 보냅니다 (서명에 Content-Type · 크기가 들어 있음) */
    record Upload(String uploadUrl, String method, Map<String, String> headers, String imageUrl, Instant expiresAt) {}
}
