package com.indie.studio.domain.account.service;

import com.indie.studio.domain.account.dto.AccountDtos.AccountView;
import com.indie.studio.domain.account.dto.AccountDtos.CreateRequest;
import com.indie.studio.domain.account.dto.AccountDtos.UpdateRequest;
import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.account.exception.AccountErrorCode;
import com.indie.studio.domain.account.repository.AdminAccountRepository;
import com.indie.studio.domain.auth.repository.SessionRepository;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.common.exception.GeneralException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 관리자 계정 관리 — 마스터만 부릅니다 (Permission.ACCOUNT).
 * 역할 · 상태 · 비밀번호 · OTP 가 바뀌면 그 계정의 세션을 모두 끊습니다. 계정은 지우지 않고 DISABLED 로 둡니다.
 */
@Service
public class AccountService {

    private final AdminAccountRepository accounts;
    private final SessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountService(AdminAccountRepository accounts, SessionRepository sessions, PasswordEncoder passwordEncoder, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findAll().stream().map(AccountView::of).toList();
    }

    @Transactional
    public AccountView create(CreateRequest request) {
        if (accounts.existsByLoginId(request.loginId())) throw new GeneralException(AccountErrorCode.LOGIN_ID_TAKEN);
        PasswordPolicy.check(request.loginId(), request.password());
        return AccountView.of(accounts.save(new AdminAccountEntity(request.loginId(), request.name().trim(),
                passwordEncoder.encode(request.password()), request.role(), clock.instant())));
    }

    @Transactional
    public AccountView update(long id, UpdateRequest request, AdminPrincipal actor) {
        AdminAccountEntity account = other(id, actor, request.role() != null || request.status() != null);
        Instant now = clock.instant();
        boolean accessChanged = (request.role() != null && request.role() != account.getRole())
                || (request.status() != null && request.status() != account.getStatus());
        account.update(request.name() == null ? null : request.name().trim(), request.role(), request.status(), now);
        if (accessChanged) sessions.revokeAll(account.getId(), now);
        return AccountView.of(account);
    }

    /** 비밀번호를 새로 정해 줍니다 — 잠금도 풀립니다 */
    @Transactional
    public void resetPassword(long id, String password, AdminPrincipal actor) {
        AdminAccountEntity account = other(id, actor, true);
        PasswordPolicy.check(account.getLoginId(), password);
        Instant now = clock.instant();
        account.changePassword(passwordEncoder.encode(password), now);
        sessions.revokeAll(account.getId(), now);
    }

    /** OTP 기기 분실 — 다음 로그인 때 다시 등록합니다 */
    @Transactional
    public void resetOtp(long id, AdminPrincipal actor) {
        AdminAccountEntity account = other(id, actor, true);
        Instant now = clock.instant();
        account.resetOtp(now);
        sessions.revokeAll(account.getId(), now);
    }

    private AdminAccountEntity other(long id, AdminPrincipal actor, boolean forbidSelf) {
        if (forbidSelf && id == actor.id()) throw new GeneralException(AccountErrorCode.CANNOT_CHANGE_SELF);
        return accounts.findById(id).orElseThrow(() -> new GeneralException(AccountErrorCode.ACCOUNT_NOT_FOUND));
    }
}
