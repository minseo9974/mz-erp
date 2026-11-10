import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { Account, Partner } from '../../api/types'
import { EntryEditor } from './EntryEditor'
import type { JournalEntry } from './types'

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
  account('103', '보통예금'),
  account('110', '받을어음', { requiresPartner: true }),
  account('812', '여비교통비', { category: 'EXPENSE', subgroup: '판매비와관리비' }),
]
const PARTNERS: Partner[] = [{ code: '00101', name: '(주)한빛전자', bizRegNo: null, partnerType: 'CUSTOMER', active: true }]

function renderEditor(props: Partial<Parameters<typeof EntryEditor>[0]> = {}) {
  const onSaved = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <EntryEditor
        companyId={1}
        month="2026-03"
        defaultDay={10}
        accounts={ACCOUNTS}
        partners={PARTNERS}
        canApprove
        periodClosed={false}
        onSaved={onSaved}
        onDeleted={vi.fn()}
        onOpenEntry={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  )
  return { onSaved }
}

function stubFetch(response: unknown) {
  document.cookie = 'XSRF-TOKEN=t; path=/'
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(response), { status: 201 }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const savedEntry = (patch: Partial<JournalEntry> = {}): JournalEntry => ({
  id: 7,
  entryDate: '2026-03-10',
  entryNo: 1,
  entryType: 'GENERAL',
  status: 'DRAFT',
  description: '출장비',
  debitTotal: 450_000,
  creditTotal: 450_000,
  version: 0,
  reversalOfId: null,
  reversedById: null,
  reversalReason: null,
  postedAt: null,
  lines: [],
  ...patch,
})

describe('EntryEditor', () => {
  it('출금 한 줄을 Enter로 입력하고 F3으로 저장하면 현금 라인이 붙어 2줄로 전송된다', async () => {
    const fetchMock = stubFetch(savedEntry())
    const { onSaved } = renderEditor()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('1번째 줄 구분'), '1')
    await user.type(screen.getByLabelText('1번째 줄 계정'), '812{Enter}')
    expect(screen.getByText('여비교통비')).toBeInTheDocument()
    expect(screen.getByLabelText('1번째 줄 거래처')).toHaveFocus() // Enter = 다음 칸
    await user.keyboard('{Enter}출장비{Enter}450000{F3}')

    const body = JSON.parse(fetchMock.mock.calls[0][1]!.body as string)
    expect(body).toEqual({
      entryDate: '2026-03-10',
      description: '출장비',
      lines: [
        { side: 'DEBIT', accountCode: '812', partnerCode: null, amount: 450_000, memo: '출장비' },
        { side: 'CREDIT', accountCode: '101', partnerCode: null, amount: 450_000, memo: '출장비' },
      ],
    })
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled())
  })

  it('계정 칸에 이름을 치고 Enter → 코드 도움이 열리고, 고르면 코드가 들어간다', async () => {
    renderEditor()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('1번째 줄 계정'), '보통{Enter}')

    const dialog = screen.getByRole('dialog', { name: '계정과목 코드 도움' })
    expect(dialog).toHaveTextContent('103 보통예금')
    await user.keyboard('{Enter}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('1번째 줄 계정')).toHaveValue('103')
  })

  it('차액이 남은 채 마지막 칸에서 Enter → 반대편 줄을 차액으로 채워 추가한다', async () => {
    renderEditor()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('1번째 줄 계정'), '103{Enter}{Enter}받을어음 만기{Enter}5000000{Enter}')

    expect(screen.getByLabelText('2번째 줄 구분')).toHaveValue('4') // 4 대변
    expect(screen.getByLabelText('2번째 줄 금액')).toHaveValue('5,000,000')
    expect(screen.getByLabelText('2번째 줄 적요')).toHaveValue('받을어음 만기')
    expect(screen.getByLabelText('2번째 줄 구분')).toHaveFocus()
  })

  it('차액이 있으면 저장 버튼이 비활성이고 차액을 보여 준다', async () => {
    renderEditor()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('1번째 줄 계정'), '103')
    await user.type(screen.getByLabelText('1번째 줄 금액'), '1000')

    expect(screen.getByRole('button', { name: '저장 (F3)' })).toBeDisabled()
    expect(screen.getByLabelText('합계')).toHaveTextContent('차액 1,000')
  })

  it('거래처 필수 계정에 거래처가 없으면 저장하지 않고 그 줄에 이유를 보여 준다', async () => {
    const fetchMock = stubFetch({})
    renderEditor()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('1번째 줄 구분'), '2')
    await user.type(screen.getByLabelText('1번째 줄 계정'), '110')
    await user.type(screen.getByLabelText('1번째 줄 금액'), '300000')
    await user.click(screen.getByRole('button', { name: '저장 (F3)' }))

    expect(screen.getByText('받을어음은(는) 거래처가 필요합니다')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('전기된 전표는 편집할 수 없고, 승인자에게 역분개 버튼만 보인다', () => {
    renderEditor({
      entry: savedEntry({
        status: 'POSTED',
        postedAt: '2026-03-10T09:00:00Z',
        lines: [
          { lineNo: 1, side: 'DEBIT', accountCode: '812', accountName: '여비교통비', partnerCode: null, partnerName: null, amount: 450_000, memo: '출장비' },
          { lineNo: 2, side: 'CREDIT', accountCode: '101', accountName: '현금', partnerCode: null, partnerName: null, amount: 450_000, memo: '출장비' },
        ],
      }),
    })
    expect(screen.getByLabelText('1번째 줄 구분')).toHaveValue('1') // 현금 전표는 1 출금 한 줄로
    expect(screen.getByLabelText('1번째 줄 계정')).toBeDisabled()
    expect(screen.queryByRole('button', { name: '저장 (F3)' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '역분개' })).toBeInTheDocument()
  })

  it('작성자(WRITER)에게는 전기 버튼 대신 안내가 보인다', () => {
    renderEditor({ canApprove: false, entry: savedEntry() })
    expect(screen.queryByRole('button', { name: '전기 (F8)' })).not.toBeInTheDocument()
    expect(screen.getByText('전기는 승인자 이상만 할 수 있습니다')).toBeInTheDocument()
  })
})
