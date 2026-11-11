import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { Account, Partner } from '../../api/types'
import type { JournalEntry } from '../journal/types'
import { OpeningEditor } from './OpeningBalancePage'

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

const ACCOUNTS = [
  account('101', '현금'),
  account('108', '외상매출금', { requiresPartner: true }),
  account('331', '자본금', { category: 'EQUITY', subgroup: '자본금', normalBalance: 'CREDIT' }),
  account('404', '제품매출', { category: 'REVENUE', subgroup: '매출', normalBalance: 'CREDIT', statement: 'IS' }),
]
const PARTNERS: Partner[] = [{ code: '00101', name: '(주)한빛전자', bizRegNo: null, partnerType: 'CUSTOMER', active: true }]

function renderEditor(props: Partial<Parameters<typeof OpeningEditor>[0]> = {}) {
  const onNotice = vi.fn()
  const onCarry = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <OpeningEditor
        companyId={1}
        year={2026}
        termNo={6}
        startDate="2026-01-01"
        carried={null}
        accounts={ACCOUNTS}
        partners={PARTNERS}
        canApprove
        periodClosed={false}
        onCarry={onCarry}
        onNotice={onNotice}
        {...props}
      />
    </QueryClientProvider>,
  )
  return { onNotice, onCarry }
}

function stubFetch(response: unknown) {
  document.cookie = 'XSRF-TOKEN=t; path=/'
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const openingEntry = (patch: Partial<JournalEntry> = {}): JournalEntry => ({
  id: 3,
  entryDate: '2026-01-01',
  entryNo: 1,
  entryType: 'OPENING',
  status: 'DRAFT',
  description: '전기분 재무상태표 이월',
  debitTotal: 5_000_000,
  creditTotal: 5_000_000,
  version: 0,
  reversalOfId: null,
  reversedById: null,
  reversalReason: null,
  postedAt: null,
  lines: [
    { lineNo: 1, side: 'DEBIT', accountCode: '101', accountName: '현금', partnerCode: null, partnerName: null, amount: 5_000_000, memo: null },
    { lineNo: 2, side: 'CREDIT', accountCode: '331', accountName: '자본금', partnerCode: null, partnerName: null, amount: 5_000_000, memo: null },
  ],
  ...patch,
})

describe('전기분 재무상태표 입력', () => {
  it('계정·금액을 Enter로 이어 치고 F3으로 저장하면 정상잔액 방향으로 라인이 전송된다', async () => {
    const fetchMock = stubFetch({ termNo: 6, startDate: '2026-01-01', endDate: '2026-12-31', entry: openingEntry() })
    const { onNotice } = renderEditor()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('1번째 줄 계정'), '101{Enter}{Enter}5000000{Enter}') // 마지막 칸 Enter = 줄 추가
    expect(screen.getByLabelText('2번째 줄 계정')).toHaveFocus()
    await user.keyboard('331{Enter}{Enter}5000000{F3}')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/companies/1/fiscal-years/2026/opening-balance')
    expect(init!.method).toBe('PUT')
    expect(JSON.parse(init!.body as string)).toEqual({
      lines: [
        { side: 'DEBIT', accountCode: '101', partnerCode: null, amount: 5_000_000, memo: null },
        { side: 'CREDIT', accountCode: '331', partnerCode: null, amount: 5_000_000, memo: null },
      ],
    })
    await vi.waitFor(() => expect(onNotice).toHaveBeenCalled())
  })

  it('자산과 부채·자본이 다르면 저장할 수 없고 차액을 보여 준다', async () => {
    renderEditor()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('1번째 줄 계정'), '101')
    await user.type(screen.getByLabelText('1번째 줄 금액'), '5000000')

    expect(screen.getByRole('button', { name: '저장 (F3)' })).toBeDisabled()
    expect(screen.getByLabelText('합계')).toHaveTextContent('차액 5,000,000')
  })

  it('코드 도움에는 재무상태표 계정만 나온다', async () => {
    renderEditor()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('1번째 줄 계정'), '{F2}')

    const dialog = screen.getByRole('dialog', { name: '계정과목 코드 도움' })
    expect(dialog).toHaveTextContent('101 현금')
    expect(dialog).not.toHaveTextContent('제품매출')
  })

  it('전기된 기초잔액은 고칠 수 없고 승인자에게 역분개만 보인다', () => {
    renderEditor({ entry: openingEntry({ status: 'POSTED', postedAt: '2026-01-01T00:00:00Z' }) })

    expect(screen.getByLabelText('1번째 줄 금액')).toBeDisabled()
    expect(screen.getByLabelText('2번째 줄 금액')).toHaveValue('5,000,000')
    expect(screen.queryByRole('button', { name: '저장 (F3)' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '역분개' })).toBeInTheDocument()
  })

  it('역분개 직후에는 넘겨받은 줄로 시작해서 처음부터 다시 치지 않는다', () => {
    renderEditor({ carried: [{ key: 9001, accountCode: '101', partnerCode: '', amount: '5,000,000' }] })

    expect(screen.getByLabelText('1번째 줄 계정')).toHaveValue('101')
    expect(screen.getByLabelText('1번째 줄 금액')).toHaveValue('5,000,000')
  })
})
