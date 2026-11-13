package com.indie.studio.domain.stage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.global.config.StudioProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;

/**
 * GPU 서버 호출 — KT 의 Studio 에서 WireGuard 로 직접 보냅니다 (S3 를 거치지 않음 · 11/13 결정).
 * <pre>
 * POST {app.gpu.base-url}/v1/stage/verify   multipart/form-data   헤더 X-Gpu-Token (시크릿 gpu.token)
 *   image      도면 사진 (PNG · JPEG · WEBP)
 *   drawing    관리자가 그린 값 (JSON 문자열)
 *   venueId · venueName
 * 200 { "image": "<base64>", "imageContentType": "image/png", "values": { … } }
 *   image 는 없어도 됩니다. values 는 Concert 의 구역 저장 본문(SectionLayoutRequest) 하나 또는 그 배열입니다.
 * </pre>
 * 분석이 오래 걸려도 됩니다 — 백그라운드 스레드에서 부르고 제한 시간은 app.gpu.read-timeout-ms (3분).
 */
@Component
public class HttpGpuClient implements GpuClient {

    private final RestClient client;
    private final String token;

    public HttpGpuClient(StudioProperties props, @Value("${gpu.token:}") String token) {
        StudioProperties.Gpu gpu = props.gpu();
        this.token = token.trim();
        if (gpu.baseUrl() == null || gpu.baseUrl().isBlank()) {
            this.client = null;
            return;
        }
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(gpu.connectTimeoutMs())).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(gpu.readTimeoutMs()));
        this.client = RestClient.builder().baseUrl(gpu.baseUrl().trim()).requestFactory(factory).build();
    }

    @Override
    public Result verify(long venueId, String venueName, byte[] image, String imageContentType, String drawingJson) {
        if (client == null) throw new IllegalStateException("GPU 서버 주소(GPU_BASE_URL)가 설정되지 않았습니다");

        HttpHeaders imageHeaders = new HttpHeaders();
        imageHeaders.setContentType(MediaType.parseMediaType(imageContentType));
        String filename = "stage." + imageContentType.substring(imageContentType.indexOf('/') + 1);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", new HttpEntity<>(new ByteArrayResource(image) {
            @Override
            public String getFilename() { return filename; }
        }, imageHeaders));
        parts.add("drawing", drawingJson);
        parts.add("venueId", String.valueOf(venueId));
        parts.add("venueName", venueName);

        JsonNode body = client.post().uri("/v1/stage/verify")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-Gpu-Token", token)
                .body(parts)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.path("values").isContainerNode()) {
            throw new IllegalStateException("GPU 서버 응답에 values 가 없습니다");
        }
        String encoded = body.path("image").asText("");
        byte[] resultImage = encoded.isEmpty() ? null : Base64.getDecoder().decode(encoded);
        return new Result(resultImage, body.path("imageContentType").asText(null), body.get("values"));
    }
}
