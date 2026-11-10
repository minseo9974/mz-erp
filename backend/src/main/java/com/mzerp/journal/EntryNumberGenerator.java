package com.mzerp.journal;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전표번호 채번: 회사·일자별 연번 (ADR-0011).
 * <p>
 * INSERT … ON CONFLICT DO UPDATE … RETURNING 한 문장으로 "없으면 1, 있으면 +1"을 한다.
 * 같은 회사·같은 날짜의 행은 첫 트랜잭션이 커밋할 때까지 잠기므로, 동시에 100명이 입력해도 번호가 겹치지 않는다.
 * 전표 저장이 실패해 롤백되면 증가분도 같이 롤백되어 번호가 비지 않는다.
 */
@Component
class EntryNumberGenerator {

    private final JdbcClient jdbc;

    EntryNumberGenerator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY) // 반드시 전표 저장과 같은 트랜잭션 안에서
    int next(Long companyId, LocalDate entryDate) {
        return jdbc.sql("""
                        insert into entry_sequence (company_id, entry_date, last_no)
                        values (:companyId, :entryDate, 1)
                        on conflict (company_id, entry_date)
                        do update set last_no = entry_sequence.last_no + 1
                        returning last_no
                        """)
                .param("companyId", companyId)
                .param("entryDate", entryDate)
                .query(Integer.class)
                .single();
    }
}
