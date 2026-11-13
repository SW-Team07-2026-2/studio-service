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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 무대 관리자 — 공연장 추가와 무대 그리기. 공연장 · 배치의 주인은 AWS 의 Concert 이고, Studio 는 내부 API 로만 읽고 씁니다.
 * 조회 파라미터와 등록 본문은 Concert 의 것 그대로입니다 (검증도 Concert 가 합니다).
 */
@RestController
public class StageController {

    private final ServiceClient serviceClient;
    private final StageJobService stageJobService;

    public StageController(ServiceClient serviceClient, StageJobService stageJobService) {
        this.serviceClient = serviceClient;
        this.stageJobService = stageJobService;
    }

    /** ?region=&q=&hasUpcoming=&page=&size= */
    @GetMapping("/api/studio/venues")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> venues(@RequestParam MultiValueMap<String, String> query,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/venues", query, admin));
    }

    @GetMapping("/api/studio/venues/{venueId}")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> venue(@PathVariable @Positive long venueId,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/api/concerts/venues/" + venueId, null, admin));
    }

    /** 공연장 추가 — { name, region, address, capacity } */
    @PostMapping("/api/studio/venues")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> createVenue(@RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CONCERT, HttpMethod.POST, "/internal/admin/concerts/venues", body, admin));
    }

    /** 지금 저장된 배치 (층 → 구역 → 좌석) — 이미 그린 구역을 화면에 깔아 줍니다 */
    @GetMapping("/api/studio/venues/{venueId}/layout")
    @RequirePermission(Permission.VENUE)
    public ApiResponse<JsonNode> layout(@PathVariable @Positive long venueId,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CONCERT, "/internal/admin/concerts/venues/" + venueId + "/layout", null, admin));
    }

    /** 무대 그리기 검증 요청 — multipart: image(도면 사진) + drawing(그린 값 JSON). 바로 응답하고 검증은 뒤에서 돕니다 */
    @PostMapping(value = "/api/studio/venues/{venueId}/stage-jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<StageJobView> createJob(@PathVariable @Positive long venueId,
                                               @RequestPart("image") MultipartFile image,
                                               @RequestParam("drawing") String drawing,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) throws IOException {
        return ApiResponse.onSuccess(stageJobService.create(venueId, image.getBytes(), drawing, admin));
    }

    @GetMapping("/api/studio/venues/{venueId}/stage-jobs")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<List<StageJobView>> jobs(@PathVariable @Positive long venueId) {
        return ApiResponse.onSuccess(stageJobService.list(venueId));
    }

    /** 화면이 status 가 REVIEW · FAILED 가 될 때까지 다시 부릅니다 (2~3초 간격) */
    @GetMapping("/api/studio/stage-jobs/{jobId}")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<StageJobView> job(@PathVariable @Positive long jobId) {
        return ApiResponse.onSuccess(stageJobService.get(jobId));
    }

    /** ?kind=RESULT(GPU 가 돌려준 사진 · 기본) | ORIGINAL(올린 사진) */
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

    /** 관리자가 확인한 값을 Concert 로 보냅니다 — 본문이 있으면 고친 값, 없으면 GPU 값 그대로 */
    @PostMapping("/api/studio/stage-jobs/{jobId}/confirm")
    @RequirePermission(Permission.STAGE_DRAW)
    public ApiResponse<JsonNode> confirm(@PathVariable @Positive long jobId,
                                         @RequestBody(required = false) JsonNode edited,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(stageJobService.confirm(jobId, edited, admin));
    }
}
