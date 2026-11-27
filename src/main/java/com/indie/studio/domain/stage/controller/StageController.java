package com.indie.studio.domain.stage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.stage.dto.StageJobView;
import com.indie.studio.domain.stage.entity.StageJobImageEntity;
import com.indie.studio.domain.stage.service.StageJobService;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** 무대 관리자 화면 -> Studio -> GPU 검증 후 관리자 확인 -> Concert 공연장 배치 저장. */
@RestController
public class StageController {

    private final ServiceClient serviceClient;
    private final StageJobService stageJobService;

    public StageController(ServiceClient serviceClient, StageJobService stageJobService) {
        this.serviceClient = serviceClient;
        this.stageJobService = stageJobService;
    }

    /**
     * Concert에서 공연장 목록을 조회합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/venues")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> venues(@RequestParam MultiValueMap<String, String> query,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/venues", query, admin));
    }

    /**
     * Concert에서 공연장 상세를 조회합니다.
     *
     * @param venueId 공연장 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/venues/{venueId}")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> venue(@PathVariable @Positive long venueId,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/venues/" + venueId, null, admin));
    }

    /**
     * Concert에 공연장 등록을 요청합니다.
     *
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/venues")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> createVenue(@RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/venues", body, admin));
    }

    /**
     * 지금 저장된 배치 (층 -> 구역 -> 좌석) - 이미 그린 구역을 화면에 깔아 줍니다
     *
     * @param venueId 공연장 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/venues/{venueId}/layout")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> layout(@PathVariable @Positive long venueId,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/venues/" + venueId + "/layout", null, admin));
    }

    /**
     * 구역 값을 GPU 검증 없이 바로 저장합니다 ( - GPU 검증 서버를 만들지 않을 수 있어 둔 길).
     * 본문은 confirm 과 같은 모양 - 구역 하나, 구역 배열, { sections: [...] }. 구역마다 Concert 에 한 번씩 보냅니다.
     * 무대 작업(stage_job)은 남기지 않고 작업 기록(audit_log)만 남습니다.
     *
     * @param venueId 공연장 ID
     * @param values Concert에 저장할 구역 배치 값
     * @param admin 요청한 관리자와 권한 정보
     */
    @PutMapping("/api/studio/venues/{venueId}/sections")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<JsonNode> saveSections(@PathVariable @Positive long venueId,
                                              @RequestBody JsonNode values,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(stageJobService.saveSections(venueId, values, admin));
    }

    /**
     * 도면 검증 작업을 만들고 백그라운드 검증을 시작합니다.
     *
     * @param venueId 공연장 ID
     * @param image 도면 이미지
     * @param drawing 관리자가 그린 도면 JSON
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping(value = "/api/studio/venues/{venueId}/stage-jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<StageJobView> createJob(@PathVariable @Positive long venueId,
                                               @RequestPart("image") MultipartFile image,
                                               @RequestParam("drawing") String drawing,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) throws IOException {
        return ApiResponse.onSuccess(stageJobService.create(venueId, image.getBytes(), drawing, admin));
    }

    /**
     * 공연장의 도면 검증 작업을 조회합니다.
     *
     * @param venueId 공연장 ID
     */
    @GetMapping("/api/studio/venues/{venueId}/stage-jobs")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<List<StageJobView>> jobs(@PathVariable @Positive long venueId) {
        return ApiResponse.onSuccess(stageJobService.list(venueId));
    }

    /**
     * 도면 검증 작업 상세를 조회합니다.
     *
     * @param jobId 도면 검증 작업 ID
     */
    @GetMapping("/api/studio/stage-jobs/{jobId}")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<StageJobView> job(@PathVariable @Positive long jobId) {
        return ApiResponse.onSuccess(stageJobService.get(jobId));
    }

    /**
     * ?kind=RESULT(GPU 가 돌려준 사진, 기본) | ORIGINAL(올린 사진)
     *
     * @param jobId 도면 검증 작업 ID
     * @param kind 조회할 작업 이미지 종류
     */
    @GetMapping("/api/studio/stage-jobs/{jobId}/image")
    @RequirePermission(Permission.STAGE_DRAW)
    public ResponseEntity<byte[]> image(@PathVariable @Positive long jobId,
                                        @RequestParam(defaultValue = "RESULT") StageJobImageEntity.Kind kind) {
        StageJobImageEntity image = stageJobService.image(jobId, kind);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.getData());
    }

    /**
     * 관리자가 확인한 값을 Concert 로 보냅니다 - 본문이 있으면 고친 값, 없으면 GPU 값 그대로
     *
     * @param jobId 도면 검증 작업 ID
     * @param edited 관리자가 검수하거나 수정한 값
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/stage-jobs/{jobId}/confirm")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<JsonNode> confirm(@PathVariable @Positive long jobId,
                                         @RequestBody(required = false) JsonNode edited,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(stageJobService.confirm(jobId, edited, admin));
    }
}
