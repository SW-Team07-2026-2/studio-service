package com.indie.studio.domain.stage.exception;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum StageErrorCode implements BaseErrorCode {
    STAGE_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "무대 그리기 작업을 찾을 수 없습니다"),
    STAGE_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "사진이 없습니다"),
    /** PNG · JPEG · WEBP 만 · 10MB 이하 (파일 앞부분으로 형식을 확인합니다) */
    STAGE_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "도면 사진은 PNG · JPEG · WEBP 형식, 10MB 이하여야 합니다"),
    STAGE_DRAWING_INVALID(HttpStatus.BAD_REQUEST, "그린 값(JSON)을 읽을 수 없습니다"),
    /** GPU 검증이 끝나 관리자 확인을 기다리는 작업(REVIEW)만 Concert 로 보낼 수 있습니다 */
    STAGE_JOB_NOT_REVIEWABLE(HttpStatus.CONFLICT, "검증이 끝난 작업만 보낼 수 있습니다"),
    STAGE_VALUES_INVALID(HttpStatus.BAD_REQUEST, "보낼 구역 값이 없습니다");

    private final HttpStatus status;
    private final String message;

    StageErrorCode(HttpStatus status, String message) {
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
