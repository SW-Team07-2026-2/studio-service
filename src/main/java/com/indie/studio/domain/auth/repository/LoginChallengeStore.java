package com.indie.studio.domain.auth.repository;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 로그인 2단계 대기 — 1차(아이디 · 비밀번호)를 통과한 사람만 가집니다. 5분 · 1회용. 메일로 보낸 인증 코드의 해시를 함께 둡니다.
 * Redis 해시 {@code studio:auth:challenge:<SHA-256(쿠키 값)>} — 쿠키 값의 해시만 둡니다.
 * Redis 만료(TTL)는 청소용이고, 만료 판정은 저장한 expiresAt 과 앱 시계(Clock)로 합니다.
 */
@Repository
public class LoginChallengeStore {

    private static final String PREFIX = "studio:auth:challenge:";

    // HSET 과 만료를 한 번에 — 사이에 죽어도 만료 없는 키가 남지 않게
    private static final RedisScript<Long> SAVE = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], unpack(ARGV, 2)) redis.call('PEXPIRE', KEYS[1], ARGV[1]) return 1", Long.class);
    // 키가 있을 때만 — 방금 쓰여 지워진 대기를 만료 없는 키로 되살리지 않게
    private static final RedisScript<Long> FAILED_ATTEMPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('HINCRBY', KEYS[1], 'attempts', 1) end return -1", Long.class);

    public record Challenge(long adminId, String codeHash, int attempts, Instant expiresAt) {}

    private final StringRedisTemplate redis;

    public LoginChallengeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** codeHash — 인증 코드 원문은 두지 않습니다 (AuthService.codeHash) */
    public void save(String tokenHash, long adminId, String codeHash, String clientIp, Instant expiresAt, Duration ttl) {
        redis.execute(SAVE, List.of(PREFIX + tokenHash), String.valueOf(ttl.toMillis()),
                "adminId", String.valueOf(adminId), "code", codeHash, "ip", clientIp, "attempts", "0",
                "expiresAt", String.valueOf(expiresAt.toEpochMilli()));
    }

    public Optional<Challenge> find(String tokenHash) {
        HashOperations<String, String, String> hash = redis.opsForHash();
        Map<String, String> f = hash.entries(PREFIX + tokenHash);
        if (f.get("adminId") == null || f.get("code") == null || f.get("expiresAt") == null) return Optional.empty();
        return Optional.of(new Challenge(Long.parseLong(f.get("adminId")), f.get("code"),
                Integer.parseInt(f.getOrDefault("attempts", "0")),
                Instant.ofEpochMilli(Long.parseLong(f.get("expiresAt")))));
    }

    public void failedAttempt(String tokenHash) {
        redis.execute(FAILED_ATTEMPT, List.of(PREFIX + tokenHash));
    }

    /** 1회용 — 지운 쪽만 true. false 면 누군가 먼저 썼거나 만료됐습니다 */
    public boolean consume(String tokenHash) {
        return Boolean.TRUE.equals(redis.delete(PREFIX + tokenHash));
    }
}
