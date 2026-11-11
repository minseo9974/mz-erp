import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'

import { DeskPage } from './DeskPage'
import type { Attempt, Enrollment, MissionStatus } from './hooks'
import { MissionPanel } from './MissionPanel'

const mission = (key: string, title: string, date: string, status: MissionStatus, attemptId: number | null = null) => ({
  key,
  title,
  brief: `${title} 상황 설명`,
  date,
  status,
  attemptId,
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

    expect(await screen.findByRole('heading', { name: '받은 업무함' })).toBeInTheDocument()
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
    expect(screen.getByText('1 / 2')).toBeInTheDocument() // 진행도
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
  })
})
