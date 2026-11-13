package com.indie.studio.domain.account.service;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.account.repository.AdminAccountRepository;
import com.indie.studio.global.auth.Role;
import com.indie.studio.global.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 첫 마스터 계정 — 계정이 하나도 없을 때만 app.bootstrap.master-login-id + 시크릿 bootstrap.master-password 로 만듭니다.
 * 계정이 하나라도 있으면 아무것도 하지 않습니다 (시크릿을 바꿔도 기존 비밀번호는 안 바뀜). 첫 로그인 뒤 비밀번호를 바꾸고 시크릿을 지우세요.
 */
@Component
public class MasterBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterBootstrap.class);

    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final StudioProperties.Bootstrap props;
    private final String password;
    private final Clock clock;

    public MasterBootstrap(AdminAccountRepository accounts, PasswordEncoder passwordEncoder, StudioProperties props,
                           @Value("${bootstrap.master-password:}") String password, Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.props = props.bootstrap();
        this.password = password.trim();
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.count() > 0) return;
        String loginId = props.masterLoginId() == null ? "" : props.masterLoginId().trim();
        if (loginId.isEmpty() || password.isEmpty()) {
            log.warn("master_bootstrap_skipped reason=no_login_id_or_password — 관리자 계정이 없어 아무도 로그인할 수 없습니다");
            return;
        }
        PasswordPolicy.check(loginId, password);
        accounts.save(new AdminAccountEntity(loginId, props.masterName(), passwordEncoder.encode(password), Role.MASTER, clock.instant()));
        log.info("master_bootstrap_created loginId={}", loginId);
    }
}
