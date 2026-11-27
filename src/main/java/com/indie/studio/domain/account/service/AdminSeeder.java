package com.indie.studio.domain.account.service;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.account.repository.AdminAccountRepository;
import com.indie.studio.global.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 설정된 관리자 계정을 최초 등록합니다. 이미 존재하는 계정은 변경하지 않으며 잘못된 입력은 기동 실패로 처리합니다. */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9.]{4,32}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]?\\$\\d\\d\\$[./0-9A-Za-z]{53}");

    private final AdminAccountRepository accounts;
    private final String admins;
    private final Clock clock;

    public AdminSeeder(AdminAccountRepository accounts, @Value("${bootstrap.admins:}") String admins, Clock clock) {
        this.accounts = accounts;
        this.admins = admins;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] lines = admins.split("\\R");
        for (int n = 0; n < lines.length; n++) {
            int i = n;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",", -1);
            if (f.length != 5) throw invalid(i, "칸이 5개가 아닙니다");
            String loginId = f[0].strip();
            String name = f[1].strip();
            String hash = f[4].strip();
            List<String> emails = Arrays.stream(f[3].split(";")).map(String::strip).toList();
            if (!LOGIN_ID.matcher(loginId).matches()) throw invalid(i, "아이디는 영문 소문자 · 숫자 · 점 4~32자");
            if (name.isEmpty() || name.length() > 50) throw invalid(i, "이름은 1~50자");
            Role role = Arrays.stream(Role.values()).filter(r -> r.name().equals(f[2].strip())).findFirst()
                    .orElseThrow(() -> invalid(i, "역할은 " + Arrays.toString(Role.values()) + " 중 하나"));
            if (emails.isEmpty() || emails.size() > 5 || !emails.stream().allMatch(e -> e.length() <= 100 && EMAIL.matcher(e).matches())) {
                throw invalid(i, "메일은 1~5개 (; 로 구분)");
            }
            if (!BCRYPT.matcher(hash).matches()) throw invalid(i, "비밀번호는 bcrypt 해시 (원문을 넣지 않습니다)");
            if (accounts.existsByLoginId(loginId)) continue;
            accounts.save(new AdminAccountEntity(loginId, name, hash, role, emails, clock.instant()));
            log.info("admin_seeded loginId={} role={} emails={}", loginId, role, emails.size());
        }
        if (accounts.count() == 0) log.warn("admin_seed_empty — 관리자 계정이 없어 아무도 로그인할 수 없습니다 (bootstrap.admins)");
    }

    private static IllegalStateException invalid(int index, String reason) {
        return new IllegalStateException("bootstrap.admins " + (index + 1) + "번째 줄: " + reason);
    }
}
