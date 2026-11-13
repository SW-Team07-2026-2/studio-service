package com.indie.studio.domain.auth.service;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.domain.account.repository.AdminAccountRepository;
import com.indie.studio.domain.account.service.PasswordPolicy;
import com.indie.studio.domain.auth.entity.LoginChallengeEntity;
import com.indie.studio.domain.auth.entity.SessionEntity;
import com.indie.studio.domain.auth.exception.AuthErrorCode;
import com.indie.studio.domain.auth.repository.LoginChallengeRepository;
import com.indie.studio.domain.auth.repository.SessionRepository;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.common.util.Tokens;
import com.indie.studio.global.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 관리자 로그인 — 1차 아이디 · 비밀번호(DB · bcrypt) → 2차 OTP → 세션.
 * <pre>
 * login      비밀번호가 맞으면 2단계 대기(5분 · 1회용)를 만듭니다. OTP 가 없는 계정이면 새 비밀키를 대기에 실어 등록을 함께 합니다.
 * verifyOtp  코드가 맞으면 세션을 만듭니다. 같은 30초 단계의 코드는 한 번만 통과합니다 (어깨 너머로 본 코드 재사용 차단).
 * authenticate  요청마다 — 세션과 계정의 지금 상태를 DB 에서 봅니다. 그래서 역할 변경 · 비활성화가 즉시 반영됩니다.
 * </pre>
 * 실패 횟수는 예외를 던지기 전에 저장합니다 (메서드에 트랜잭션을 걸지 않음 — 걸면 롤백으로 횟수가 사라집니다).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_CHALLENGE_ATTEMPTS = 5;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final AdminAccountRepository accounts;
    private final LoginChallengeRepository challenges;
    private final SessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final SecretCipher cipher;
    private final StudioProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(AdminAccountRepository accounts, LoginChallengeRepository challenges, SessionRepository sessions,
                       PasswordEncoder passwordEncoder, SecretCipher cipher, StudioProperties props, Clock clock) {
        this.accounts = accounts;
        this.challenges = challenges;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(Tokens.random());   // 없는 아이디도 bcrypt 를 한 번 돌려 응답 시간으로 아이디 유무를 못 가리게
    }

    public record LoginResult(String challengeToken, String next, String otpauthUri, String secret) {}

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

        byte[] enrollSecret = account.getTotpSecretEnc() == null ? Totp.newSecret() : null;
        String token = Tokens.random();
        challenges.save(new LoginChallengeEntity(Tokens.sha256(token), account.getId(),
                enrollSecret == null ? null : cipher.encrypt(enrollSecret), clientIp, now,
                now.plus(Duration.ofMinutes(props.login().challengeMinutes()))));
        if (enrollSecret == null) return new LoginResult(token, "OTP", null, null);
        return new LoginResult(token, "OTP_ENROLL",
                Totp.otpauthUri(props.totp().issuer(), account.getLoginId(), enrollSecret), Totp.base32(enrollSecret));
    }

    public SessionIssued verifyOtp(String challengeToken, String code, String clientIp) {
        Instant now = clock.instant();
        LoginChallengeEntity challenge = challengeToken == null ? null
                : challenges.findByTokenHash(Tokens.sha256(challengeToken)).orElse(null);
        if (challenge == null || challenge.getUsedAt() != null || !challenge.getExpiresAt().isAfter(now)
                || challenge.getAttempts() >= MAX_CHALLENGE_ATTEMPTS) {
            throw new GeneralException(AuthErrorCode.OTP_CHALLENGE_EXPIRED);
        }
        AdminAccountEntity account = accounts.findById(challenge.getAdminId()).orElse(null);
        boolean enroll = challenge.getEnrollSecretEnc() != null;
        // 대기 중에 계정이 꺼졌거나 · 잠겼거나 · OTP 등록 상태가 바뀌었으면(다른 창에서 먼저 등록 · 마스터가 초기화) 처음부터 다시
        if (account == null || !account.isActive() || account.isLocked(now) || enroll != (account.getTotpSecretEnc() == null)) {
            throw new GeneralException(AuthErrorCode.OTP_CHALLENGE_EXPIRED);
        }

        byte[] secretEnc = enroll ? challenge.getEnrollSecretEnc() : account.getTotpSecretEnc();
        long step = Totp.matchStep(cipher.decrypt(secretEnc), code, now);
        if (step < 0 || (account.getTotpLastStep() != null && step <= account.getTotpLastStep())) {
            challenge.failedAttempt();
            challenges.save(challenge);
            fail(account, now);
            throw new GeneralException(AuthErrorCode.OTP_INVALID);
        }
        if (challenges.markUsed(challenge.getId(), now) != 1) throw new GeneralException(AuthErrorCode.OTP_CHALLENGE_EXPIRED);

        account.loginSucceeded(enroll ? secretEnc : null, step, now);
        accounts.save(account);
        String token = Tokens.random();
        SessionEntity session = sessions.save(new SessionEntity(Tokens.sha256(token), account.getId(), Tokens.random(), clientIp,
                now, now.plus(Duration.ofHours(props.session().ttlHours()))));
        log.info("admin_login adminId={} role={} enrolled={}", account.getId(), account.getRole(), enroll);
        return new SessionIssued(token, principal(account, session));
    }

    /** 세션 쿠키 → 관리자. 없거나 · 만료됐거나 · 계정이 꺼졌으면 null */
    public AdminPrincipal authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return null;
        Instant now = clock.instant();
        SessionEntity session = sessions.findByTokenHashAndRevokedAtIsNull(Tokens.sha256(sessionToken)).orElse(null);
        if (session == null || !session.getExpiresAt().isAfter(now)
                || !session.getLastSeenAt().plus(Duration.ofMinutes(props.session().idleMinutes())).isAfter(now)) {
            return null;
        }
        AdminAccountEntity account = accounts.findById(session.getAdminId()).orElse(null);
        if (account == null || !account.isActive()) return null;
        if (session.getLastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {   // 요청마다 쓰지 않고 1분에 한 번만
            session.seen(now);
            sessions.save(session);
        }
        return principal(account, session);
    }

    public void logout(AdminPrincipal admin) {
        sessions.revoke(admin.sessionId(), clock.instant());
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
        sessions.revokeOthers(account.getId(), admin.sessionId(), now);
    }

    /** 만료된 2단계 대기 · 세션 정리 — 하루 지난 것만 (1시간마다) */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purgeExpired() {
        Instant before = clock.instant().minus(Duration.ofDays(1));
        int c = challenges.deleteExpired(before);
        int s = sessions.deleteExpired(before);
        if (c + s > 0) log.info("auth_purge challenges={} sessions={}", c, s);
    }

    private void fail(AdminAccountEntity account, Instant now) {
        account.recordFailure(now, props.login().maxFailures(), Duration.ofMinutes(props.login().lockMinutes()));
        accounts.save(account);
        if (account.isLocked(now)) log.warn("admin_locked adminId={} until={}", account.getId(), account.getLockedUntil());
    }

    private static AdminPrincipal principal(AdminAccountEntity account, SessionEntity session) {
        return new AdminPrincipal(account.getId(), account.getLoginId(), account.getName(), account.getRole(),
                session.getId(), session.getCsrfToken());
    }
}
