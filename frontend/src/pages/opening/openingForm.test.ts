import { describe, expect, it } from 'vitest'

import type { Account, Partner } from '../../api/types'
import type { EntryLine } from '../journal/types'
import { emptyRow, fromEntryLines, toApiLines, totals, validate } from './openingForm'
import type { OpeningRow } from './openingForm'

const row = (patch: Partial<OpeningRow>): OpeningRow => ({ ...emptyRow(), ...patch })

const account = (code: string, name: string, patch: Partial<Account> = {}): Account => ({
  code,
  name,
  category: 'ASSET',
  subgroup: '당좌자산',
  normalBalance: 'DEBIT',
  contraOfCode: null,
  requiresPartner: false,
  statement: 'BS',
  system: false,
  active: true,
  ...patch,
})

const accounts = new Map(
  [
    account('101', '현금'),
    account('108', '외상매출금', { requiresPartner: true }),
    account('109', '대손충당금', { normalBalance: 'CREDIT', contraOfCode: '108' }),
    account('331', '자본금', { category: 'EQUITY', subgroup: '자본금', normalBalance: 'CREDIT' }),
    account('404', '제품매출', { category: 'REVENUE', subgroup: '매출', normalBalance: 'CREDIT', statement: 'IS' }),
  ].map((a) => [a.code, a]),
)
const partners = new Map<string, Partner>([
  ['00101', { code: '00101', name: '(주)한빛전자', bizRegNo: null, partnerType: 'CUSTOMER', active: true }],
])

describe('차변·대변은 계정의 정상잔액이 정한다 (회계 노트 03)', () => {
  it('자산은 차변, 차감 계정(대손충당금)과 자본은 대변', () => {
    const api = toApiLines(
      [
        row({ accountCode: '108', partnerCode: '00101', amount: '12,000,000' }),
        row({ accountCode: '109', amount: '120,000' }),
        row({ accountCode: '331', amount: '11,880,000' }),
        row({}), // 빈 줄은 보내지 않는다
      ],
      accounts,
    )
    expect(api).toEqual([
      { side: 'DEBIT', accountCode: '108', partnerCode: '00101', amount: 12_000_000, memo: null },
      { side: 'CREDIT', accountCode: '109', partnerCode: null, amount: 120_000, memo: null },
      { side: 'CREDIT', accountCode: '331', partnerCode: null, amount: 11_880_000, memo: null },
    ])
  })

  it('정상잔액과 반대인 잔액은 음수로 적는다 → 반대쪽 라인이 되고, 다시 불러오면 괄호로 보인다', () => {
    const api = toApiLines([row({ accountCode: '101', amount: '-30,000' })], accounts)
    expect(api[0]).toMatchObject({ side: 'CREDIT', amount: 30_000 })

    const saved: EntryLine[] = [
      { lineNo: 1, side: 'CREDIT', accountCode: '101', accountName: '현금', partnerCode: null, partnerName: null, amount: 30_000, memo: null },
      { lineNo: 2, side: 'CREDIT', accountCode: '331', accountName: '자본금', partnerCode: null, partnerName: null, amount: 50_000, memo: null },
    ]
    expect(fromEntryLines(saved, accounts).map((r) => r.amount)).toEqual(['(30,000)', '50,000'])
  })

  it('합계: 자산 17,000,000 = 대손충당금 120,000 + 자본금 16,880,000', () => {
    const t = totals(
      [
        row({ accountCode: '101', amount: '5,000,000' }),
        row({ accountCode: '108', partnerCode: '00101', amount: '12,000,000' }),
        row({ accountCode: '109', amount: '120,000' }),
        row({ accountCode: '331', amount: '16,880,000' }),
      ],
      accounts,
    )
    expect(t).toEqual({ debit: 17_000_000, credit: 17_000_000, difference: 0 })
  })
})

describe('화면 검증', () => {
  it('수익·비용 계정, 거래처 없는 외상매출금, 금액 없는 줄을 그 줄에 표시한다', () => {
    const revenue = row({ accountCode: '404', amount: '1,000' })
    const receivable = row({ accountCode: '108', amount: '1,000' })
    const empty = row({ accountCode: '101' })
    const { byRow } = validate([revenue, receivable, empty], accounts, partners)
    expect(byRow.get(revenue.key)?.[0]).toContain('수익·비용 계정')
    expect(byRow.get(receivable.key)).toEqual(['외상매출금은(는) 거래처별로 나눠 입력합니다'])
    expect(byRow.get(empty.key)).toEqual(['금액을 입력하세요'])
  })

  it('자산과 부채·자본이 다르면 차액을 알려 준다', () => {
    const { entry } = validate(
      [row({ accountCode: '101', amount: '5,000,000' }), row({ accountCode: '331', amount: '4,000,000' })],
      accounts,
      partners,
    )
    expect(entry).toEqual(['차변(자산)과 대변(부채·자본)이 1,000,000원 다릅니다'])
  })

  it('맞는 기초잔액은 오류가 없다', () => {
    const { byRow, entry } = validate(
      [row({ accountCode: '101', amount: '5,000,000' }), row({ accountCode: '331', amount: '5,000,000' }), row({})],
      accounts,
      partners,
    )
    expect(byRow.size).toBe(0)
    expect(entry).toEqual([])
  })
})
