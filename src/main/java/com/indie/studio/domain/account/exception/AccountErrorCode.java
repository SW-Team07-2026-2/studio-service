package com.indie.studio.domain.account.exception;

import com.indie.studio.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum AccountErrorCode implements BaseErrorCode {
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "관리자 계정을 찾을 수 없습니다"),
    LOGIN_ID_TAKEN(HttpStatus.CONFLICT, "이미 쓰고 있는 아이디입니다"),
    /** 내 역할 · 상태 · 비밀번호 초기화 · OTP 초기화는 계정 관리 화면에서 못 합니다 — 마스터가 스스로를 잠그지 않게 (활성 마스터가 항상 1명 남음) */
    CANNOT_CHANGE_SELF(HttpStatus.CONFLICT, "내 계정은 여기서 바꿀 수 없습니다"),
    PASSWORD_POLICY(HttpStatus.BAD_REQUEST, "비밀번호는 12자 이상 72바이트 이하이고 아이디를 포함할 수 없습니다");

    private final HttpStatus status;
    private final String message;

    AccountErrorCode(HttpStatus status, String message) {
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
