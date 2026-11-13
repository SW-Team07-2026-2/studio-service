package com.indie.studio.global.audit;

import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.auth.Role;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 작업 기록 조회 — 마스터 전용. 최신순이고, 다음 쪽은 마지막 항목의 id 를 beforeId 로 보냅니다 */
@RestController
public class AuditLogController {

    public record AuditLogView(long id, Instant occurredAt, long adminId, Role role, String method, String path,
                               int status, AuditLogEntity.Result result, String clientIp, int durationMs) {}

    private final AuditLogRepository auditLogs;

    public AuditLogController(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @GetMapping("/api/studio/audit-logs")
    @RequirePermission(Permission.AUDIT)
    public ApiResponse<List<AuditLogView>> list(@RequestParam(required = false) @Positive Long adminId,
                                                @RequestParam(required = false) @Positive Long beforeId,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return ApiResponse.onSuccess(auditLogs.page(adminId, beforeId, PageRequest.of(0, size)).stream()
                .map(a -> new AuditLogView(a.getId(), a.getOccurredAt(), a.getAdminId(), a.getRole(), a.getMethod(),
                        a.getPath(), a.getStatus(), a.getResult(), a.getClientIp(), a.getDurationMs()))
                .toList());
    }
}
