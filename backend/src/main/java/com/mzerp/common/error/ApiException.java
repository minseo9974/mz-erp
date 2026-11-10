package com.mzerp.common.error;

/** 업무 규칙 위반. {@link GlobalExceptionHandler}가 Problem Details로 바꿔 응답한다. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + "을(를) 찾을 수 없습니다");
    }
}
