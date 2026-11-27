package com.indie.studio.domain.image;

import java.util.Locale;

/** 공개 이미지 종류 - S3 키의 첫 경로 (artist/2026/11/{uuid}.jpg) */
public enum ImageKind {
    ARTIST, ALBUM, GOODS, PHYSICAL,
    /** 공연 대표 이미지 */
    POSTER,
    /** 공연 상세 이미지 (imageUrls) */
    CONCERT;

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }
}
