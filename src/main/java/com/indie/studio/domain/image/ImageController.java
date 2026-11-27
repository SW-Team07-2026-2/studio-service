package com.indie.studio.domain.image;

import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.common.exception.CommonErrorCode;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 이미지 올리기 - 업로드 주소를 받아 화면이 파일을 S3 로 바로 PUT 하고, 받은 imageUrl 을 저장할 칸에 넣습니다.
 * 저장(승인, 등록, 수정)할 때 그 주소에 파일이 실제로 있는지 다시 확인합니다 (ImageGuard).
 * 상품 관리자: 아티스트, 앨범, 굿즈, 피지컬 앨범 이미지 / 공연 관리자: 공연 포스터, 상세 이미지.
 */
@RestController
public class ImageController {

    private static final Set<ImageKind> PRODUCT_KINDS = Set.of(ImageKind.ARTIST, ImageKind.ALBUM, ImageKind.GOODS, ImageKind.PHYSICAL);
    private static final Set<ImageKind> CONCERT_KINDS = Set.of(ImageKind.POSTER, ImageKind.CONCERT);

    private final ImageStore store;

    public ImageController(ImageStore store) {
        this.store = store;
    }

    public record UploadRequest(@NotNull ImageKind kind, @NotBlank String contentType, @NotNull @Positive Long size) {}

    @PostMapping("/api/studio/products/images")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<ImageStore.Upload> productImage(@Valid @RequestBody UploadRequest request) {
        return ApiResponse.onSuccess(presign(request, PRODUCT_KINDS));
    }

    @PostMapping("/api/studio/concerts/images")
    @RequirePermission(Permission.CONCERT)
    public ApiResponse<ImageStore.Upload> concertImage(@Valid @RequestBody UploadRequest request) {
        return ApiResponse.onSuccess(presign(request, CONCERT_KINDS));
    }

    private ImageStore.Upload presign(UploadRequest r, Set<ImageKind> allowed) {
        if (!allowed.contains(r.kind())) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "kind 는 " + allowed + " 중 하나입니다");
        }
        return store.presign(r.kind(), r.contentType(), r.size());
    }
}
