package com.indie.studio.global.audit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** 관리자 작업 기록을 6개월 보존하고 지난 기록을 매일 삭제합니다. */
@Component
public class AuditRetentionJob {
    private final AuditLogRepository logs;
    private final Clock clock;

    public AuditRetentionJob(AuditLogRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    public void purge() {
        var cutoff = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC)).minusMonths(6).toInstant();
        for (int batch = 0; batch < 500; batch++) {
            if (logs.deleteExpired(cutoff) < 1000) break;
        }
    }
}
