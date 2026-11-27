package com.indie.studio.domain.image;

import java.time.Instant;
import java.util.Map;

/**
 * 관리자 화면 -> S3 직접 업로드 또는 Studio의 허용 출처 이미지 가져오기.
 * 저장 전에 이미지 주소와 실제 업로드 여부를 확인합니다. 구현은 설정에 따라 S3ImageStore 또는 DisabledImageStore입니다.
 */
public interface ImageStore {

    Upload presign(ImageKind kind, String contentType, long size);

    /** 우리 버킷에 올라간 이미지 주소인지 확인합니다 - 아니면 GeneralException(ImageErrorCode) */
    void verify(String url);

    /** 허용한 곳의 이미지면 true (importFrom 으로 옮길 수 있음) */
    boolean importable(String url);

    /** 허용한 곳의 이미지를 받아 버킷에 올리고 우리 주소를 돌려줍니다 */
    String importFrom(String url, ImageKind kind);

    /** method, headers 그대로 uploadUrl 에 파일을 보냅니다 (서명에 Content-Type, 크기가 들어 있음) */
    record Upload(String uploadUrl, String method, Map<String, String> headers, String imageUrl, Instant expiresAt) {}
}
