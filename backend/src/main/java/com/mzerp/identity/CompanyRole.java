package com.mzerp.identity;

/**
 * 회사별 역할. 위로 갈수록 권한이 크고, 상위 역할은 하위 역할의 권한을 모두 가진다 (ADR-0008).
 * <ul>
 *   <li>WRITER — 기준정보 조회, 거래처 등록, 전표 작성</li>
 *   <li>APPROVER — 전표 전기·역분개</li>
 *   <li>ADMIN — 계정과목 관리, 마감 취소, 사용자 관리</li>
 * </ul>
 */
public enum CompanyRole {
    WRITER, APPROVER, ADMIN;

    public boolean includes(CompanyRole required) {
        return this.ordinal() >= required.ordinal();
    }
}
