package com.indie.studio.global.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 보존 기간이 지난 기록을 최대 1000건 삭제합니다. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM audit_log WHERE occurred_at < :cutoff ORDER BY id LIMIT 1000", nativeQuery = true)
    int deleteExpired(Instant cutoff);

    /** 최신순 - beforeId 보다 오래된 것부터 (커서) */
    @Query("select a from AuditLogEntity a where (:adminId is null or a.adminId = :adminId) "
            + "and (:beforeId is null or a.id < :beforeId) order by a.id desc")
    List<AuditLogEntity> page(Long adminId, Long beforeId, Pageable pageable);
}
