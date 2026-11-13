package com.indie.studio.domain.auth.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/** TOTP (RFC 6238) — HMAC-SHA1 · 6자리 · 30초. Google Authenticator 등 OTP 앱 기본값과 같습니다 */
public final class Totp {

    private static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** 160bit 비밀키 (RFC 4226 권장 길이) */
    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(Instant at) {
        return at.getEpochSecond() / STEP_SECONDS;
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 맞으면 그 코드의 단계, 틀리면 -1. 기기 시계 차이를 봐서 앞뒤 한 단계(±30초)까지 받습니다 */
    public static long matchStep(byte[] secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return -1;
        long current = step(now);
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        for (long candidate : new long[]{current, current - 1, current + 1}) {
            if (MessageDigest.isEqual(code(secret, candidate).getBytes(StandardCharsets.US_ASCII), given)) return candidate;
        }
        return -1;
    }

    /** OTP 앱에 넣는 비밀키 표기 (패딩 없음) */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        return out.toString();
    }

    /** OTP 앱이 QR 로 읽는 주소 — 프론트가 이 문자열로 QR 을 그립니다 */
    public static String otpauthUri(String issuer, String loginId, byte[] secret) {
        String i = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20");
        String label = i + ":" + URLEncoder.encode(loginId, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + i + "&algorithm=SHA1&digits=6&period=30";
    }
}
