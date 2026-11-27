package com.indie.studio.domain.image;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum ImageErrorCode implements BaseErrorCode {
    /** app.image.bucket 이 비어 있음 - 이미지 칸을 비운 채로만 저장할 수 있습니다 */
    IMAGE_STORE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소가 설정되지 않았습니다"),
    IMAGE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "jpg · png · webp · gif 이미지만 올릴 수 있습니다"),
    IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지가 너무 큽니다"),
    /** 우리 이미지 주소(app.image.public-base-url)가 아님 - 외부 주소는 받지 않습니다. 파일을 올려 받은 주소를 쓰세요 */
    IMAGE_URL_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "올린 이미지의 주소만 저장할 수 있습니다"),
    /** 받은 업로드 주소로 파일이 올라가지 않았음 (업로드 실패, 시간 초과) */
    IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "이미지가 올라가지 않았습니다. 다시 올려 주세요"),
    /** KOPIS 포스터를 받아 오지 못함 - 관리자가 직접 올립니다 */
    IMAGE_IMPORT_FAILED(HttpStatus.BAD_GATEWAY, "포스터를 가져오지 못했습니다. 직접 올려 주세요");

    private final HttpStatus status;
    private final String message;

    ImageErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
