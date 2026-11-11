package com.mzerp.ledger;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 계정·거래처별 차변·대변 합계. 장부의 가장 작은 조각이다 (3단계의 원장·시산표가 여기서 넓어진다).
 * <p>
 * 불변식 5: 장부는 전표 라인에서만 파생된다 → 전기된 전표의 라인만 더한다. 기초잔액도 OPENING 전표라서 따로 더할 것이 없다.
 * 집계 조회는 MyBatis SQL로 쓴다 (ADR-0005).
 */
@Mapper
public interface BalanceMapper {

    /** @param partnerCode 거래처가 없는 라인의 합계는 null */
    record Row(String accountCode, String partnerCode, long debit, long credit) {
    }

    @Select("""
            select a.code                           as account_code,
                   p.code                           as partner_code,
                   coalesce(sum(l.debit_amount), 0)  as debit,
                   coalesce(sum(l.credit_amount), 0) as credit
              from journal_line l
              join journal_entry e on e.id = l.entry_id
              join account a on a.id = l.account_id
              left join partner p on p.id = l.partner_id
             where e.company_id = #{companyId}
               and e.status = 'POSTED'
             group by a.code, p.code
             order by a.code, p.code
            """)
    List<Row> postedBalances(@Param("companyId") Long companyId);
}
