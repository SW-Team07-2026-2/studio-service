package com.indie.studio.global.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/** 에러 코드로 던지는 예외 — GlobalExceptionHandler 가 ApiResponse 실패 응답으로 바꿉니다 */
public class GeneralException extends RuntimeException {

    private final BaseErrorCode errorCode;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public GeneralException(BaseErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public GeneralException(BaseErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 실패 응답의 result 에 값을 싣습니다 */
    public GeneralException with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public Map<String, Object> getData() { return data; }

    public BaseErrorCode getErrorCode() { return errorCode; }
}
