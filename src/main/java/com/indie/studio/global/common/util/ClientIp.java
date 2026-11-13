package com.indie.studio.global.common.util;

import jakarta.servlet.http.HttpServletRequest;

/** 관리자 입구는 Cloudflare 뒤에 있습니다 — KT 인그레스가 Cloudflare 대역만 받으므로 CF-Connecting-IP 를 씁니다 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        return cf != null && !cf.isBlank() && cf.length() <= 45 ? cf.trim() : request.getRemoteAddr();
    }
}
