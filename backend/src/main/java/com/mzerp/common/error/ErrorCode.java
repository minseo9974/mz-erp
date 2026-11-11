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
    ACCOUNT_CONTRA_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "차감 대상 계정이 올바르지 않습니다"),

    // 전표 (회계 노트 02)
    JOURNAL_UNBALANCED(HttpStatus.UNPROCESSABLE_CONTENT, "차변과 대변 합계가 다릅니다"),
    JOURNAL_TOO_FEW_LINES(HttpStatus.UNPROCESSABLE_CONTENT, "전표 라인은 2줄 이상이어야 합니다"),
    LINE_AMOUNT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "금액이 올바르지 않습니다"),
    PARTNER_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "거래처가 필요한 계정입니다"),
    ACCOUNT_NOT_POSTABLE(HttpStatus.UNPROCESSABLE_CONTENT, "전표에 쓸 수 없는 계정입니다"),
    FISCAL_PERIOD_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "해당 날짜의 회계기간이 없습니다"),
    PERIOD_CLOSED(HttpStatus.CONFLICT, "마감된 기간입니다"),
    ENTRY_IMMUTABLE(HttpStatus.CONFLICT, "전기된 전표는 수정·삭제할 수 없습니다"),
    ENTRY_NOT_POSTED(HttpStatus.CONFLICT, "전기되지 않은 전표입니다"),
    ENTRY_ALREADY_REVERSED(HttpStatus.CONFLICT, "이미 역분개된 전표입니다"),
    REVERSAL_DATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "역분개일은 원전표일보다 빠를 수 없습니다"),
    OPENING_NOT_FIRST_YEAR(HttpStatus.UNPROCESSABLE_CONTENT, "기초잔액은 첫 회계연도에만 직접 입력합니다"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요");

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
