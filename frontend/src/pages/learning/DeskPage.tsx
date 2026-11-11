import { Link, useNavigate } from 'react-router'

import { ApiError } from '../../api/client'
import { useLogout, useMe } from '../../api/hooks'
import { allMissions, missionPath, nextMission, useEnroll, useMyEnrollments, useStartMission } from './hooks'
import type { Enrollment, MissionStatus } from './hooks'

const STATUS_MARK: Record<MissionStatus, { mark: string; label: string }> = {
  PASSED: { mark: '✔', label: '완료' },
  REVEALED: { mark: '↷', label: '정답 보고 넘어감' },
  IN_PROGRESS: { mark: '●', label: '진행 중' },
  NOT_STARTED: { mark: '○', label: '대기' },
}

/**
 * 내 책상 (docs/design/training-site.md §5): 실습의 첫 화면.
 * 과정을 시작하기 전에는 입사 안내를, 시작한 뒤에는 받은 업무함(지금 할 일)과 과정 지도를 보여 준다.
 */
export function DeskPage() {
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()
  const enrollments = useMyEnrollments()
  const enroll = useEnroll()
  const enrollment = enrollments.data?.[0]
  const enrollError = enroll.error instanceof ApiError ? enroll.error.userMessage : enroll.error?.message

  return (
    <div className="desk">
      <header className="desk-header">
        <span className="logo">MZ-ERP 실습</span>
        <span className="spacer" />
        <span className="muted">{me.data?.name}</span>
        <Link to="/companies">자유 모드</Link>
        <button onClick={() => logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
          로그아웃
        </button>
      </header>

      <main className="desk-main">
        {enrollments.isPending && <p className="muted">불러오는 중…</p>}

        {enrollments.isSuccess && !enrollment && (
          <section className="card">
            <h1>(주)엠제트전자 회계팀에 오신 것을 환영합니다</h1>
            <p>
              이론은 배웠지만 실무는 처음인 분을 위한 실습입니다. 전자부품을 만드는 회사의 회계팀 신입이 되어, 통장 내역과
              증빙을 보고 전표를 올리고 팀장의 결재를 받습니다.
            </p>
            <ul className="desk-points">
              <li>나만의 실습 회사가 만들어집니다. 틀려도 다른 사람에게 영향이 없습니다.</li>
              <li>전표는 실제 회계 프로그램 화면에서 직접 입력합니다.</li>
              <li>틀리면 팀장이 왜 반려했는지 알려 줍니다. 고쳐서 다시 올리면 됩니다.</li>
            </ul>
            {enrollError && (
              <div className="alert" role="alert">
                {enrollError}
              </div>
            )}
            <button className="primary" onClick={() => enroll.mutate()} disabled={enroll.isPending}>
              {enroll.isPending ? '실습 회사를 준비하는 중…' : '입사하기'}
            </button>
          </section>
        )}

        {enrollment && <Desk enrollment={enrollment} />}
      </main>
    </div>
  )
}

function Desk({ enrollment }: { enrollment: Enrollment }) {
  const navigate = useNavigate()
  const start = useStartMission(enrollment.id)
  const missions = allMissions(enrollment)
  const next = nextMission(enrollment)
  const finished = missions.filter((m) => m.status === 'PASSED' || m.status === 'REVEALED').length
  const startError = start.error instanceof ApiError ? (start.error.detail ?? start.error.title) : start.error?.message

  function open() {
    if (!next) return
    // 이미 진행 중이면 서버가 같은 시도를 돌려준다
    start.mutate(next.key, { onSuccess: () => navigate(missionPath(enrollment.companyId, next.date)) })
  }

  return (
    <>
      <section className="card">
        <h1>받은 업무함</h1>
        {next ? (
          <>
            <div className="memo">
              <div className="muted">
                김 팀장 · 거래일 {next.date} · <strong>{next.title}</strong>
              </div>
              <p>{next.brief}</p>
            </div>
            {startError && (
              <div className="alert" role="alert">
                {startError}
              </div>
            )}
            <div className="toolbar" style={{ margin: 0 }}>
              <button className="primary" onClick={open} disabled={start.isPending}>
                {next.status === 'IN_PROGRESS' ? '이어서 하기' : '업무 시작'}
              </button>
              <Link to={`/companies/${enrollment.companyId}`}>실습 회사 장부 둘러보기</Link>
            </div>
          </>
        ) : (
          <p>
            지금까지 열린 업무를 모두 마쳤습니다. 다음 장은 준비 중입니다.{' '}
            <Link to={`/companies/${enrollment.companyId}/journal-entries`}>내가 올린 전표 보기</Link>
          </p>
        )}
      </section>

      <section className="card">
        <h2>
          {enrollment.courseTitle}{' '}
          <span className="muted">
            {finished} / {missions.length}
          </span>
        </h2>
        {enrollment.chapters.map((c) => (
          <div key={c.key}>
            <h3 className="chapter-title">{c.title}</h3>
            <ol className="mission-list">
              {c.missions.map((m) => (
                <li key={m.key} className={`mission ${m.status.toLowerCase().replace('_', '-')}`}>
                  <span className="mark" title={STATUS_MARK[m.status].label}>
                    {STATUS_MARK[m.status].mark}
                  </span>
                  <span className="muted">{m.date.slice(5)}</span>
                  <span>{m.title}</span>
                  <span className="muted status">{STATUS_MARK[m.status].label}</span>
                </li>
              ))}
            </ol>
          </div>
        ))}
      </section>
    </>
  )
}
