package com.indie.studio.global.client;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** AWS 서비스가 돌려준 4xx 를 그대로 옮긴 에러 — 상태 · code · message 가 원래 서비스의 것입니다 (409 VENUE_EXISTS 등) */
public record UpstreamError(HttpStatus status, String code, String message) implements BaseErrorCode {

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
