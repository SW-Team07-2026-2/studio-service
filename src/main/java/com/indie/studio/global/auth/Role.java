package com.indie.studio.global.auth;

import java.util.EnumSet;
import java.util.Set;

/**
 * 역할 — 계정마다 하나. 하위 관리자는 자기 역할의 권한만 가지고, 다른 역할의 화면 · API 가 있는지도 알 수 없습니다.
 * 값을 바꾸면 V1__studio_schema.sql 의 ck_admin_role 도 함께 고칩니다.
 */
public enum Role {
    /** 마스터 — 모든 기능 + 관리자 계정 · 작업 기록 */
    MASTER(EnumSet.allOf(Permission.class)),
    /** 무대 관리자 — 공연장 추가 · 무대 그리기 */
    STAGE(EnumSet.of(Permission.SELF, Permission.VENUE, Permission.STAGE_DRAW)),
    /** 공연 관리자 — 공연 상세 · 약관 · 이미지 · 요구사항 · 좌석별 가격 */
    CONCERT(EnumSet.of(Permission.SELF, Permission.CONCERT, Permission.SEAT_PRICE)),
    /** 굿즈 관리자 — 굿즈 · 피지컬 앨범 */
    GOODS(EnumSet.of(Permission.SELF, Permission.PRODUCT, Permission.STOCK)),
    /** CS 관리자 — 회원의 티켓팅 · 굿즈 · 피지컬 앨범 구매 문의 */
    CS(EnumSet.of(Permission.SELF, Permission.CS_READ, Permission.CS_CANCEL));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<Permission> permissions() { return permissions; }
}
