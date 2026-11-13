import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'

import { DeskPage } from './DeskPage'
import type { Attempt, Enrollment, Evidence, MissionReview, MissionStatus } from './hooks'
import { MissionPanel } from './MissionPanel'
import { MissionReviewPage } from './MissionReviewPage'

const mission = (key: string, title: string, date: string, status: MissionStatus, attemptId: number | null = null) => ({
  key,
  title,
  brief: `${title} 상황 설명`,
  date,
  status,
  attemptId,
  practiceAttemptId: null as number | null,
})

const enrollment = (statuses: [MissionStatus, MissionStatus]): Enrollment => ({
  id: 3,
  companyId: 7,
  courseKey: 'accounting-basics',
  courseTitle: '회계팀 첫 1년',
  chapters: [
    {
      key: 'ch1',
      title: '전표의 기본',
      missions: [
        mission('withholding-tax-payment', '원천세 납부', '2026-01-10', statuses[0], statuses[0] === 'NOT_STARTED' ? null : 11),
        mission('accounts-payable-other-payment', '미지급금 지급', '2026-01-15', statuses[1]),
      ],
    },
  ],
})

const attempt = (patch: Partial<Attempt> = {}): Attempt => ({
  id: 11,
  missionKey: 'withholding-tax-payment',
  title: '원천세 납부',
  brief: '작년 12월 급여에서 떼어 둔 세금을 납부했어요.',
  date: '2026-01-10',
  status: 'IN_PROGRESS',
  practice: false,
  evidence: [],
  hints: [],
  hintsRemaining: 2,
  submissions: 0,
  ...patch,
})

/** 경로별 응답을 돌려주는 fetch. 함수면 호출할 때마다 실행한다 (호출 순서에 따라 응답을 바꿀 때) */
function stubApi(routes: Record<string, unknown | (() => unknown)>) {
  document.cookie = 'XSRF-TOKEN=t; path=/'
  const calls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const key = `${init?.method ?? 'GET'} ${String(input)}`
      calls.push(key)
      const route = routes[key]
      if (route === undefined) return new Response(JSON.stringify({ title: `no stub: ${key}` }), { status: 500 })
      return new Response(JSON.stringify(typeof route === 'function' ? route() : route), { status: 200 })
    }),
  )
  return calls
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderAt(path: string, element: React.ReactNode) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={path} element={element} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const ME = { userId: 1, loginId: 'kim', name: '김신입', companies: [] }

afterEach(() => vi.unstubAllGlobals())

describe('내 책상', () => {
  it('과정을 시작하기 전에는 입사 안내를 보여 주고, 입사하기를 누르면 과정을 시작한다', async () => {
    let enrolled = false
    const calls = stubApi({
      'GET /api/v1/me': ME,
      'GET /api/v1/learning/enrollments': () => (enrolled ? [enrollment(['NOT_STARTED', 'NOT_STARTED'])] : []),
      'POST /api/v1/learning/enrollments': () => {
        enrolled = true
        return enrollment(['NOT_STARTED', 'NOT_STARTED'])
      },
    })
    renderAt('/desk', <DeskPage />)

    await userEvent.setup().click(await screen.findByRole('button', { name: '입사하기' }))

    expect(await screen.findByRole('heading', { name: '업무 목록' })).toBeInTheDocument()
    expect(calls).toContain('POST /api/v1/learning/enrollments')
  })

  it('받은 업무함에 지금 할 업무가 나오고, 업무 시작을 누르면 거래일이 속한 달의 전표 화면으로 간다', async () => {
    const calls = stubApi({
      'GET /api/v1/me': ME,
      'GET /api/v1/learning/enrollments': [enrollment(['PASSED', 'NOT_STARTED'])],
      'POST /api/v1/learning/enrollments/3/missions/accounts-payable-other-payment/start': attempt({ id: 12 }),
    })
    renderAt('/desk', <DeskPage />)

    expect(await screen.findByText('미지급금 지급 상황 설명')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: '진행도' })).toHaveAttribute('aria-valuenow', '1')
    await userEvent.setup().click(screen.getByRole('button', { name: '업무 시작' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/companies/7/journal-entries?month=2026-01')
    expect(calls).toContain('POST /api/v1/learning/enrollments/3/missions/accounts-payable-other-payment/start')
  })
})

describe('미션 패널', () => {
  it('상황과 거래일을 보여 주고, 힌트는 누를 때 하나씩 열린다', async () => {
    stubApi({
      'GET /api/v1/learning/attempts/11': attempt(),
      'POST /api/v1/learning/attempts/11/hint': attempt({ hints: ['맡아 둔 돈은 부채예요.'], hintsRemaining: 1 }),
    })
    renderAt('/x', <MissionPanel enrollment={enrollment(['IN_PROGRESS', 'NOT_STARTED'])} />)

    expect(await screen.findByText('작년 12월 급여에서 떼어 둔 세금을 납부했어요.')).toBeInTheDocument()
    expect(screen.getByText(/거래일 2026-01-10/)).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '힌트 보기 (2)' }))

    expect(await screen.findByText('맡아 둔 돈은 부채예요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '힌트 보기 (1)' })).toBeInTheDocument()
  })

  it('결재 요청이 반려되면 사유를 보여 주고, 통과하면 승인 안내로 바뀐다', async () => {
    let submits = 0
    stubApi({
      'GET /api/v1/learning/attempts/11': attempt(),
      'GET /api/v1/learning/enrollments': [enrollment(['IN_PROGRESS', 'NOT_STARTED'])],
      'POST /api/v1/learning/attempts/11/submit': () =>
        ++submits === 1
          ? { passed: false, findings: [{ type: 'SIDE_SWAPPED', message: '차변과 대변이 서로 바뀌었어요.' }], attempt: attempt({ submissions: 1 }) }
          : { passed: true, findings: [], attempt: attempt({ status: 'PASSED', submissions: 2 }) },
    })
    const view = (e: Enrollment) => (
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <MissionPanel enrollment={e} />
        </MemoryRouter>
      </QueryClientProvider>
    )
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { rerender } = render(view(enrollment(['IN_PROGRESS', 'NOT_STARTED'])))
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '결재 요청' }))
    expect(await screen.findByText('차변과 대변이 서로 바뀌었어요.')).toBeInTheDocument()
    expect(screen.getByText('반려되었습니다')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '결재 요청' }))
    await vi.waitFor(() => expect(submits).toBe(2))
    rerender(view(enrollment(['PASSED', 'NOT_STARTED']))) // 수강 목록을 다시 받아 오면 그 미션은 진행 중이 아니다

    expect(await screen.findByText(/김 팀장이 승인했습니다/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 책상으로 →' })).toHaveAttribute('href', '/desk')
    expect(screen.getByRole('link', { name: '해설 보기' })).toHaveAttribute('href', '/desk/missions/withholding-tax-payment')
  })

  it('다시 푸는 연습이면 연습이라고 알리고, 통과하면 장부에 반영하지 않았다고 안내한다', async () => {
    const practicing = (): Enrollment => {
      const e = enrollment(['PASSED', 'NOT_STARTED'])
      e.chapters[0].missions[0].practiceAttemptId = 21
      return e
    }
    stubApi({
      'GET /api/v1/learning/attempts/21': attempt({ id: 21, practice: true }),
      'GET /api/v1/learning/enrollments': [enrollment(['PASSED', 'NOT_STARTED'])],
      'POST /api/v1/learning/attempts/21/submit': {
        passed: true,
        findings: [],
        attempt: attempt({ id: 21, practice: true, status: 'PASSED', submissions: 1 }),
      },
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = (e: Enrollment) => (
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <MissionPanel enrollment={e} />
        </MemoryRouter>
      </QueryClientProvider>
    )
    const { rerender } = render(view(practicing()))

    expect(await screen.findByText('다시 풀기')).toBeInTheDocument()
    expect(screen.getByText(/장부에는 반영되지 않습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '연습 그만두기' })).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '결재 요청' }))
    rerender(view(enrollment(['PASSED', 'NOT_STARTED'])))

    expect(await screen.findByText(/연습이라 장부에는 반영하지 않았고/)).toBeInTheDocument()
  })
})

describe('증빙', () => {
  const evidence: Evidence[] = [
    {
      type: 'bank',
      title: '보통예금 거래내역',
      body: {
        account: '엠제트은행 보통예금',
        opening: 30000000,
        rows: [
          ['2026-01-10', '국세 근로소득세', 227280, 0, 29772720],
          ['2026-01-10', '지방소득세', 22720, 0, 29750000],
        ],
      },
    },
    {
      type: 'slip',
      title: '원천세 납부 내역',
      body: { issuer: '회계팀 정리', items: [['귀속', '2025년 12월 급여']], amount: 250000 },
    },
    {
      type: 'note',
      title: '받아 둔 약속어음',
      body: {
        number: '자가 00260310',
        amount: 5000000,
        issuer: '(주)한빛전자',
        payee: '(주)엠제트전자',
        issueDate: '2025-12-10',
        dueDate: '2026-03-10',
        place: '엠제트은행 본점',
      },
    },
  ]

  it('미션 패널의 자료를 누르면 문서가 열리고, 탭으로 다른 자료로 넘어가고, Esc로 닫힌다', async () => {
    stubApi({ 'GET /api/v1/learning/attempts/11': attempt({ evidence }) })
    renderAt('/x', <MissionPanel enrollment={enrollment(['IN_PROGRESS', 'NOT_STARTED'])} />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /보통예금 거래내역/ }))

    // 통장 거래내역: 이전 잔액, 출금 두 줄, 마지막 잔액
    const dialog = screen.getByRole('dialog', { name: '보통예금 거래내역' })
    expect(dialog).toHaveTextContent('실습용')
    expect(dialog).toHaveTextContent('이전 잔액30,000,000')
    expect(screen.getByText('지방소득세').closest('tr')).toHaveTextContent('2026-01-10지방소득세22,72029,750,000')

    await user.click(screen.getByRole('tab', { name: '원천세 납부 내역' }))
    expect(screen.getByRole('dialog', { name: '원천세 납부 내역' })).toHaveTextContent('250,000원')

    await user.click(screen.getByRole('tab', { name: '받아 둔 약속어음' }))
    const note = screen.getByRole('dialog', { name: '받아 둔 약속어음' })
    expect(note).toHaveTextContent('(주)엠제트전자 귀하')
    expect(note).toHaveTextContent('지급 기일2026-03-10')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('업무 목록', () => {
  it('끝낸 업무는 다시 보기로 가는 링크, 지금 할 업무는 버튼, 아직 열리지 않은 업무는 누를 수 없다', async () => {
    const e = enrollment(['PASSED', 'NOT_STARTED'])
    e.chapters[0].missions.push(mission('receivable-collection', '외상매출금 회수', '2026-02-25', 'NOT_STARTED'))
    stubApi({ 'GET /api/v1/me': ME, 'GET /api/v1/learning/enrollments': [e] })
    renderAt('/desk', <DeskPage />)

    const done = await screen.findByRole('link', { name: '원천세 납부 해설 보기 · 다시 풀기' })
    expect(done).toHaveAttribute('href', '/desk/missions/withholding-tax-payment')
    expect(screen.getByRole('button', { name: '미지급금 지급 업무 시작' })).toBeEnabled()
    // 잠긴 업무는 링크도 버튼도 아니다
    expect(screen.queryByRole('link', { name: /외상매출금 회수/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /외상매출금 회수/ })).toBeNull()
    expect(screen.getByText('대기')).toBeInTheDocument()
  })
})

describe('미션 다시 보기', () => {
  const review = (patch: Partial<MissionReview> = {}): MissionReview => ({
    key: 'withholding-tax-payment',
    title: '원천세 납부',
    chapterTitle: '전표의 기본',
    brief: '작년 12월 급여에서 떼어 둔 세금을 납부했어요.',
    date: '2026-01-10',
    status: 'PASSED',
    finishedAt: '2026-11-11T01:00:00Z',
    evidence: [],
    answer: [
      { side: 'DEBIT', accountCode: '254', accountName: '예수금', partnerName: null, amount: 250000 },
      { side: 'CREDIT', accountCode: '103', accountName: '보통예금', partnerName: null, amount: 250000 },
    ],
    explanation: '떼어 둔 돈은 맡은 돈이에요.\n\n- 부채가 줄면 차변이에요.\n\n흔한 실수: 비용으로 처리하는 것.\n\n실무에서는: 매달 10일이 납부일이에요.',
    hints: ['맡아 둔 돈은 부채예요.', '부채가 줄면 차변이에요!'],
    hintsUsed: 1,
    submissions: 2,
    rejections: [['차변과 대변이 서로 바뀌었어요.']],
    practices: 0,
    practiceAttemptId: null,
    ...patch,
  })
  const REVIEW = 'GET /api/v1/learning/enrollments/3/missions/withholding-tax-payment/review'
  const renderReview = () =>
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/desk/missions/withholding-tax-payment']}>
          <Routes>
            <Route path="/desk/missions/:missionKey" element={<MissionReviewPage />} />
            <Route path="*" element={<Where />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

  it('정답 분개와 해설, 내 기록을 보여 준다', async () => {
    stubApi({
      'GET /api/v1/me': ME,
      'GET /api/v1/learning/enrollments': [enrollment(['PASSED', 'NOT_STARTED'])],
      [REVIEW]: review(),
    })
    renderReview()

    expect(await screen.findByRole('heading', { name: '원천세 납부' })).toBeInTheDocument()
    expect(screen.getByText('결재 승인')).toBeInTheDocument()
    // 정답 분개: 차변 예수금 / 대변 보통예금
    const row = screen.getByText('예수금').closest('tr')!
    expect(row).toHaveTextContent('예수금 254250,000보통예금 103250,000')
    // 해설: 문단, 목록, 눈에 띄는 두 문단
    expect(screen.getByText('부채가 줄면 차변이에요.').tagName).toBe('LI')
    expect(screen.getByText('흔한 실수').closest('.callout')).toHaveTextContent('비용으로 처리하는 것.')
    expect(screen.getByText('실무에서는').closest('.callout')).toHaveClass('tip')
    // 내 기록
    expect(screen.getByText('결재 요청').nextElementSibling).toHaveTextContent('2회')
    expect(screen.getByText('차변과 대변이 서로 바뀌었어요.')).toBeInTheDocument()
  })

  it('다시 풀기를 누르면 연습을 시작하고 거래일이 속한 달의 전표 화면으로 간다', async () => {
    const calls = stubApi({
      'GET /api/v1/me': ME,
      'GET /api/v1/learning/enrollments': [enrollment(['PASSED', 'NOT_STARTED'])],
      [REVIEW]: review(),
      'POST /api/v1/learning/enrollments/3/missions/withholding-tax-payment/retry': attempt({ id: 21, practice: true }),
    })
    renderReview()

    await userEvent.setup().click(await screen.findByRole('button', { name: '다시 풀기' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/companies/7/journal-entries?month=2026-01')
    expect(calls).toContain('POST /api/v1/learning/enrollments/3/missions/withholding-tax-payment/retry')
  })

  it('아직 끝내지 않은 업무는 볼 수 없다고 안내한다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (input) => {
        const url = String(input)
        if (url.endsWith('/review')) {
          const body = { code: 'MISSION_NOT_FINISHED', title: '아직 끝내지 않은 미션입니다', detail: "'원천세 납부' 미션을 끝낸 뒤에 볼 수 있어요" }
          return new Response(JSON.stringify(body), { status: 409 })
        }
        return new Response(JSON.stringify(url.endsWith('/me') ? ME : [enrollment(['IN_PROGRESS', 'NOT_STARTED'])]), { status: 200 })
      }),
    )
    renderReview()

    expect(await screen.findByText("'원천세 납부' 미션을 끝낸 뒤에 볼 수 있어요")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 책상으로 돌아가기' })).toHaveAttribute('href', '/desk')
  })
})
