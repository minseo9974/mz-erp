package com.mzerp.journal;

/** DRAFT 임시(수정·삭제 가능, 장부 미반영) → POSTED 전기(불변, 장부 반영). ADR-0003 */
public enum EntryStatus {
    DRAFT, POSTED
}
