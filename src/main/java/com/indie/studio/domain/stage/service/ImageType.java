package com.indie.studio.domain.stage.service;

/** 파일 앞부분(매직 넘버)으로 사진 형식을 확인합니다 — 요청의 Content-Type 은 믿지 않습니다 */
public final class ImageType {

    private ImageType() {}

    /** image/png · image/jpeg · image/webp 중 하나, 아니면 null */
    public static String detect(byte[] data) {
        if (data == null || data.length < 12) return null;
        if ((data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "image/png";
        if ((data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) return "image/jpeg";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return "image/webp";
        return null;
    }
}
