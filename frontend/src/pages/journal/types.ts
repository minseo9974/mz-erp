// 전표 API DTO (backend JournalService.EntryView / JournalController.EntryRequest)

export type Side = 'DEBIT' | 'CREDIT'
export type EntryStatus = 'DRAFT' | 'POSTED'
export type EntryType = 'GENERAL' | 'SALES_PURCHASE' | 'OPENING' | 'CLOSING' | 'REVERSAL'

export interface EntryLine {
  lineNo: number
  side: Side
  accountCode: string
  accountName: string
  partnerCode: string | null
  partnerName: string | null
  amount: number
  memo: string | null
}

export interface JournalEntry {
  id: number
  entryDate: string // YYYY-MM-DD
  entryNo: number
  entryType: EntryType
  status: EntryStatus
  description: string | null
  debitTotal: number
  creditTotal: number
  version: number
  reversalOfId: number | null
  reversedById: number | null
  reversalReason: string | null
  postedAt: string | null
  lines: EntryLine[]
}

export interface EntryLineInput {
  side: Side
  accountCode: string
  partnerCode: string | null
  amount: number
  memo: string | null
}

export interface EntryInput {
  entryDate: string
  description: string | null
  lines: EntryLineInput[]
}

export const STATUS_LABEL: Record<EntryStatus, string> = { DRAFT: '임시', POSTED: '전기' }
export const TYPE_LABEL: Record<EntryType, string> = {
  GENERAL: '일반',
  SALES_PURCHASE: '매입매출',
  OPENING: '기초',
  CLOSING: '결산',
  REVERSAL: '역분개',
}
