// 백엔드 DTO (docs/design/api.md). 3단계쯤 OpenAPI에서 자동 생성으로 바꾼다.

export type CompanyRole = 'WRITER' | 'APPROVER' | 'ADMIN'

export interface MyCompany {
  companyId: number
  name: string
  role: CompanyRole
}

export interface Me {
  userId: number
  loginId: string
  name: string
  companies: MyCompany[]
}

export type AccountCategory = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE'
export type NormalBalance = 'DEBIT' | 'CREDIT'
export type StatementType = 'BS' | 'IS' | 'MC' | 'SYS'

export interface Account {
  code: string
  name: string
  category: AccountCategory
  subgroup: string
  normalBalance: NormalBalance
  contraOfCode: string | null
  requiresPartner: boolean
  statement: StatementType
  system: boolean
  active: boolean
}

export type PartnerType = 'CUSTOMER' | 'VENDOR' | 'BOTH' | 'BANK' | 'CARD' | 'ETC'

export interface Partner {
  code: string
  name: string
  bizRegNo: string | null
  partnerType: PartnerType
  active: boolean
}

export type FiscalStatus = 'OPEN' | 'CLOSED'

export interface FiscalPeriod {
  periodNo: number
  startDate: string
  endDate: string
  status: FiscalStatus
}

export interface FiscalYear {
  termNo: number
  startDate: string
  endDate: string
  status: FiscalStatus
  periods: FiscalPeriod[]
}

export const CATEGORY_LABEL: Record<AccountCategory, string> = {
  ASSET: '자산',
  LIABILITY: '부채',
  EQUITY: '자본',
  REVENUE: '수익',
  EXPENSE: '비용',
}

export const STATEMENT_LABEL: Record<StatementType, string> = {
  BS: '재무상태표',
  IS: '손익계산서',
  MC: '제조원가명세서',
  SYS: '시스템',
}

export const PARTNER_TYPE_LABEL: Record<PartnerType, string> = {
  CUSTOMER: '매출처',
  VENDOR: '매입처',
  BOTH: '매출·매입',
  BANK: '금융기관',
  CARD: '카드사',
  ETC: '기타',
}

export const ROLE_LABEL: Record<CompanyRole, string> = {
  WRITER: '작성자',
  APPROVER: '승인자',
  ADMIN: '관리자',
}
