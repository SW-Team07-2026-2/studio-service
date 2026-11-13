package com.indie.studio.global.auth;

/** 로그인한 관리자 — SessionFilter 가 요청마다 DB 의 현재 계정 상태로 만듭니다 (역할 변경 · 비활성화 즉시 반영) */
public record AdminPrincipal(long id, String loginId, String name, Role role, long sessionId, String csrfToken) {

    /** 요청 속성 이름 — 컨트롤러는 @RequestAttribute(AdminPrincipal.ATTRIBUTE) 로 받습니다 */
    public static final String ATTRIBUTE = "studio.admin";
}
