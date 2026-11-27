package com.indie.studio;

import com.indie.studio.global.audit.AuditLogRepository;
import com.indie.studio.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.sql.Timestamp;
import static org.assertj.core.api.Assertions.assertThat;

class AuditRetentionTest extends IntegrationTest {
    @Autowired AuditLogRepository logs;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deletesOnlyRecordsStrictlyBeforeCutoff() {
        var cutoff = Instant.parse("2000-06-01T00:00:00Z");
        for (var at : new Instant[]{cutoff.minusSeconds(1), cutoff, cutoff.plusSeconds(1)}) {
            jdbc.update("INSERT INTO audit_log (occurred_at, admin_id, role, method, path, status, result, client_ip, duration_ms) "
                    + "SELECT ?, id, role, 'GET', '/retention-test', 200, 'OK', '127.0.0.1', 1 FROM admin_account LIMIT 1", Timestamp.from(at));
        }
        assertThat(logs.deleteExpired(cutoff)).isEqualTo(1);
        assertThat(logs.deleteExpired(cutoff)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE path = '/retention-test'", Long.class)).isEqualTo(2);
    }
}
