import { useState } from 'react'
import { Link } from 'react-router'

import { ApiError } from '../../api/client'
import { EvidenceList } from './Evidence'
import { activeAttemptId, activeMission, reviewPath, useAttempt, useAttemptActions } from './hooks'
import type { Attempt, Enrollment, Finding } from './hooks'

type OutcomeKind = 'PASSED' | 'REVEALED' | 'PRACTICE_PASSED' | 'PRACTICE_QUIT'
type Outcome = { title: string; missionKey: string; kind: OutcomeKind }

const OUTCOME_TEXT: Record<OutcomeKind, string> = {
  PASSED: '김 팀장이 승인했습니다. 전표가 전기되어 장부에 반영됐어요. 목록에서 상태가 "전기"로 바뀐 것을 확인해 보세요.',
  REVEALED: '정답 전표를 넣어 두었습니다. 목록에서 그 전표를 열어 어느 계정이 차변·대변에 갔는지 확인해 보세요.',
  PRACTICE_PASSED: '김 팀장이 승인했습니다. 연습이라 장부에는 반영하지 않았고, 연습으로 쓴 전표는 지웠어요.',
  PRACTICE_QUIT: '연습을 그만두었습니다. 연습으로 쓰던 전표는 지웠어요.',
}

/**
 * 미션 패널: 실습 회사의 ERP 화면 옆에 붙어, 지금 하는 업무의 상황·힌트·결재 요청을 보여 준다.
 * ERP 화면은 실습용으로 따로 만들지 않는다 — 자유 모드와 같은 화면에 이 패널만 붙는다 (ADR-0012).
 */
export function MissionPanel({ enrollment }: { enrollment: Enrollment }) {
  // 지금 풀고 있는 미션: 진행 중인 본 시도, 또는 끝낸 미션을 다시 푸는 연습
  const current = activeMission(enrollment)
  const attemptId = current ? activeAttemptId(current) : null
  const attempt = useAttempt(attemptId)
  // 결재가 나면 그 미션은 더 이상 "진행 중"이 아니다. 결과를 읽을 수 있게 패널이 잠시 기억해 둔다
  const [outcome, setOutcome] = useState<Outcome | null>(null)

  if (current && attempt.data && attempt.data.id === attemptId) {
    return (
      <aside className="mission-panel" aria-label="미션">
        <ActiveMission
          key={attempt.data.id}
          attempt={attempt.data}
          companyId={enrollment.companyId}
          onDone={(kind) => setOutcome({ title: attempt.data.title, missionKey: attempt.data.missionKey, kind })}
        />
      </aside>
    )
  }

  return (
    <aside className="mission-panel" aria-label="미션">
      {outcome ? (
        <>
          <h2>{outcome.title}</h2>
          <p className={outcome.kind.endsWith('PASSED') ? 'ok' : undefined} role="status">
            {outcome.kind.endsWith('PASSED') && '✔ '}
            {OUTCOME_TEXT[outcome.kind]}
          </p>
          <Link to={reviewPath(outcome.missionKey)} className="button primary">
            해설 보기
          </Link>
        </>
      ) : (
        <p className="muted">진행 중인 업무가 없습니다.</p>
      )}
      <Link to="/desk">내 책상으로 →</Link>
    </aside>
  )
}

function ActiveMission({
  attempt,
  companyId,
  onDone,
}: {
  attempt: Attempt
  companyId: number
  onDone: (kind: OutcomeKind) => void
}) {
  const practice = attempt.practice
  const { submit, hint, reveal } = useAttemptActions(attempt.id, companyId)
  const [findings, setFindings] = useState<Finding[] | null>(null)
  const busy = submit.isPending || hint.isPending || reveal.isPending
  const failed = [submit, hint, reveal].find((m) => m.error)?.error
  const errorText = failed instanceof ApiError ? failed.userMessage : failed?.message

  // 통과하면 이 컴포넌트는 사라진다 → 사라져도 이어지는 mutateAsync().then()으로 결과를 넘긴다
  function request() {
    submit.mutateAsync().then(
      (r) => {
        if (r.passed) onDone(practice ? 'PRACTICE_PASSED' : 'PASSED')
        else setFindings(r.findings)
      },
      () => {},
    )
  }

  function skip() {
    const question = practice
      ? '연습을 그만둘까요? 연습으로 쓰던 임시 전표는 지워집니다.'
      : '쓰던 임시 전표는 지워지고 정답 전표가 대신 들어갑니다. 넘어갈까요?'
    if (!window.confirm(question)) return
    reveal.mutateAsync().then(
      () => onDone(practice ? 'PRACTICE_QUIT' : 'REVEALED'),
      () => {},
    )
  }

  return (
    <>
      <h2>
        {attempt.title} {practice && <span className="chip accent">다시 풀기</span>}
      </h2>
      <div className="memo">
        <div className="muted">김 팀장 · 거래일 {attempt.date}</div>
        <p>{attempt.brief}</p>
      </div>
      <EvidenceList evidence={attempt.evidence} />
      {practice ? (
        <p className="muted">
          연습입니다. 이 거래의 전표는 이미 장부에 있으니 목록의 전표는 보지 말고, <strong>거래일</strong>로 새 전표를 작성해
          저장(F3)한 뒤 결재를 요청하세요. 장부에는 반영되지 않습니다.
        </p>
      ) : (
        <p className="muted">
          자료를 확인하고, 일반전표 입력에서 <strong>거래일</strong>로 전표를 작성해 저장(F3)한 뒤 결재를 요청하세요. 맞으면 팀장이
          전기합니다.
        </p>
      )}

      {attempt.hints.length > 0 && (
        <ol className="hints">
          {attempt.hints.map((h) => (
            <li key={h}>{h}</li>
          ))}
        </ol>
      )}

      {findings && (
        <div className="alert" role="alert">
          <strong>반려되었습니다</strong>
          <ul>
            {findings.map((f) => (
              <li key={f.type + f.message}>{f.message}</li>
            ))}
          </ul>
        </div>
      )}
      {errorText && (
        <div className="alert" role="alert">
          {errorText}
        </div>
      )}

      <div className="mission-actions">
        <button className="primary" onClick={request} disabled={busy}>
          {submit.isPending ? '검토 중…' : '결재 요청'}
        </button>
        {attempt.hintsRemaining > 0 && (
          <button onClick={() => hint.mutate()} disabled={busy}>
            힌트 보기 ({attempt.hintsRemaining})
          </button>
        )}
      </div>
      <p className="muted mission-foot">
        결재 요청 {attempt.submissions}회 ·{' '}
        <button className="link" onClick={skip} disabled={busy}>
          {practice ? '연습 그만두기' : '정답 보고 넘어가기'}
        </button>{' '}
        · <Link to="/desk">내 책상</Link>
      </p>
    </>
  )
}
