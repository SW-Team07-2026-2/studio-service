package com.indie.studio.global.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 최신순 — beforeId 보다 오래된 것부터 (커서) */
    @Query("select a from AuditLogEntity a where (:adminId is null or a.adminId = :adminId) "
            + "and (:beforeId is null or a.id < :beforeId) order by a.id desc")
    List<AuditLogEntity> page(Long adminId, Long beforeId, Pageable pageable);
}
