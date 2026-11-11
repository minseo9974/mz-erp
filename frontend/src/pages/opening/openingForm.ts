import type { Account, Partner } from '../../api/types'
import { formatWon, parseWon } from '../../lib/money'
import type { EntryLine, EntryLineInput, Side } from '../journal/types'

/**
 * 전기분 재무상태표 입력 폼의 순수 로직 (화면과 분리해서 테스트한다).
 *
 * 한 줄 = 계정(+거래처)의 기초잔액. 차변인지 대변인지는 사용자가 고르지 않고 **계정의 정상잔액**이 정한다.
 *   자산 → 차변 · 부채·자본 → 대변 · 차감 계정(대손충당금, 감가상각누계액) → 대변
 * 정상잔액과 반대인 잔액(드묾)은 금액을 음수로 적는다. 재무상태표에서 괄호로 표시하는 것과 같다.
 * 회계 노트 03 — docs/accounting-notes/03-opening-balance.md
 */
export interface OpeningRow {
  key: number
  accountCode: string
  partnerCode: string
  amount: string
}

let nextKey = 1

export function emptyRow(): OpeningRow {
  return { key: nextKey++, accountCode: '', partnerCode: '', amount: '' }
}

const isBlank = (r: OpeningRow) => !r.accountCode.trim() && !r.partnerCode.trim() && !r.amount.trim()
const opposite = (side: Side): Side => (side === 'DEBIT' ? 'CREDIT' : 'DEBIT')

/** 이 줄의 금액이 들어갈 쪽 */
export function sideOf(row: OpeningRow, accounts: Map<string, Account>): Side {
  const normal = accounts.get(row.accountCode.trim())?.normalBalance ?? 'DEBIT'
  const amount = parseWon(row.amount)
  return amount !== null && amount < 0 ? opposite(normal) : normal
}

/** 저장 요청의 라인. 비어 있는 줄은 뺀다 */
export function toApiLines(rows: OpeningRow[], accounts: Map<string, Account>): EntryLineInput[] {
  return rows
    .filter((r) => !isBlank(r))
    .map((r) => ({
      side: sideOf(r, accounts),
      accountCode: r.accountCode.trim(),
      partnerCode: r.partnerCode.trim() || null,
      amount: Math.abs(parseWon(r.amount) ?? 0),
      memo: null,
    }))
}

/** 저장된 기초잔액 전표 → 폼 줄 */
export function fromEntryLines(lines: EntryLine[], accounts: Map<string, Account>): OpeningRow[] {
  return lines.map((l) => {
    const normal = accounts.get(l.accountCode)?.normalBalance ?? l.side
    return {
      key: nextKey++,
      accountCode: l.accountCode,
      partnerCode: l.partnerCode ?? '',
      amount: formatWon(l.side === normal ? l.amount : -l.amount),
    }
  })
}

export function totals(rows: OpeningRow[], accounts: Map<string, Account>) {
  let debit = 0
  let credit = 0
  for (const r of rows) {
    const amount = Math.abs(parseWon(r.amount) ?? 0)
    if (sideOf(r, accounts) === 'DEBIT') debit += amount
    else credit += amount
  }
  return { debit, credit, difference: debit - credit }
}

/** 화면 검증. 서버(OpeningBalanceService)도 같은 규칙을 다시 검사한다 */
export function validate(rows: OpeningRow[], accounts: Map<string, Account>, partners: Map<string, Partner>) {
  const byRow = new Map<number, string[]>()
  const filled = rows.filter((r) => !isBlank(r))
  for (const r of filled) {
    const errors: string[] = []
    const code = r.accountCode.trim()
    const account = accounts.get(code)
    if (!code) errors.push('계정과목을 입력하세요')
    else if (!account) errors.push(`계정 ${code}이(가) 없습니다`)
    else if (account.statement !== 'BS') errors.push(`${account.name}은(는) 수익·비용 계정이라 기초잔액에 넣지 않습니다`)
    else if (!account.active) errors.push(`${account.name}은(는) 사용 중지된 계정입니다`)

    const partnerCode = r.partnerCode.trim()
    if (partnerCode && !partners.has(partnerCode)) errors.push(`거래처 ${partnerCode}이(가) 없습니다`)
    else if (!partnerCode && account?.requiresPartner) errors.push(`${account.name}은(는) 거래처별로 나눠 입력합니다`)

    const amount = parseWon(r.amount)
    if (amount === null || amount === 0) errors.push('금액을 입력하세요')

    if (errors.length) byRow.set(r.key, errors)
  }

  const entry: string[] = []
  const t = totals(filled, accounts)
  if (filled.length < 2) entry.push('기초잔액은 2줄 이상이어야 합니다')
  if (t.difference !== 0) {
    entry.push(`차변(자산)과 대변(부채·자본)이 ${formatWon(Math.abs(t.difference))}원 다릅니다`)
  }
  return { byRow, entry }
}
