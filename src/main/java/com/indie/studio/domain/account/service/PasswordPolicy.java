package com.indie.studio.domain.account.service;

import com.indie.studio.domain.account.exception.AccountErrorCode;
import com.indie.studio.global.common.exception.GeneralException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 12자 이상, bcrypt 한계(72바이트) 이하, 아이디 포함 금지. 주기적 변경은 강제하지 않습니다 (NIST 800-63B) */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    /**
     * 관리자 비밀번호 길이와 아이디 포함 여부를 검증합니다.
     *
     * @param loginId 관리자 로그인 ID
     * @param password 관리자 비밀번호
     */
    public static void check(String loginId, String password) {
        boolean ok = password != null && password.length() >= 12
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && !password.toLowerCase(Locale.ROOT).contains(loginId.toLowerCase(Locale.ROOT));
        if (!ok) throw new GeneralException(AccountErrorCode.PASSWORD_POLICY);
    }
}
