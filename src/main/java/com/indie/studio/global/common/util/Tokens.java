package com.indie.studio.global.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 쿠키에 싣는 무작위 토큰 - DB 에는 SHA-256 해시만 둡니다 (DB 가 새도 세션을 훔칠 수 없게) */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** 세션에 사용할 무작위 토큰을 생성합니다. */
    public static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 지정한 자릿수의 무작위 숫자를 생성합니다.
     *
     * @param count 생성할 숫자의 자릿수
     */
    public static String digits(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    /**
     * 토큰의 SHA-256 해시를 구합니다.
     *
     * @param value 해시를 구할 문자열
     */
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 두 문자열을 상수 시간으로 비교합니다.
     *
     * @param a 비교할 첫 문자열
     * @param b 비교할 두 번째 문자열
     */
    public static boolean same(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
