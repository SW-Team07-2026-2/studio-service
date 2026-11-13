package com.indie.studio;

import com.indie.studio.domain.auth.service.Totp;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 부록 B 의 SHA1 시험값 (8자리의 뒤 6자리) */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109L)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890L)))).isEqualTo("005924");
    }

    @Test
    void acceptsOneStepOfClockDriftAndReportsTheStep() {
        Instant now = Instant.ofEpochSecond(1111111109L);
        long current = Totp.step(now);
        assertThat(Totp.matchStep(RFC_SECRET, "081804", now)).isEqualTo(current);
        assertThat(Totp.matchStep(RFC_SECRET, Totp.code(RFC_SECRET, current - 1), now)).isEqualTo(current - 1);
        assertThat(Totp.matchStep(RFC_SECRET, Totp.code(RFC_SECRET, current + 1), now)).isEqualTo(current + 1);
        assertThat(Totp.matchStep(RFC_SECRET, Totp.code(RFC_SECRET, current - 2), now)).isEqualTo(-1);
        assertThat(Totp.matchStep(RFC_SECRET, "81804", now)).isEqualTo(-1);
        assertThat(Totp.matchStep(RFC_SECRET, null, now)).isEqualTo(-1);
    }

    @Test
    void encodesSecretForOtpApps() {
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");   // RFC 4648 — OTP 앱 문서의 예시 키
        assertThat(Totp.otpauthUri("indie-studio", "stage.kim", RFC_SECRET))
                .isEqualTo("otpauth://totp/indie-studio:stage.kim?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=indie-studio&algorithm=SHA1&digits=6&period=30");
    }
}
