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

/** 관리자 화면 -> Studio 계정 관리 API. 마스터 권한으로 관리자 등록, 수정과 비밀번호 재설정을 수행합니다. */
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 관리자 계정 목록을 조회합니다. */
    @GetMapping("/api/studio/accounts")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<List<AccountView>> list() {
        return ApiResponse.onSuccess(accountService.list());
    }

    /**
     * 관리자 계정 정보를 등록합니다.
     *
     * @param request 생성할 관리자 계정 정보
     */
    @PostMapping("/api/studio/accounts")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<AccountView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.onSuccess(accountService.create(request));
    }

    /**
     * 이름, 역할, 상태(ACTIVE, DISABLED), 인증 코드 받을 메일. 역할, 상태가 바뀌면 그 계정은 바로 로그아웃됩니다
     *
     * @param accountId 관리자 계정 ID
     * @param request 변경할 관리자 계정 정보
     * @param admin 요청한 관리자와 권한 정보
     */
    @PatchMapping("/api/studio/accounts/{accountId}")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<AccountView> update(@PathVariable @Positive long accountId, @Valid @RequestBody UpdateRequest request,
                                           @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(accountService.update(accountId, request, admin));
    }

    /**
     * 지정한 관리자 계정의 비밀번호를 재설정합니다.
     *
     * @param accountId 관리자 계정 ID
     * @param request 재설정할 관리자 비밀번호
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/accounts/{accountId}/password-reset")
    @RequirePermission(Permission.ACCOUNT)
    public ApiResponse<Void> resetPassword(@PathVariable @Positive long accountId, @Valid @RequestBody PasswordResetRequest request,
                                           @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        accountService.resetPassword(accountId, request.password(), admin);
        return ApiResponse.onSuccess(null);
    }
}
