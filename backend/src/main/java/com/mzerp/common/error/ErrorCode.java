package com.mzerp.common.error;

import org.springframework.http.HttpStatus;

/** 업무 오류 코드. API 응답의 {@code code} 필드로 내려간다 (docs/design/api.md). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다"),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "이 작업을 할 권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    DUPLICATE_CODE(HttpStatus.CONFLICT, "이미 사용 중인 코드입니다"),
    FISCAL_YEAR_OVERLAP(HttpStatus.CONFLICT, "다른 회계연도와 기간이 겹칩니다"),
    DATA_CONFLICT(HttpStatus.CONFLICT, "데이터 제약 조건에 맞지 않습니다"),
    ACCOUNT_SYSTEM_LOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "시스템 계정은 수정할 수 없습니다"),
    ACCOUNT_CONTRA_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "차감 대상 계정이 올바르지 않습니다");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
