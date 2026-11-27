package com.indie.studio.global.config;

import com.indie.studio.domain.image.DisabledImageStore;
import com.indie.studio.domain.image.ImageStore;
import com.indie.studio.domain.image.S3ImageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** 공개 이미지 저장소 - app.image.bucket 이 있으면 S3(로컬은 MinIO), 없으면 막아 둔 저장소 */
@Configuration
public class ImageConfig {

    private static final Logger log = LoggerFactory.getLogger(ImageConfig.class);

    @Bean
    public ImageStore imageStore(StudioProperties props, Clock clock,
                                 @Value("${image.access-key:}") String accessKey, @Value("${image.secret-key:}") String secretKey) {
        StudioProperties.Image image = props.image();
        if (image == null || image.bucket() == null || image.bucket().isBlank()) {
            log.warn("image_store_disabled — app.image.bucket 이 비어 이미지 업로드 · 저장을 막습니다");
            return new DisabledImageStore();
        }
        AwsCredentialsProvider credentials = accessKey.isBlank() || secretKey.isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.strip(), secretKey.strip()));
        Region region = Region.of(image.region());
        boolean local = image.endpoint() != null && !image.endpoint().isBlank();
        S3ClientBuilder s3 = S3Client.builder().region(region).credentialsProvider(credentials);
        S3Presigner.Builder presigner = S3Presigner.builder().region(region).credentialsProvider(credentials);
        if (local) {
            s3.endpointOverride(URI.create(image.endpoint())).forcePathStyle(true);
            presigner.endpointOverride(URI.create(image.endpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        log.info("image_store bucket={} publicBase={} local={}", image.bucket(), image.publicBaseUrl(), local);
        return new S3ImageStore(s3.build(), presigner.build(), http, image, clock);
    }
}
