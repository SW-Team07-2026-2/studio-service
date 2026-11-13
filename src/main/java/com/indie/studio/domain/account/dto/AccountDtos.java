package com.indie.studio.domain.account.dto;

import com.indie.studio.domain.account.entity.AdminAccountEntity;
import com.indie.studio.global.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 관리자 계정 관리 (마스터 전용) 요청 · 응답 */
public final class AccountDtos {

    private AccountDtos() {}

    /** loginId — 영문 소문자 · 숫자 · 점 4~32자. 사람마다 하나 (공용 계정 금지) */
    public record CreateRequest(@NotNull @Pattern(regexp = "[a-z0-9.]{4,32}") String loginId,
                                @NotBlank @Size(max = 50) String name,
                                @NotNull Role role,
                                @NotBlank @Size(max = 128) String password) {}

    /** 보낸 값만 바꿉니다 (null 은 그대로) */
    public record UpdateRequest(@Size(min = 1, max = 50) String name, Role role, AdminAccountEntity.Status status) {}

    public record PasswordResetRequest(@NotBlank @Size(max = 128) String password) {}

    public record AccountView(long id, String loginId, String name, Role role, AdminAccountEntity.Status status,
                              boolean otpEnrolled, Instant lockedUntil, Instant lastLoginAt, Instant createdAt) {
        public static AccountView of(AdminAccountEntity a) {
            return new AccountView(a.getId(), a.getLoginId(), a.getName(), a.getRole(), a.getStatus(),
                    a.getTotpSecretEnc() != null, a.getLockedUntil(), a.getLastLoginAt(), a.getCreatedAt());
        }
    }
}
