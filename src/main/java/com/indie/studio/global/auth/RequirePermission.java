package com.indie.studio.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 핸들러를 부르는 데 필요한 권한. 🔴 /api/studio/** 핸들러에 이 선언이 없으면 아무도 부를 수 없습니다 (기본 거부 · 404).
 * 로그인 전 엔드포인트(SessionFilter.PUBLIC_PATHS)만 예외입니다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    Permission value();
}
