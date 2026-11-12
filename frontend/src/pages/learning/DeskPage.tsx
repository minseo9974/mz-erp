import { Link, useNavigate } from 'react-router'

import { ApiError } from '../../api/client'
import { useMe } from '../../api/hooks'
import { DeskHeader } from './DeskHeader'
import {
  activeAttemptId,
  allMissions,
  isFinished,
  missionPath,
  nextMission,
  reviewPath,
  useEnroll,
  useMyEnrollments,
  useStartMission,
} from './hooks'
import type { Enrollment, MissionSummary } from './hooks'

/**
 * 내 책상 (docs/design/training-site.md §5): 실습의 첫 화면.
 * 과정을 시작하기 전에는 입사 안내를, 시작한 뒤에는 지금 할 일과 업무 목록을 보여 준다.
 * 업무 목록에서 끝낸 업무를 누르면 해설과 다시 풀기가 있는 '다시 보기'로 간다.
 */
export function DeskPage() {
  const enrollments = useMyEnrollments()
  const enrollment = enrollments.data?.[0]

  return (
    <div className="desk">
      <DeskHeader />
      <main className="desk-main">
        {enrollments.isPending && <p className="muted">불러오는 중…</p>}
        {enrollments.isError && (
          <div className="alert" role="alert">
            실습 정보를 불러오지 못했습니다. 잠시 뒤 새로고침해 주세요.
          </div>
        )}
        {enrollments.isSuccess && !enrollment && <Welcome />}
        {enrollment && <Desk enrollment={enrollment} />}
      </main>
    </div>
  )
}

function Welcome() {
  const enroll = useEnroll()
  const error = enroll.error instanceof ApiError ? enroll.error.userMessage : enroll.error?.message

  return (
    <section className="card welcome">
      <p className="eyebrow">신입 회계 실무 실습</p>
      <h1>(주)엠제트전자 회계팀에 오신 것을 환영합니다</h1>
      <p>
        이론은 배웠지만 실무는 처음인 분을 위한 실습입니다. 전자부품을 만드는 회사의 회계팀 신입이 되어, 통장 내역과 증빙을
        보고 전표를 올리고 팀장의 결재를 받습니다.
      </p>
      <ol className="steps">
        <li>
          <strong>업무를 받습니다</strong>
          <span>팀장이 오늘 처리할 거래를 알려 줍니다.</span>
        </li>
        <li>
          <strong>전표를 올립니다</strong>
          <span>실제 회계 프로그램 화면에서 직접 입력합니다.</span>
        </li>
        <li>
          <strong>결재를 받습니다</strong>
          <span>틀리면 왜 반려됐는지 알려 줍니다. 고쳐서 다시 올리면 됩니다.</span>
        </li>
      </ol>
      <p className="muted">나만의 실습 회사가 만들어집니다. 틀려도 다른 사람에게 영향이 없습니다.</p>
      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}
      <div>
        <button className="primary large" onClick={() => enroll.mutate()} disabled={enroll.isPending}>
          {enroll.isPending ? '실습 회사를 준비하는 중…' : '입사하기'}
        </button>
      </div>
    </section>
  )
}

type Chip = { label: string; tone: 'accent' | 'ok' | 'warn' | 'idle' }

/** 지금 할 업무의 상태와, 그 업무를 여는 버튼의 글 */
function nowState(m: MissionSummary): { chip: Chip; action: string } {
  if (m.practiceAttemptId !== null) return { chip: { label: '다시 푸는 중', tone: 'accent' }, action: '이어서 풀기' }
  if (m.status === 'IN_PROGRESS') return { chip: { label: '진행 중', tone: 'accent' }, action: '이어서 하기' }
  return { chip: { label: '새 업무', tone: 'accent' }, action: '업무 시작' }
}

function Desk({ enrollment }: { enrollment: Enrollment }) {
  const me = useMe()
  const navigate = useNavigate()
  const start = useStartMission(enrollment.id)
  const missions = allMissions(enrollment)
  const next = nextMission(enrollment)
  const finished = missions.filter(isFinished).length
  const percent = missions.length === 0 ? 0 : Math.round((finished / missions.length) * 100)
  const startError = start.error instanceof ApiError ? (start.error.detail ?? start.error.title) : start.error?.message

  /** 지금 할 업무를 연다. 이미 풀고 있으면 그 화면으로 바로, 처음이면 시작(장부 잔액 저장)한 뒤에 간다 */
  function open(mission: MissionSummary) {
    const path = missionPath(enrollment.companyId, mission.date)
    if (activeAttemptId(mission) !== null) navigate(path)
    else start.mutate(mission.key, { onSuccess: () => navigate(path) })
  }

  return (
    <>
      <section className="desk-hero">
        <div>
          <p className="eyebrow">{enrollment.courseTitle}</p>
          <h1>{me.data?.name ? `${me.data.name}님의 책상` : '내 책상'}</h1>
        </div>
        <div className="progress">
          <div className="progress-label">
            <strong>
              {finished} / {missions.length}
            </strong>
            <span className="muted">업무 완료</span>
          </div>
          <div
            className="progress-track"
            role="progressbar"
            aria-label="진행도"
            aria-valuemin={0}
            aria-valuemax={missions.length}
            aria-valuenow={finished}
          >
            <div className="progress-fill" style={{ width: `${percent}%` }} />
          </div>
        </div>
      </section>

      <section className="card now" aria-labelledby="now-title">
        {next ? (
          <>
            <div className="now-head">
              <span className={`chip ${nowState(next).chip.tone}`}>{nowState(next).chip.label}</span>
              <span className="muted">김 팀장 · 거래일 {next.date}</span>
            </div>
            <h2 id="now-title">{next.title}</h2>
            <div className="memo">
              <p>{next.brief}</p>
            </div>
            {startError && (
              <div className="alert" role="alert">
                {startError}
              </div>
            )}
            <div className="now-actions">
              <button className="primary large" onClick={() => open(next)} disabled={start.isPending}>
                {nowState(next).action}
              </button>
              <Link to={`/companies/${enrollment.companyId}`}>실습 회사 장부 둘러보기</Link>
            </div>
          </>
        ) : (
          <>
            <div className="now-head">
              <span className="chip ok">모두 완료</span>
            </div>
            <h2 id="now-title">지금까지 열린 업무를 모두 마쳤습니다</h2>
            <p>
              다음 장은 준비 중입니다. 아래 목록에서 끝낸 업무를 누르면 해설을 다시 읽거나 다시 풀어 볼 수 있어요.{' '}
              <Link to={`/companies/${enrollment.companyId}/journal-entries`}>내가 올린 전표 보기</Link>
            </p>
          </>
        )}
      </section>

      <section className="card" aria-labelledby="map-title">
        <div className="map-head">
          <h2 id="map-title">업무 목록</h2>
          <span className="muted">끝낸 업무를 누르면 해설을 보고 다시 풀 수 있어요</span>
        </div>
        {enrollment.chapters.map((chapter, chapterIndex) => (
          <div key={chapter.key} className="chapter">
            <h3 className="chapter-title">
              <span>
                {chapterIndex + 1}장 · {chapter.title}
              </span>
              <span className="muted">
                {chapter.missions.filter(isFinished).length} / {chapter.missions.length}
              </span>
            </h3>
            <ol className="mission-list">
              {chapter.missions.map((m, i) => (
                <li key={m.key}>
                  <MissionRow
                    mission={m}
                    index={i + 1}
                    isNext={m.key === next?.key}
                    onOpen={() => open(m)}
                    disabled={start.isPending}
                  />
                </li>
              ))}
            </ol>
          </div>
        ))}
      </section>
    </>
  )
}

/**
 * 업무 목록의 한 줄. 상태에 따라 누르면 일어나는 일이 다르다.
 * 끝낸 업무 → 다시 보기(해설·다시 풀기), 지금 할 업무 → 전표 화면, 아직 열리지 않은 업무 → 누를 수 없다.
 */
function MissionRow({
  mission,
  index,
  isNext,
  onOpen,
  disabled,
}: {
  mission: MissionSummary
  index: number
  isNext: boolean
  onOpen: () => void
  disabled: boolean
}) {
  const body = (mark: string, chip: Chip, action: string | null) => (
    <>
      <span className="step" aria-hidden="true">
        {mark}
      </span>
      <span className="mission-text">
        <span className="mission-title">{mission.title}</span>
        <span className="muted mission-date">거래일 {mission.date}</span>
      </span>
      <span className={`chip ${chip.tone}`}>{chip.label}</span>
      <span className="mission-go" aria-hidden="true">
        {action}
      </span>
    </>
  )

  if (isFinished(mission)) {
    const chip: Chip =
      mission.practiceAttemptId !== null
        ? { label: '다시 푸는 중', tone: 'accent' }
        : mission.status === 'PASSED'
          ? { label: '완료', tone: 'ok' }
          : { label: '정답 확인', tone: 'warn' }
    return (
      <Link to={reviewPath(mission.key)} className="mission-row done" aria-label={`${mission.title} 해설 보기 · 다시 풀기`}>
        {body(mission.status === 'PASSED' ? '✓' : '↷', chip, '해설 ›')}
      </Link>
    )
  }
  if (isNext) {
    const { chip, action } = nowState(mission)
    return (
      <button
        type="button"
        className="mission-row current"
        onClick={onOpen}
        disabled={disabled}
        aria-label={`${mission.title} ${action}`}
      >
        {body(String(index), chip, mission.status === 'IN_PROGRESS' ? '이어서 ›' : '시작 ›')}
      </button>
    )
  }
  return (
    <div className="mission-row locked" aria-disabled="true" title="앞의 업무를 끝내면 열립니다">
      {body(String(index), { label: '대기', tone: 'idle' }, null)}
    </div>
  )
}
