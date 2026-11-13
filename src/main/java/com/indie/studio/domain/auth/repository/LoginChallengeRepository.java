package com.indie.studio.domain.auth.repository;

import com.indie.studio.domain.auth.entity.LoginChallengeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface LoginChallengeRepository extends JpaRepository<LoginChallengeEntity, Long> {

    Optional<LoginChallengeEntity> findByTokenHash(String tokenHash);

    /** 1회용 — 아직 안 쓴 것만 쓴 것으로 바꿉니다. 1 이 아니면 누군가 먼저 썼습니다 */
    @Transactional
    @Modifying
    @Query("update LoginChallengeEntity c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int markUsed(long id, Instant now);

    @Transactional
    @Modifying
    @Query("delete from LoginChallengeEntity c where c.expiresAt < :before")
    int deleteExpired(Instant before);
}
