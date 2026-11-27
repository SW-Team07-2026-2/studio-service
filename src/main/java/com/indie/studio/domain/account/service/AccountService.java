package com.indie.studio.domain.account.service;

import com.indie.studio.domain.account.dto.AccountDtos.AccountView;
import com.indie.studio.domain.account.dto.AccountDtos.CreateRequest;
import com.indie.studio.domain.account.dto.AccountDtos.UpdateRequest;
import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.account.exception.AccountErrorCode;
import com.indie.studio.domain.account.repository.AdminAccountRepository;
import com.indie.studio.domain.auth.repository.SessionStore;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.common.exception.GeneralException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 관리자 계정 관리 - 마스터만 부릅니다 (Permission.ACCOUNT).
 * 역할, 상태, 비밀번호가 바뀌면 그 계정의 세션을 모두 끊습니다. 계정은 지우지 않고 DISABLED 로 둡니다.
 */
@Service
public class AccountService {

    private final AdminAccountRepository accounts;
    private final SessionStore sessions;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountService(AdminAccountRepository accounts, SessionStore sessions, PasswordEncoder passwordEncoder, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 관리자 계정 목록을 조회합니다. */
    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findAll().stream().map(AccountView::of).toList();
    }

    /**
     * 관리자 계정 정보를 등록합니다.
     *
     * @param request 생성할 관리자 계정 정보
     */
    @Transactional
    public AccountView create(CreateRequest request) {
        if (accounts.existsByLoginId(request.loginId())) throw new GeneralException(AccountErrorCode.LOGIN_ID_TAKEN);
        PasswordPolicy.check(request.loginId(), request.password());
        return AccountView.of(accounts.save(new AdminAccountEntity(request.loginId(), request.name().trim(),
                passwordEncoder.encode(request.password()), request.role(), request.otpEmails(), clock.instant())));
    }

    /**
     * 관리자 계정 정보를 수정합니다.
     *
     * @param id 관리자 계정 ID
     * @param request 변경할 관리자 계정 정보
     * @param actor 작업을 수행할 관리자와 권한 정보
     */
    @Transactional
    public AccountView update(long id, UpdateRequest request, AdminPrincipal actor) {
        AdminAccountEntity account = other(id, actor, request.role() != null || request.status() != null);
        Instant now = clock.instant();
        boolean accessChanged = (request.role() != null && request.role() != account.getRole())
                || (request.status() != null && request.status() != account.getStatus());
        account.update(request.name() == null ? null : request.name().trim(), request.role(), request.status(), request.otpEmails(), now);
        if (accessChanged) sessions.revokeAll(account.getId());
        return AccountView.of(account);
    }

    /**
     * 비밀번호를 새로 정해 줍니다 - 잠금도 풀립니다
     *
     * @param id 관리자 계정 ID
     * @param password 관리자 비밀번호
     * @param actor 작업을 수행할 관리자와 권한 정보
     */
    @Transactional
    public void resetPassword(long id, String password, AdminPrincipal actor) {
        AdminAccountEntity account = other(id, actor, true);
        PasswordPolicy.check(account.getLoginId(), password);
        Instant now = clock.instant();
        account.changePassword(passwordEncoder.encode(password), now);
        sessions.revokeAll(account.getId());
    }

    private AdminAccountEntity other(long id, AdminPrincipal actor, boolean forbidSelf) {
        if (forbidSelf && id == actor.id()) throw new GeneralException(AccountErrorCode.CANNOT_CHANGE_SELF);
        return accounts.findById(id).orElseThrow(() -> new GeneralException(AccountErrorCode.ACCOUNT_NOT_FOUND));
    }
}
