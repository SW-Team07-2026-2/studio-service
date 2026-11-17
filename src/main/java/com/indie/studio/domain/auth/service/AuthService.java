package com.indie.studio.domain.auth.service;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.account.repository.AdminAccountRepository;
import com.indie.studio.domain.account.service.PasswordPolicy;
import com.indie.studio.domain.auth.exception.AuthErrorCode;
import com.indie.studio.domain.auth.repository.LoginChallengeStore;
import com.indie.studio.domain.auth.repository.SessionStore;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.common.util.Tokens;
import com.indie.studio.global.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 관리자 로그인 — 1차 아이디 · 비밀번호(MySQL · bcrypt) → 2차 메일 인증 코드 → 세션. 2단계 대기와 세션은 Redis 에 둡니다 (만료되면 Redis 가 지움).
 * <pre>
 * login      비밀번호가 맞으면 2단계 대기(5분 · 1회용)를 만들고, 계정에 등록된 메일로 6자리 코드를 보냅니다. 대기에는 코드의 해시만 둡니다.
 * verifyOtp  코드가 맞으면 세션을 만듭니다. 대기는 한 번 쓰면 지워지고, 5번 틀리면 버립니다 (코드는 로그인마다 새로 만듭니다).
 * authenticate  요청마다 — 세션(Redis)과 계정의 지금 상태(MySQL)를 봅니다. 그래서 역할 변경 · 비활성화가 즉시 반영됩니다.
 * </pre>
 * 실패 횟수는 예외를 던지기 전에 저장합니다 (메서드에 트랜잭션을 걸지 않음 — 걸면 롤백으로 횟수가 사라집니다).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_CHALLENGE_ATTEMPTS = 5;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final AdminAccountRepository accounts;
    private final LoginChallengeStore challenges;
    private final SessionStore sessions;
    private final PasswordEncoder passwordEncoder;
    private final OtpMailer mailer;
    private final StudioProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(AdminAccountRepository accounts, LoginChallengeStore challenges, SessionStore sessions,
                       PasswordEncoder passwordEncoder, OtpMailer mailer, StudioProperties props, Clock clock) {
        this.accounts = accounts;
        this.challenges = challenges;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(Tokens.random());   // 없는 아이디도 bcrypt 를 한 번 돌려 응답 시간으로 아이디 유무를 못 가리게
    }

    /** sentTo — 코드를 보낸 주소 (가림 처리) */
    public record LoginResult(String challengeToken, List<String> sentTo) {}

    public record SessionIssued(String sessionToken, AdminPrincipal admin) {}

    public LoginResult login(String loginId, String password, String clientIp) {
        Instant now = clock.instant();
        AdminAccountEntity account = accounts.findByLoginId(loginId).orElse(null);
        if (account == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new GeneralException(AuthErrorCode.LOGIN_FAILED);
        }
        if (!account.isActive() || account.isLocked(now)) throw new GeneralException(AuthErrorCode.LOGIN_FAILED);
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            fail(account, now);
            throw new GeneralException(AuthErrorCode.LOGIN_FAILED);
        }

        String code = Tokens.digits(6);
        String token = Tokens.random();
        String challengeHash = Tokens.sha256(token);
        Duration ttl = Duration.ofMinutes(props.login().challengeMinutes());
        challenges.save(challengeHash, account.getId(), codeHash(token, code), clientIp, now.plus(ttl), ttl);
        try {
            mailer.send(account.getLoginId(), account.getOtpEmails(), code, ttl);
        } catch (MailException e) {
            challenges.consume(challengeHash);
            log.error("otp_mail_failed adminId={} reason={}", account.getId(), e.getMessage());
            throw new GeneralException(AuthErrorCode.OTP_MAIL_FAILED);
        }
        return new LoginResult(token, account.getOtpEmails().stream().map(AuthService::mask).toList());
    }

    public SessionIssued verifyOtp(String challengeToken, String code, String clientIp) {
        Instant now = clock.instant();
        String challengeHash = challengeToken == null ? null : Tokens.sha256(challengeToken);
        LoginChallengeStore.Challenge challenge = challengeHash == null ? null : challenges.find(challengeHash).orElse(null);
        if (challenge == null || !challenge.expiresAt().isAfter(now) || challenge.attempts() >= MAX_CHALLENGE_ATTEMPTS) {
            throw new GeneralException(AuthErrorCode.OTP_CHALLENGE_EXPIRED);
        }
        AdminAccountEntity account = accounts.findById(challenge.adminId()).orElse(null);
        // 대기 중에 계정이 꺼졌거나 잠겼으면 처음부터 다시
        if (account == null || !account.isActive() || account.isLocked(now)) {
            throw new GeneralException(AuthErrorCode.OTP_CHALLENGE_EXPIRED);
        }

        if (!Tokens.same(codeHash(challengeToken, code), challenge.codeHash())) {
            challenges.failedAttempt(challengeHash);
            fail(account, now);
            throw new GeneralException(AuthErrorCode.OTP_INVALID);
        }
        if (!challenges.consume(challengeHash)) throw new GeneralException(AuthErrorCode.OTP_CHALLENGE_EXPIRED);   // 1회용 — 먼저 지운 쪽만

        account.loginSucceeded(now);
        accounts.save(account);
        String token = Tokens.random();
        String tokenHash = Tokens.sha256(token);
        String csrfToken = Tokens.random();
        Duration ttl = Duration.ofHours(props.session().ttlHours());
        sessions.save(tokenHash, account.getId(), csrfToken, clientIp, now, now.plus(ttl), ttl);
        log.info("admin_login adminId={} role={}", account.getId(), account.getRole());
        return new SessionIssued(token, principal(account, tokenHash, csrfToken));
    }

    /** 세션 쿠키 → 관리자. 없거나 · 만료됐거나 · 계정이 꺼졌으면 null */
    public AdminPrincipal authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return null;
        Instant now = clock.instant();
        String tokenHash = Tokens.sha256(sessionToken);
        SessionStore.Session session = sessions.find(tokenHash).orElse(null);
        if (session == null || !session.expiresAt().isAfter(now)
                || !session.lastSeenAt().plus(Duration.ofMinutes(props.session().idleMinutes())).isAfter(now)) {
            return null;
        }
        AdminAccountEntity account = accounts.findById(session.adminId()).orElse(null);
        if (account == null || !account.isActive()) return null;
        if (session.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {   // 요청마다 쓰지 않고 1분에 한 번만
            sessions.touch(tokenHash, now);
        }
        return principal(account, tokenHash, session.csrfToken());
    }

    public void logout(AdminPrincipal admin) {
        sessions.revoke(admin.id(), admin.sessionId());
    }

    /** 내 비밀번호 변경 — 다른 기기의 세션은 끊고 지금 세션만 남깁니다 */
    public void changePassword(AdminPrincipal admin, String currentPassword, String newPassword) {
        AdminAccountEntity account = accounts.findById(admin.id()).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new GeneralException(AuthErrorCode.PASSWORD_MISMATCH);
        }
        PasswordPolicy.check(account.getLoginId(), newPassword);
        Instant now = clock.instant();
        account.changePassword(passwordEncoder.encode(newPassword), now);
        accounts.save(account);
        sessions.revokeOthers(account.getId(), admin.sessionId());
    }

    private void fail(AdminAccountEntity account, Instant now) {
        account.recordFailure(now, props.login().maxFailures(), Duration.ofMinutes(props.login().lockMinutes()));
        accounts.save(account);
        if (account.isLocked(now)) log.warn("admin_locked adminId={} until={}", account.getId(), account.getLockedUntil());
    }

    /** 대기에 두는 값 — 쿠키 값(Redis 에 없음)을 섞어, Redis 만 봐서는 6자리를 되짚을 수 없게 */
    private static String codeHash(String challengeToken, String code) {
        return Tokens.sha256(challengeToken + ":" + code);
    }

    /** ab***@example.com */
    private static String mask(String email) {
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    private static AdminPrincipal principal(AdminAccountEntity account, String sessionTokenHash, String csrfToken) {
        return new AdminPrincipal(account.getId(), account.getLoginId(), account.getName(), account.getRole(),
                sessionTokenHash, csrfToken);
    }
}
