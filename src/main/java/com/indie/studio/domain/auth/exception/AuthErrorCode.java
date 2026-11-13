package com.indie.studio.domain.auth.exception;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements BaseErrorCode {
    /** 아이디가 없든 · 비밀번호가 틀리든 · 잠겼든 · 비활성화든 같은 응답 — 무엇이 틀렸는지 알려 주지 않습니다 */
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다"),
    OTP_INVALID(HttpStatus.UNAUTHORIZED, "OTP 코드가 올바르지 않습니다"),
    /** 2단계 대기가 없거나 · 5분이 지났거나 · 이미 썼거나 · 5번 틀림 → 1차 로그인부터 다시 */
    OTP_CHALLENGE_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인 시간이 지났습니다. 아이디와 비밀번호부터 다시 입력해 주세요"),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다");

    private final HttpStatus status;
    private final String message;

    AuthErrorCode(HttpStatus status, String message) {
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
