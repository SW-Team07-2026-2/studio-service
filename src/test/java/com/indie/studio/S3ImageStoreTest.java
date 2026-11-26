package com.indie.studio;

import com.indie.studio.domain.image.ImageErrorCode;
import com.indie.studio.domain.image.ImageKind;
import com.indie.studio.domain.image.ImageStore;
import com.indie.studio.domain.image.S3ImageStore;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.config.StudioProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 진짜 S3 동작 — LocalStack S3 에 업로드 주소로 올리고 · 확인하고 · 허용한 곳의 포스터를 옮깁니다 (Docker 필요) */
class S3ImageStoreTest {

    private static final LocalStackContainer S3 = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.S3)
            .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");                                  // 업로드 주소 서명을 실제로 검사
    private static final String BUCKET = "indig-images";
    private static final String BASE = "https://img.test";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private static S3Client s3;
    private static HttpServer origin;
    private static ImageStore store;

    @BeforeAll
    static void start() throws Exception {
        S3.start();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(S3.getAccessKey(), S3.getSecretKey()));
        URI endpoint = S3.getEndpoint();
        s3 = S3Client.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials).endpointOverride(endpoint).forcePathStyle(true).build();
        s3.createBucket(b -> b.bucket(BUCKET));
        S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials).endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();

        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // KOPIS 자리
        origin.createContext("/poster.png", ex -> { ex.sendResponseHeaders(200, PNG.length); ex.getResponseBody().write(PNG); ex.close(); });
        origin.createContext("/page.html", ex -> {
            byte[] html = "<html>not an image</html>".getBytes();
            ex.getResponseHeaders().add("Content-Type", "image/png");                  // 헤더가 거짓말해도 내용으로 거절
            ex.sendResponseHeaders(200, html.length); ex.getResponseBody().write(html); ex.close();
        });
        origin.createContext("/moved", ex -> { ex.getResponseHeaders().add("Location", "http://169.254.169.254/"); ex.sendResponseHeaders(302, -1); ex.close(); });
        origin.start();

        store = new S3ImageStore(s3, presigner, HTTP,
                new StudioProperties.Image(BUCKET, "ap-northeast-2", BASE, endpoint.toString(), 1024, 300, List.of("127.0.0.1")), Clock.systemUTC());
    }

    @AfterAll
    static void stop() {
        origin.stop(0);
        S3.stop();
    }

    @Test
    void uploadThroughPresignedUrlThenVerify() throws Exception {
        ImageStore.Upload upload = store.presign(ImageKind.ARTIST, "image/png", PNG.length);
        assertThat(upload.imageUrl()).matches(BASE + "/artist/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThatThrownBy(() -> store.verify(upload.imageUrl()))                         // 아직 안 올림
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_NOT_UPLOADED));

        HttpResponse<String> wrongType = HTTP.send(HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                .header("Content-Type", "image/jpeg").PUT(HttpRequest.BodyPublishers.ofByteArray(PNG)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wrongType.statusCode()).isEqualTo(403);                                   // 서명한 종류와 다르면 S3 가 거절

        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(PNG)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
        store.verify(upload.imageUrl());

        assertThatThrownBy(() -> store.verify("https://elsewhere.example/artist/2026/11/a.png")).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> store.verify(BASE + "/../secret.png")).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> store.presign(ImageKind.ARTIST, "image/png", 4096))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_TOO_LARGE));
        assertThatThrownBy(() -> store.presign(ImageKind.ARTIST, "text/html", 10))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_TYPE_NOT_ALLOWED));
    }

    @Test
    void importsOnlyRealImagesFromAllowedHostsWithoutFollowingRedirects() {
        String host = "http://127.0.0.1:" + origin.getAddress().getPort();
        String url = store.importFrom(host + "/poster.png", ImageKind.POSTER);
        assertThat(url).startsWith(BASE + "/poster/").endsWith(".png");
        store.verify(url);
        ResponseBytes<GetObjectResponse> saved = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(url.substring(BASE.length() + 1)));
        assertThat(saved.asByteArray()).isEqualTo(PNG);
        assertThat(saved.response().contentType()).isEqualTo("image/png");

        assertThat(store.importable("http://localhost:" + origin.getAddress().getPort() + "/poster.png")).isFalse();
        assertThatThrownBy(() -> store.importFrom("http://localhost/poster.png", ImageKind.POSTER))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_URL_NOT_ALLOWED));
        assertThatThrownBy(() -> store.importFrom(host + "/page.html", ImageKind.POSTER))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_TYPE_NOT_ALLOWED));
        assertThatThrownBy(() -> store.importFrom(host + "/moved", ImageKind.POSTER))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_IMPORT_FAILED));
    }

    /** 11/26 상세 이미지 — https 이고 공개 주소로만 풀리는 곳만 (안쪽 주소 · 사설 · Tailscale · 사용자 정보 · 다른 포트는 거절) */
    @Test
    void importsDetailImagesOnlyFromPublicHttpsHosts() {
        String local = "http://127.0.0.1:" + origin.getAddress().getPort() + "/poster.png";
        for (String url : List.of(local, "https://127.0.0.1/poster.png", "https://10.0.0.5/a.png", "https://192.168.0.2/a.png",
                "https://100.99.13.54/a.png", "https://169.254.169.254/latest", "https://[::1]/a.png", "https://[fd00::1]/a.png",
                "https://user@example.com/a.png", "https://example.com:8443/a.png", "https://localhost/a.png")) {
            assertThatThrownBy(() -> store.importPublic(url, ImageKind.GOODS)).as(url)
                    .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_URL_NOT_ALLOWED));
        }
        assertThat(S3ImageStore.publicHttps("https://8.8.8.8/a.png")).isTrue();
    }
}
