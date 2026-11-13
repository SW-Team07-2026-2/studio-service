package com.indie.studio.domain.stage.service;

import com.fasterxml.jackson.databind.JsonNode;

/** 무대 그리기 검증 서버 (개인 GPU 서버 · WireGuard 피어). 구현은 HttpGpuClient */
public interface GpuClient {

    /** image 가 null 이면 GPU 가 사진을 돌려주지 않은 것 · values 는 Concert 로 보낼 구역 값 */
    record Result(byte[] image, String imageContentType, JsonNode values) {}

    Result verify(long venueId, String venueName, byte[] image, String imageContentType, String drawingJson);
}
