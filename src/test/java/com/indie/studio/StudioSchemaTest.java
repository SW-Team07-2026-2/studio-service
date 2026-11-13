package com.indie.studio;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.stage.entity.StageJobEntity;
import com.indie.studio.domain.stage.entity.StageJobImageEntity;
import com.indie.studio.global.audit.AuditLogEntity;
import com.indie.studio.global.auth.Role;
import com.indie.studio.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스키마 — 이 테스트 컨텍스트가 뜬 것 자체가 Flyway(V1 구조 · V2 값)와 엔티티의 일치(ddl-auto: validate)를 확인합니다.
 * 여기서는 V2 의 CHECK 값 목록과 Java enum 이 같은지 봅니다 (한쪽만 고치면 깨짐).
 */
class StudioSchemaTest extends IntegrationTest {

    private static final Pattern VALUE = Pattern.compile("[A-Z][A-Z_]*");

    @Autowired
    private JdbcTemplate jdbc;

    /** 마이그레이션은 V1(구조) · V2(값) 두 파일 */
    @Test
    void migrationsAreSchemaThenValues() {
        assertThat(jdbc.queryForList("select concat(version, ' ', description) from flyway_schema_history "
                + "where success and version is not null order by installed_rank", String.class))
                .containsExactly("1 studio schema", "2 studio values");
    }

    @Test
    void checkConstraintValuesMatchJavaEnums() {
        Map<String, Set<String>> checks = new HashMap<>();
        jdbc.query("select constraint_name, check_clause from information_schema.check_constraints where constraint_schema = database()", rs -> {
            Set<String> values = new TreeSet<>();
            Matcher m = VALUE.matcher(rs.getString(2));
            while (m.find()) values.add(m.group());
            checks.put(rs.getString(1), values);
        });

        assertThat(checks.get("ck_admin_role")).isEqualTo(names(Role.values()));
        assertThat(checks.get("ck_admin_status")).isEqualTo(names(AdminAccountEntity.Status.values()));
        assertThat(checks.get("ck_stage_job_status")).isEqualTo(names(StageJobEntity.Status.values()));
        assertThat(checks.get("ck_stage_job_image_kind")).isEqualTo(names(StageJobImageEntity.Kind.values()));
        assertThat(checks.get("ck_audit_result")).isEqualTo(names(AuditLogEntity.Result.values()));
    }

    @Test
    void storesOnlyHashesAndCiphertext() {
        // 세션 · 2단계 대기는 쿠키 값의 해시(64자 hex)만, 비밀번호는 bcrypt, OTP 비밀키는 암호문(IV 12 + 20 + 태그 16)
        assertThat(jdbc.queryForList("select token_hash from admin_session", String.class)).allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(jdbc.queryForList("select token_hash from admin_login_challenge", String.class)).allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(jdbc.queryForList("select password_hash from admin_account", String.class)).isNotEmpty().allMatch(h -> h.startsWith("$2a$"));
        assertThat(jdbc.queryForList("select length(totp_secret_enc) from admin_account where totp_secret_enc is not null", Integer.class))
                .allMatch(n -> n == 48);
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }
}
