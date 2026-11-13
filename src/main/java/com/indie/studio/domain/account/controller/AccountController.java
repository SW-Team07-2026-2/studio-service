package com.indie.studio.domain.account.controller;

import com.indie.studio.domain.account.dto.AccountDtos.AccountView;
import com.indie.studio.domain.account.dto.AccountDtos.CreateRequest;
import com.indie.studio.domain.account.dto.AccountDtos.PasswordResetRequest;
import com.indie.studio.domain.account.dto.AccountDtos.UpdateRequest;
import com.indie.studio.domain.account.service.AccountService;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 관리자 계정 관리 — 마스터 전용. 다른 역할에게는 이 경로가 404 입니다 */
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/api/studio/accounts")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<List<AccountView>> list() {
        return ApiResponse.onSuccess(accountService.list());
    }

    @PostMapping("/api/studio/accounts")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<AccountView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.onSuccess(accountService.create(request));
    }

    /** 이름 · 역할 · 상태(ACTIVE · DISABLED). 역할 · 상태가 바뀌면 그 계정은 바로 로그아웃됩니다 */
    @PatchMapping("/api/studio/accounts/{id}")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<AccountView> update(@PathVariable @Positive long id, @Valid @RequestBody UpdateRequest request,
                                           @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(accountService.update(id, request, admin));
    }

    @PostMapping("/api/studio/accounts/{id}/password-reset")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<Void> resetPassword(@PathVariable @Positive long id, @Valid @RequestBody PasswordResetRequest request,
                                           @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        accountService.resetPassword(id, request.password(), admin);
        return ApiResponse.onSuccess(null);
    }

    @PostMapping("/api/studio/accounts/{id}/otp-reset")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<Void> resetOtp(@PathVariable @Positive long id,
                                      @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        accountService.resetOtp(id, admin);
        return ApiResponse.onSuccess(null);
    }
}
