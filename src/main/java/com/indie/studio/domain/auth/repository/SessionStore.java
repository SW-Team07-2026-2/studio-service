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
import java.util.Set;

/**
 * 관리자 세션 토큰의 해시와 회원별 세션 목록을 Redis에 저장합니다.
 * 세션 만료와 무요청 시간은 저장된 시각으로 판정하고 TTL로 오래된 값을 정리합니다.
 * 권한이나 비밀번호 변경 시 회원별 세션을 모두 폐기합니다.
 */
@Repository
public class SessionStore {

    private static final String PREFIX = "studio:auth:session:";
    private static final String ADMIN_PREFIX = "studio:auth:admin-sessions:";

    // 세션 해시, 계정별 집합, 만료를 한 번에
    private static final RedisScript<Long> SAVE = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], unpack(ARGV, 3)) redis.call('PEXPIRE', KEYS[1], ARGV[1]) "
                    + "redis.call('SADD', KEYS[2], ARGV[2]) redis.call('PEXPIRE', KEYS[2], ARGV[1]) return 1", Long.class);
    // 키가 있을 때만 - 방금 끊긴 세션을 만료 없는 키로 되살리지 않게
    private static final RedisScript<Long> TOUCH = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('HSET', KEYS[1], 'lastSeenAt', ARGV[1]) end return -1", Long.class);

    public record Session(long adminId, String csrfToken, Instant expiresAt, Instant lastSeenAt) {}

    private final StringRedisTemplate redis;

    public SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(String tokenHash, long adminId, String csrfToken, String clientIp, Instant now, Instant expiresAt, Duration ttl) {
        String ms = String.valueOf(now.toEpochMilli());
        redis.execute(SAVE, List.of(PREFIX + tokenHash, ADMIN_PREFIX + adminId),
                String.valueOf(ttl.toMillis()), tokenHash,
                "adminId", String.valueOf(adminId), "csrf", csrfToken, "ip", clientIp,
                "createdAt", ms, "lastSeenAt", ms, "expiresAt", String.valueOf(expiresAt.toEpochMilli()));
    }

    public Optional<Session> find(String tokenHash) {
        HashOperations<String, String, String> hash = redis.opsForHash();
        Map<String, String> f = hash.entries(PREFIX + tokenHash);
        if (f.get("adminId") == null || f.get("csrf") == null || f.get("expiresAt") == null || f.get("lastSeenAt") == null) {
            return Optional.empty();
        }
        return Optional.of(new Session(Long.parseLong(f.get("adminId")), f.get("csrf"),
                Instant.ofEpochMilli(Long.parseLong(f.get("expiresAt"))), Instant.ofEpochMilli(Long.parseLong(f.get("lastSeenAt")))));
    }

    public void touch(String tokenHash, Instant now) {
        redis.execute(TOUCH, List.of(PREFIX + tokenHash), String.valueOf(now.toEpochMilli()));
    }

    public void revoke(long adminId, String tokenHash) {
        redis.delete(PREFIX + tokenHash);
        redis.opsForSet().remove(ADMIN_PREFIX + adminId, tokenHash);
    }

    /**
     * 그 계정의 세션을 모두 끊습니다 - 역할, 상태, 비밀번호, OTP 가 바뀔 때.
     * MySQL 트랜잭션 밖이라 계정 변경이 롤백돼도 세션은 끊긴 채입니다 (다시 로그인하면 됨, 반대 방향의 위험은 없음).
     */
    public void revokeAll(long adminId) {
        revokeExcept(adminId, null);
    }

    /** 비밀번호를 스스로 바꿨을 때 - 지금 세션만 남깁니다 */
    public void revokeOthers(long adminId, String keepTokenHash) {
        revokeExcept(adminId, keepTokenHash);
    }

    private void revokeExcept(long adminId, String keepTokenHash) {
        Set<String> members = redis.opsForSet().members(ADMIN_PREFIX + adminId);
        if (members == null) return;
        for (String tokenHash : members) {
            if (!tokenHash.equals(keepTokenHash)) revoke(adminId, tokenHash);
        }
    }
}
