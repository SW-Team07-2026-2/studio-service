package com.indie.studio.domain.stage.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Studio -> GPU 서버 도면 검증 포트. 구현은 HttpGpuClient입니다. */
public interface GpuClient {

    /** image 가 null 이면 GPU 가 사진을 돌려주지 않은 것, values 는 Concert 로 보낼 구역 값 */
    record Result(byte[] image, String imageContentType, JsonNode values) {}

    /**
     * GPU 서버에 이미지와 도면을 보내 검증 결과를 받습니다.
     *
     * @param venueId 공연장 ID
     * @param venueName 공연장 이름
     * @param image 도면 이미지
     * @param imageContentType 도면 이미지 MIME 형식
     * @param drawingJson 관리자가 그린 도면 JSON 문자열
     */
    Result verify(long venueId, String venueName, byte[] image, String imageContentType, String drawingJson);
}
