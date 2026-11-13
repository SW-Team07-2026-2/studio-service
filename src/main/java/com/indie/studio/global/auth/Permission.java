package com.indie.studio.global.auth;

/** 권한 — 엔드포인트는 권한 하나를 요구하고(@RequirePermission), 역할이 권한 묶음을 가집니다 (Role) */
public enum Permission {
    /** 내 정보 · 로그아웃 · 비밀번호 변경 — 모든 역할 */
    SELF,
    /** 관리자 계정 관리 */
    ACCOUNT,
    /** 작업 기록 조회 */
    AUDIT,
    /** 공연장 조회 · 등록 */
    VENUE,
    /** 무대 그리기 — GPU 검증 요청 · 결과 확인 · Concert 로 보내기 */
    STAGE_DRAW,
    /** 공연 조회 · 등록 · 수정 (상세 · 약관 · 이미지 · 요구사항 · 라인업) */
    CONCERT,
    /** 공연 좌석별 가격 */
    SEAT_PRICE,
    /** 굿즈 · 피지컬 앨범 상품 조회 · 등록 · 수정 */
    PRODUCT,
    /** 재고 조정 */
    STOCK,
    /** 회원과 그 회원의 예매 · 주문 · 결제 조회 */
    CS_READ,
    /** 예매 · 주문 취소 대행 */
    CS_CANCEL,
    /** 검표 — 검표할 공연 목록 · 입장 QR 확인 */
    CHECK_IN
}
