package com.indie.studio.domain.auth.repository;

import com.indie.studio.domain.auth.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** 그 계정의 세션을 모두 끊습니다 — 역할 · 상태 · 비밀번호 · OTP 가 바뀔 때 */
    @Transactional
    @Modifying
    @Query("update SessionEntity s set s.revokedAt = :now where s.adminId = :adminId and s.revokedAt is null")
    int revokeAll(long adminId, Instant now);

    /** 비밀번호를 스스로 바꿨을 때 — 지금 세션만 남깁니다 */
    @Transactional
    @Modifying
    @Query("update SessionEntity s set s.revokedAt = :now where s.adminId = :adminId and s.id <> :keepId and s.revokedAt is null")
    int revokeOthers(long adminId, long keepId, Instant now);

    @Transactional
    @Modifying
    @Query("update SessionEntity s set s.revokedAt = :now where s.id = :id and s.revokedAt is null")
    int revoke(long id, Instant now);

    @Transactional
    @Modifying
    @Query("delete from SessionEntity s where s.expiresAt < :before")
    int deleteExpired(Instant before);
}
