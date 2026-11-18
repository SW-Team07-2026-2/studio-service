package com.indie.studio.domain.image;

import com.indie.studio.global.common.exception.GeneralException;

/** app.image.bucket 이 비었을 때 — 업로드 · 이미지 주소 저장을 막습니다 (이미지 칸을 비운 승인 · 등록은 그대로 됨) */
public class DisabledImageStore implements ImageStore {

    @Override
    public Upload presign(ImageKind kind, String contentType, long size) {
        throw new GeneralException(ImageErrorCode.IMAGE_STORE_DISABLED);
    }

    @Override
    public void verify(String url) {
        throw new GeneralException(ImageErrorCode.IMAGE_STORE_DISABLED);
    }

    @Override
    public boolean importable(String url) {
        return false;
    }

    @Override
    public String importFrom(String url, ImageKind kind) {
        throw new GeneralException(ImageErrorCode.IMAGE_STORE_DISABLED);
    }
}
