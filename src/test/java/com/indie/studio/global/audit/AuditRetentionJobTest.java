package com.indie.studio.global.audit;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.mockito.Mockito.*;

class AuditRetentionJobTest {
    @Test
    void usesSixCalendarMonthsAndContinuesFullBatches() {
        var logs = mock(AuditLogRepository.class);
        var cutoff = Instant.parse("2026-02-28T00:00:00Z");
        when(logs.deleteExpired(cutoff)).thenReturn(1000, 1);
        new AuditRetentionJob(logs, Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC)).purge();
        verify(logs, times(2)).deleteExpired(cutoff);
        verifyNoMoreInteractions(logs);
    }

    @Test
    void stopsWhenNothingIsExpired() {
        var logs = mock(AuditLogRepository.class);
        var cutoff = Instant.parse("2026-05-27T00:00:00Z");
        new AuditRetentionJob(logs, Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC)).purge();
        verify(logs).deleteExpired(cutoff);
        verifyNoMoreInteractions(logs);
    }
}
