/**
 * 일반전표 입력 화면의 "입력 모델" ↔ API 라인 변환과 화면 검증.
 * <p>
 * 화면은 전산회계 프로그램처럼 구분을 4가지로 받는다 (docs/design/screens.md):
 *   1 출금 · 2 입금 · 3 차변 · 4 대변
 * 출금·입금은 "상대편이 현금"인 거래를 한 줄로 입력하는 편의 기능이다.
 * 서버(전표 엔진)는 차변·대변만 알고, 현금 라인은 여기서 만들어 보낸다.
 *   출금 812 여비교통비 450,000 → (차) 812 450,000 + (대) 현금 450,000
 *   입금 108 외상매출금 300,000 → (대) 108 300,000 + (차) 현금 300,000
 */
import { parseWon } from '../../lib/money'
import type { Account, Partner } from '../../api/types'
import type { EntryLine, EntryLineInput } from './types'

export type LineKind = 'WITHDRAW' | 'DEPOSIT' | 'DEBIT' | 'CREDIT'

export const KIND_OPTIONS: { value: LineKind; key: string; label: string }[] = [
  { value: 'WITHDRAW', key: '1', label: '출금' },
  { value: 'DEPOSIT', key: '2', label: '입금' },
  { value: 'DEBIT', key: '3', label: '차변' },
  { value: 'CREDIT', key: '4', label: '대변' },
]

/**
 * 출금·입금의 상대 계정. 전산회계 프로그램의 화면 관례(출금·입금 = 현금)를 그대로 따른다.
 * 회계 불변식 6(계정코드 하드코딩 금지)은 장부·결산 같은 "판단 로직"에 대한 규칙이고,
 * 이 값은 입력 편의용 기본값이라 예외로 둔다. 회사 계정표에 없거나 사용 중지면 출금·입금을 막는다.
 */
export const CASH_ACCOUNT_CODE = '101'

export interface FormLine {
  key: number
  kind: LineKind
  accountCode: string
  partnerCode: string
  memo: string
  amount: string // 사용자가 친 그대로 ("1,000,000"). 저장할 때 정수로 바꾼다
}

let nextKey = 1
export function emptyLine(memo = ''): FormLine {
  return { key: nextKey++, kind: 'DEBIT', accountCode: '', partnerCode: '', memo, amount: '' }
}

/** 입력 줄 → API 라인. 출금·입금은 현금 라인이 하나씩 더 붙는다. */
export function toApiLines(lines: FormLine[]): EntryLineInput[] {
  return lines.flatMap((l) => {
    const amount = parseWon(l.amount) ?? 0
    const partnerCode = l.partnerCode.trim() || null
    const memo = l.memo.trim() || null
    const accountCode = l.accountCode.trim()
    switch (l.kind) {
      case 'DEBIT':
      case 'CREDIT':
        return [{ side: l.kind, accountCode, partnerCode, amount, memo }]
      case 'WITHDRAW':
        return [
          { side: 'DEBIT' as const, accountCode, partnerCode, amount, memo },
          { side: 'CREDIT' as const, accountCode: CASH_ACCOUNT_CODE, partnerCode: null, amount, memo },
        ]
      case 'DEPOSIT':
        return [
          { side: 'CREDIT' as const, accountCode, partnerCode, amount, memo },
          { side: 'DEBIT' as const, accountCode: CASH_ACCOUNT_CODE, partnerCode: null, amount, memo },
        ]
    }
  })
}

/**
 * 저장된 전표 → 입력 줄. 2줄짜리 현금 전표는 다시 출금·입금 한 줄로 접어서 보여 준다.
 * (예: 차 812 / 대 101 → "출금 812"). 그 외는 차변·대변 그대로.
 */
export function fromEntryLines(lines: EntryLine[]): FormLine[] {
  if (lines.length === 2) {
    const cash = lines.find((l) => l.accountCode === CASH_ACCOUNT_CODE)
    const other = lines.find((l) => l !== cash)
    if (cash && other && other.accountCode !== CASH_ACCOUNT_CODE && cash.amount === other.amount) {
      return [toFormLine(other, cash.side === 'CREDIT' ? 'WITHDRAW' : 'DEPOSIT')]
    }
  }
  return lines.map((l) => toFormLine(l, l.side))
}

function toFormLine(l: EntryLine, kind: LineKind): FormLine {
  return {
    key: nextKey++,
    kind,
    accountCode: l.accountCode,
    partnerCode: l.partnerCode ?? '',
    memo: l.memo ?? '',
    amount: l.amount.toLocaleString('ko-KR'),
  }
}

export interface Totals {
  debit: number
  credit: number
  difference: number // 차변 - 대변. 0이어야 저장 가능
}

/** 현금 자동 라인까지 포함한 합계. 서버가 계산할 값과 같다. */
export function totals(lines: FormLine[]): Totals {
  let debit = 0
  let credit = 0
  for (const l of toApiLines(lines)) {
    if (l.side === 'DEBIT') debit += l.amount
    else credit += l.amount
  }
  return { debit, credit, difference: debit - credit }
}

/**
 * 화면에서 미리 막는 오류. 서버가 같은 규칙을 다시 검사하므로(앱·DB) 여기서는 "빨리 알려 주기"가 목적이다.
 * 반환: 줄 key → 메시지 목록, 그리고 전표 전체 메시지.
 */
export function validate(
  lines: FormLine[],
  accounts: Map<string, Account>,
  partners: Map<string, Partner>,
): { byLine: Map<number, string[]>; entry: string[] } {
  const byLine = new Map<number, string[]>()
  const add = (key: number, msg: string) => byLine.set(key, [...(byLine.get(key) ?? []), msg])

  for (const l of lines) {
    const code = l.accountCode.trim()
    const account = accounts.get(code)
    if (!code) add(l.key, '계정과목을 입력하세요')
    else if (!account) add(l.key, `계정 ${code}이(가) 없습니다`)
    else if (account.system) add(l.key, `${account.name}은(는) 결산 전용 계정입니다`)
    else if (!account.active) add(l.key, `${account.name}은(는) 사용 중지된 계정입니다`)

    const partnerCode = l.partnerCode.trim()
    if (partnerCode && !partners.has(partnerCode)) add(l.key, `거래처 ${partnerCode}이(가) 없습니다`)
    if (!partnerCode && account?.requiresPartner) add(l.key, `${account.name}은(는) 거래처가 필요합니다`)

    const amount = parseWon(l.amount)
    if (amount === null || amount <= 0) add(l.key, '금액은 0보다 큰 정수로 입력하세요')

    if ((l.kind === 'WITHDRAW' || l.kind === 'DEPOSIT') && code === CASH_ACCOUNT_CODE) {
      add(l.key, '출금·입금의 상대 계정은 현금이 될 수 없습니다')
    }
  }

  const entry: string[] = []
  const usesCash = lines.some((l) => l.kind === 'WITHDRAW' || l.kind === 'DEPOSIT')
  const cash = accounts.get(CASH_ACCOUNT_CODE)
  if (usesCash && (!cash || !cash.active)) entry.push(`출금·입금에 쓸 현금 계정(${CASH_ACCOUNT_CODE})이 없습니다. 차변·대변으로 입력하세요`)
  if (toApiLines(lines).length < 2) entry.push('전표는 2줄 이상이어야 합니다 (출금·입금은 1줄로 충분)')
  const t = totals(lines)
  if (t.difference !== 0) entry.push(`차변과 대변이 ${Math.abs(t.difference).toLocaleString('ko-KR')}원 다릅니다`)
  return { byLine, entry }
}
