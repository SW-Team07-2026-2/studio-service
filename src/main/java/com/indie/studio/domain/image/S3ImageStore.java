package com.indie.studio.domain.image;

import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * S3 (운영), MinIO (로컬) 이미지 저장소. 공개 주소 = app.image.public-base-url + "/" + 키 (CloudFront img.indig.co.kr -> 버킷).
 * 키: {종류}/{yyyy}/{MM}/{uuid}.{jpg|png|webp|gif} - Studio 가 정하고 화면은 고를 수 없습니다.
 * 업로드 주소는 Content-Type, 크기까지 서명해서, 받은 값과 다른 파일은 S3 가 거절합니다.
 */
public class S3ImageStore implements ImageStore {

    private static final Logger log = LoggerFactory.getLogger(S3ImageStore.class);
    /** 종류 -> 확장자. 저장할 때 다시 확인합니다 */
    private static final Map<String, String> TYPES = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");
    private static final Pattern KEY = Pattern.compile(
            "^(artist|album|goods|physical|poster|concert)/\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|gif)$");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final HttpClient http;
    private final String bucket;
    private final String publicBase;
    private final long maxBytes;
    private final Duration uploadTtl;
    private final List<String> importHosts;
    private final Clock clock;

    public S3ImageStore(S3Client s3, S3Presigner presigner, HttpClient http, StudioProperties.Image props, Clock clock) {
        this.s3 = s3;
        this.presigner = presigner;
        this.http = http;
        this.bucket = props.bucket();
        this.publicBase = props.publicBaseUrl().replaceAll("/+$", "") + "/";
        this.maxBytes = props.maxBytes();
        this.uploadTtl = Duration.ofSeconds(props.uploadTtlSeconds());
        this.importHosts = props.importHosts() == null ? List.of()
                : props.importHosts().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
        this.clock = clock;
    }

    @Override
    public Upload presign(ImageKind kind, String contentType, long size) {
        String ext = extension(contentType);
        if (size <= 0 || size > maxBytes) throw tooLarge();
        String key = key(kind, ext);
        PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(uploadTtl)
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(size).build())
                .build());
        return new Upload(signed.url().toString(), "PUT", Map.of("Content-Type", contentType), publicBase + key, signed.expiration());
    }

    @Override
    public void verify(String url) {
        String key = keyOf(url);
        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw new GeneralException(ImageErrorCode.IMAGE_NOT_UPLOADED);
            throw e;
        }
        if (head.contentLength() == null || head.contentLength() > maxBytes) throw tooLarge();
        if (!TYPES.containsKey(String.valueOf(head.contentType()).toLowerCase(Locale.ROOT))) {
            throw new GeneralException(ImageErrorCode.IMAGE_TYPE_NOT_ALLOWED);
        }
    }

    @Override
    public boolean importable(String url) {
        try {
            URI uri = URI.create(url.strip());
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && importHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)) && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 리다이렉트는 따라가지 않습니다 (허용한 곳 밖으로 나가지 않게), 크기 제한, 내용(앞 바이트)으로 종류 확인 */
    @Override
    public String importFrom(String url, ImageKind kind) {
        if (!importable(url)) throw new GeneralException(ImageErrorCode.IMAGE_URL_NOT_ALLOWED);
        byte[] bytes;
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url.strip())).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "indig-studio/1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                if (res.statusCode() != 200) {
                    log.warn("image_import_failed url={} status={}", url, res.statusCode());
                    throw new GeneralException(ImageErrorCode.IMAGE_IMPORT_FAILED);
                }
                bytes = body.readNBytes((int) maxBytes + 1);
            }
        } catch (IOException e) {
            log.warn("image_import_failed url={} error={}", url, e.getClass().getSimpleName());
            throw new GeneralException(ImageErrorCode.IMAGE_IMPORT_FAILED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeneralException(ImageErrorCode.IMAGE_IMPORT_FAILED);
        }
        if (bytes.length > maxBytes) throw tooLarge();
        String contentType = sniff(bytes);
        String key = key(kind, TYPES.get(contentType));
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength((long) bytes.length).build(),
                RequestBody.fromBytes(bytes));
        log.info("image_imported kind={} key={} bytes={}", kind, key, bytes.length);
        return publicBase + key;
    }

    private String key(ImageKind kind, String ext) {
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        return String.format("%s/%04d/%02d/%s.%s", kind.path(), now.getYear(), now.getMonthValue(), UUID.randomUUID(), ext);
    }

    private String keyOf(String url) {
        if (url == null || !url.startsWith(publicBase)) throw new GeneralException(ImageErrorCode.IMAGE_URL_NOT_ALLOWED);
        String key = url.substring(publicBase.length());
        if (!KEY.matcher(key).matches()) throw new GeneralException(ImageErrorCode.IMAGE_URL_NOT_ALLOWED);
        return key;
    }

    private static String extension(String contentType) {
        String ext = contentType == null ? null : TYPES.get(contentType.toLowerCase(Locale.ROOT));
        if (ext == null) throw new GeneralException(ImageErrorCode.IMAGE_TYPE_NOT_ALLOWED);
        return ext;
    }

    /** 앞 바이트로 이미지 종류 - 응답 헤더의 Content-Type 은 믿지 않습니다 */
    static String sniff(byte[] b) {
        if (starts(b, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (starts(b, 'G', 'I', 'F', '8')) return "image/gif";
        if (b.length >= 12 && starts(b, 'R', 'I', 'F', 'F') && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        throw new GeneralException(ImageErrorCode.IMAGE_TYPE_NOT_ALLOWED);
    }

    private static boolean starts(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private GeneralException tooLarge() {
        return new GeneralException(ImageErrorCode.IMAGE_TOO_LARGE, "이미지는 " + (maxBytes / 1024 / 1024) + "MB 까지 올릴 수 있습니다");
    }
}
