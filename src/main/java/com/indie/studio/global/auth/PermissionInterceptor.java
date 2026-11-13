package com.indie.studio.global.auth;

import com.indie.studio.global.audit.AuditLogEntity;
import com.indie.studio.global.audit.AuditLogRepository;
import com.indie.studio.global.common.exception.CommonErrorCode;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.common.util.ClientIp;
import com.indie.studio.global.common.util.Tokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.util.Set;

/**
 * 역할 검사 — 핸들러의 @RequirePermission 을 내 역할이 가졌는지 봅니다.
 * <pre>
 * ① 권한이 없거나 · 선언이 없는 핸들러 → 404 NOT_FOUND. 없는 경로와 응답이 같아서, 무대 관리자는 굿즈 API 가 있는지조차 알 수 없습니다.
 * ② 상태를 바꾸는 요청은 X-CSRF-Token 이 세션의 값과 같아야 합니다 (쿠키 인증이라 필요) → 403 CSRF_INVALID
 * ③ 요청 1건마다 작업 기록 1행 (조회 · 권한 밖 시도 포함)
 * </pre>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String STARTED = "studio.started";

    private final AuditLogRepository auditLogs;
    private final Clock clock;

    public PermissionInterceptor(AuditLogRepository auditLogs, Clock clock) {
        this.auditLogs = auditLogs;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTRIBUTE);
        if (admin == null || !(handler instanceof HandlerMethod method)) return true;   // 로그인 전 경로 · 없는 경로
        request.setAttribute(STARTED, System.nanoTime());

        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null || !admin.role().has(required.value())) {
            if (required == null) log.error("handler_without_permission handler={}", method.getShortLogMessage());
            record(request, admin, 404, AuditLogEntity.Result.DENIED);
            throw new GeneralException(CommonErrorCode.NOT_FOUND);
        }
        if (!SAFE_METHODS.contains(request.getMethod()) && !Tokens.same(request.getHeader(CSRF_HEADER), admin.csrfToken())) {
            record(request, admin, 403, AuditLogEntity.Result.FAILED);   // preHandle 에서 던지면 afterCompletion 이 불리지 않습니다
            throw new GeneralException(CommonErrorCode.CSRF_INVALID);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute(AdminPrincipal.ATTRIBUTE);
        if (admin == null || request.getAttribute(STARTED) == null) return;
        int status = ex != null && response.getStatus() < 400 ? 500 : response.getStatus();
        record(request, admin, status, status < 400 ? AuditLogEntity.Result.OK : AuditLogEntity.Result.FAILED);
    }

    /** 기록에 실패해도 요청은 그대로 둡니다 — 실패는 서버 로그에 남습니다 */
    private void record(HttpServletRequest request, AdminPrincipal admin, int status, AuditLogEntity.Result result) {
        try {
            Long started = (Long) request.getAttribute(STARTED);
            int durationMs = started == null ? 0 : (int) ((System.nanoTime() - started) / 1_000_000);
            String path = request.getRequestURI();
            auditLogs.save(new AuditLogEntity(clock.instant(), admin.id(), admin.role(), request.getMethod(),
                    path.length() > 255 ? path.substring(0, 255) : path, status, result, ClientIp.of(request), durationMs));
        } catch (RuntimeException e) {
            log.error("audit_log_failed adminId={} path={}", admin.id(), request.getRequestURI(), e);
        }
    }
}
