package com.indie.studio.domain.account.dto;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.global.auth.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 관리자 계정 관리 (마스터 전용) 요청 · 응답 */
public final class AccountDtos {

    private AccountDtos() {}

    /**
     * loginId — 영문 소문자 · 숫자 · 점 4~32자.
     * otpEmails — 로그인 인증 코드를 받을 메일 1~5개. 역할별 공용 계정이면 담당자 메일을 모두 (같은 코드가 모두에게 갑니다)
     */
    public record CreateRequest(@NotNull @Pattern(regexp = "[a-z0-9.]{4,32}") String loginId,
                                @NotBlank @Size(max = 50) String name,
                                @NotNull Role role,
                                @NotEmpty @Size(max = 5) List<@NotBlank @Email @Size(max = 100) String> otpEmails,
                                @NotBlank @Size(max = 128) String password) {}

    /** 보낸 값만 바꿉니다 (null 은 그대로) */
    public record UpdateRequest(@Size(min = 1, max = 50) String name, Role role, AdminAccountEntity.Status status,
                                @Size(min = 1, max = 5) List<@NotBlank @Email @Size(max = 100) String> otpEmails) {}

    public record PasswordResetRequest(@NotBlank @Size(max = 128) String password) {}

    public record AccountView(long id, String loginId, String name, Role role, AdminAccountEntity.Status status,
                              List<String> otpEmails, Instant lockedUntil, Instant lastLoginAt, Instant createdAt) {
        public static AccountView of(AdminAccountEntity a) {
            return new AccountView(a.getId(), a.getLoginId(), a.getName(), a.getRole(), a.getStatus(),
                    a.getOtpEmails(), a.getLockedUntil(), a.getLastLoginAt(), a.getCreatedAt());
        }
    }
}
