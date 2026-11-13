package com.indie.studio.domain.stage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.stage.entity.StageJobEntity;

import java.time.Instant;

/**
 * 무대 그리기 작업 — 목록에서는 drawing · values · sent 가 빠집니다.
 * values = GPU 가 돌려준 값 (관리자가 확인 · 수정할 대상) · sent = Concert 로 실제 보낸 값 · resultImage = GPU 가 돌려준 사진이 있는지.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StageJobView(long id, long venueId, String venueName, StageJobEntity.Status status, String errorMessage,
                           long requestedBy, Long sentBy, Instant createdAt, Instant analyzedAt, Instant sentAt,
                           Boolean resultImage, JsonNode drawing, JsonNode values, JsonNode sent) {}
