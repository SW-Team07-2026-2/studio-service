package com.indie.studio.global.common.exception;

import org.springframework.http.HttpStatus;

/** 도메인에 속하지 않는 공통 에러 */
public enum CommonErrorCode implements BaseErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다. 새로고침 후 다시 시도해 주세요"),
    /** 없는 경로와 권한 밖 경로가 같은 응답입니다 — 다른 역할의 기능이 있는지조차 알 수 없게 */
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않는 메서드입니다"),
    CONFLICT(HttpStatus.CONFLICT, "요청을 처리할 수 없습니다"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다"),
    /** AWS 서비스가 Studio 의 내부 토큰을 거절(401 · 403)했거나 5xx 를 돌려줌 — 관리자 세션 문제가 아니므로 401 로 내보내지 않습니다 */
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "연결된 서비스에서 오류가 발생했습니다"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "연결된 서비스에 닿지 못했습니다. 잠시 후 다시 시도해 주세요");

    private final HttpStatus status;
    private final String message;

    CommonErrorCode(HttpStatus status, String message) {
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
