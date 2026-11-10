import { describe, expect, it } from 'vitest'

import type { Account, Partner } from '../../api/types'
import { emptyLine, fromEntryLines, toApiLines, totals, validate } from './entryForm'
import type { FormLine } from './entryForm'
import type { EntryLine } from './types'

const line = (patch: Partial<FormLine>): FormLine => ({ ...emptyLine(), ...patch })

const account = (code: string, name: string, patch: Partial<Account> = {}): Account => ({
  code,
  name,
  category: 'ASSET',
  subgroup: '',
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
    account('103', '보통예금'),
    account('110', '받을어음', { requiresPartner: true }),
    account('812', '여비교통비', { category: 'EXPENSE' }),
    account('400', '손익', { system: true }),
  ].map((a) => [a.code, a]),
)
const partners = new Map<string, Partner>([
  ['00101', { code: '00101', name: '(주)한빛전자', bizRegNo: null, partnerType: 'CUSTOMER', active: true }],
])

describe('출금·입금 → 차변·대변 라인 (전산회계 입력 관례)', () => {
  it('출금 한 줄은 (차) 상대 계정 + (대) 현금 두 줄이 된다 — 시나리오 #35 출장비', () => {
    const api = toApiLines([line({ kind: 'WITHDRAW', accountCode: '812', amount: '450,000', memo: '출장비' })])
    expect(api).toEqual([
      { side: 'DEBIT', accountCode: '812', partnerCode: null, amount: 450_000, memo: '출장비' },
      { side: 'CREDIT', accountCode: '101', partnerCode: null, amount: 450_000, memo: '출장비' },
    ])
  })

  it('입금 한 줄은 (대) 상대 계정 + (차) 현금, 거래처는 상대 계정 쪽에만 붙는다', () => {
    const api = toApiLines([line({ kind: 'DEPOSIT', accountCode: '110', partnerCode: '00101', amount: '300000' })])
    expect(api.map((l) => [l.side, l.accountCode, l.partnerCode])).toEqual([
      ['CREDIT', '110', '00101'],
      ['DEBIT', '101', null],
    ])
  })

  it('저장된 2줄 현금 전표는 다시 출금 한 줄로 접어서 보여 준다', () => {
    const saved: EntryLine[] = [
      { lineNo: 1, side: 'DEBIT', accountCode: '812', accountName: '여비교통비', partnerCode: null, partnerName: null, amount: 450_000, memo: '출장비' },
      { lineNo: 2, side: 'CREDIT', accountCode: '101', accountName: '현금', partnerCode: null, partnerName: null, amount: 450_000, memo: '출장비' },
    ]
    const form = fromEntryLines(saved)
    expect(form).toHaveLength(1)
    expect(form[0]).toMatchObject({ kind: 'WITHDRAW', accountCode: '812', amount: '450,000' })
  })

  it('현금이 아닌 전표는 차변·대변 그대로', () => {
    const saved: EntryLine[] = [
      { lineNo: 1, side: 'DEBIT', accountCode: '103', accountName: '보통예금', partnerCode: null, partnerName: null, amount: 5_000_000, memo: null },
      { lineNo: 2, side: 'CREDIT', accountCode: '110', accountName: '받을어음', partnerCode: '00101', partnerName: '(주)한빛전자', amount: 5_000_000, memo: null },
    ]
    expect(fromEntryLines(saved).map((l) => l.kind)).toEqual(['DEBIT', 'CREDIT'])
  })
})

describe('합계·차액', () => {
  it('출금은 차변·대변에 같은 금액이 들어가 차액에 영향이 없다', () => {
    expect(totals([line({ kind: 'WITHDRAW', accountCode: '812', amount: '450,000' })])).toEqual({
      debit: 450_000,
      credit: 450_000,
      difference: 0,
    })
  })

  it('차변 5,000,000 / 대변 4,000,000 → 차액 1,000,000', () => {
    const t = totals([
      line({ kind: 'DEBIT', accountCode: '103', amount: '5,000,000' }),
      line({ kind: 'CREDIT', accountCode: '110', amount: '4,000,000' }),
    ])
    expect(t.difference).toBe(1_000_000)
  })
})

describe('화면 검증 (서버도 같은 규칙을 다시 검사한다)', () => {
  it('받을어음은 거래처가 없으면 그 줄에 오류', () => {
    const credit = line({ kind: 'CREDIT', accountCode: '110', amount: '5,000,000' })
    const { byLine } = validate([line({ kind: 'DEBIT', accountCode: '103', amount: '5,000,000' }), credit], accounts, partners)
    expect(byLine.get(credit.key)).toEqual(['받을어음은(는) 거래처가 필요합니다'])
  })

  it('결산 전용 시스템 계정·없는 계정·0원은 막는다', () => {
    const sys = line({ accountCode: '400', amount: '1' })
    const unknown = line({ accountCode: '999', amount: '1' })
    const zero = line({ accountCode: '103', amount: '0' })
    const { byLine } = validate([sys, unknown, zero], accounts, partners)
    expect(byLine.get(sys.key)?.[0]).toContain('결산 전용')
    expect(byLine.get(unknown.key)?.[0]).toContain('없습니다')
    expect(byLine.get(zero.key)?.[0]).toContain('0보다 큰')
  })

  it('차액이 있으면 전표 오류, 한 줄짜리 차변 전표도 오류', () => {
    const { entry } = validate([line({ kind: 'DEBIT', accountCode: '103', amount: '1,000' })], accounts, partners)
    expect(entry).toEqual(['전표는 2줄 이상이어야 합니다 (출금·입금은 1줄로 충분)', '차변과 대변이 1,000원 다릅니다'])
  })

  it('정상 전표는 오류가 없다', () => {
    const { byLine, entry } = validate(
      [
        line({ kind: 'DEBIT', accountCode: '103', amount: '5,000,000' }),
        line({ kind: 'CREDIT', accountCode: '110', partnerCode: '00101', amount: '5,000,000' }),
      ],
      accounts,
      partners,
    )
    expect(byLine.size).toBe(0)
    expect(entry).toEqual([])
  })
})
