package com.indie.studio.global.auth;

import java.util.Arrays;
import java.util.List;

/** 대시보드 탭 — /me 가 내 역할에 해당하는 것만 내려줍니다. 프론트는 이 목록에 없는 탭을 그리지 않습니다 */
public enum Menu {
    VENUES("venues", "공연장 · 무대", Permission.VENUE),
    CONCERTS("concerts", "공연", Permission.CONCERT),
    PRODUCTS("products", "굿즈 · 피지컬 앨범", Permission.PRODUCT),
    CS("cs", "CS", Permission.CS_READ),
    CHECKIN("checkin", "검표", Permission.CHECK_IN),
    ACCOUNTS("accounts", "관리자 계정", Permission.ACCOUNT),
    AUDIT("audit", "작업 기록", Permission.AUDIT);

    private final String key;
    private final String label;
    private final Permission permission;

    Menu(String key, String label, Permission permission) {
        this.key = key;
        this.label = label;
        this.permission = permission;
    }

    public record Item(String key, String label) {}

    public static List<Item> of(Role role) {
        return Arrays.stream(values()).filter(m -> role.has(m.permission)).map(m -> new Item(m.key, m.label)).toList();
    }
}
