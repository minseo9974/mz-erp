import type { ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { ApiError } from '../../api/client'
import { DeskHeader } from './DeskHeader'
import { missionPath, useMissionReview, useMyEnrollments, useRetryMission } from './hooks'
import type { AnswerLine, Enrollment, MissionReview } from './hooks'

const won = (n: number) => n.toLocaleString('ko-KR')

/**
 * 끝낸 미션 다시 보기: 상황 → 정답 분개 → 해설 → 내 기록, 그리고 다시 풀기.
 * 정답이 들어 있어서 미션을 끝낸 뒤에만 열린다 (서버가 409로 막는다).
 */
export function MissionReviewPage() {
  const { missionKey = '' } = useParams()
  const enrollments = useMyEnrollments()
  const enrollment = enrollments.data?.[0]
  const review = useMissionReview(enrollment?.id, missionKey)
  const notReady = enrollments.isSuccess && !enrollment
  const error = review.error instanceof ApiError ? (review.error.detail ?? review.error.title) : review.error?.message

  return (
    <div className="desk">
      <DeskHeader />
      <main className="desk-main">
        <Link to="/desk" className="back">
          ‹ 내 책상
        </Link>
        {(enrollments.isPending || (enrollment && review.isPending)) && <p className="muted">불러오는 중…</p>}
        {(notReady || review.isError) && (
          <section className="card">
            <h1>아직 볼 수 없는 업무입니다</h1>
            <p>{notReady ? '먼저 내 책상에서 과정을 시작해 주세요.' : error}</p>
            <p>
              <Link to="/desk">내 책상으로 돌아가기</Link>
            </p>
          </section>
        )}
        {enrollment && review.data && <Review enrollment={enrollment} review={review.data} />}
      </main>
    </div>
  )
}

function Review({ enrollment, review }: { enrollment: Enrollment; review: MissionReview }) {
  const navigate = useNavigate()
  const retry = useRetryMission(enrollment.id)
  const retryError = retry.error instanceof ApiError ? (retry.error.detail ?? retry.error.title) : retry.error?.message
  const journal = missionPath(enrollment.companyId, review.date)
  const practicing = review.practiceAttemptId !== null

  function again() {
    if (practicing) navigate(journal)
    else retry.mutate(review.key, { onSuccess: () => navigate(journal) })
  }

  return (
    <>
      <section className="review-head">
        <p className="eyebrow">{review.chapterTitle}</p>
        <h1>{review.title}</h1>
        <div className="review-meta">
          <span className={`chip ${review.status === 'PASSED' ? 'ok' : 'warn'}`}>
            {review.status === 'PASSED' ? '결재 승인' : '정답 보고 넘어감'}
          </span>
          <span className="muted">거래일 {review.date}</span>
        </div>
      </section>

      <section className="card" aria-labelledby="brief-title">
        <h2 id="brief-title">상황</h2>
        <div className="memo">
          <div className="muted">김 팀장</div>
          <p>{review.brief}</p>
        </div>
      </section>

      <section className="card" aria-labelledby="answer-title">
        <h2 id="answer-title">정답 분개</h2>
        <AnswerTable lines={review.answer} />
        <p className="muted">
          실습 회사 장부에는 이 전표가 전기되어 있어요. <Link to={journal}>일반전표에서 보기</Link>
        </p>
      </section>

      <section className="card" aria-labelledby="why-title">
        <h2 id="why-title">왜 이 분개인가</h2>
        <Explanation text={review.explanation} />
      </section>

      <section className="card" aria-labelledby="record-title">
        <h2 id="record-title">내 기록</h2>
        <dl className="stats">
          <div>
            <dt>결재 요청</dt>
            <dd>{review.submissions}회</dd>
          </div>
          <div>
            <dt>반려</dt>
            <dd>{review.rejections.length}회</dd>
          </div>
          <div>
            <dt>힌트</dt>
            <dd>
              {review.hintsUsed} / {review.hints.length}
            </dd>
          </div>
          <div>
            <dt>다시 풀기</dt>
            <dd>{review.practices}회</dd>
          </div>
        </dl>
        {review.rejections.length > 0 && (
          <details>
            <summary>반려됐던 사유 다시 보기</summary>
            <ol className="rejections">
              {review.rejections.map((messages, i) => (
                <li key={`${i}-${messages.join('|')}`}>
                  {messages.map((m) => (
                    <p key={m}>{m}</p>
                  ))}
                </li>
              ))}
            </ol>
          </details>
        )}
        <details>
          <summary>힌트 다시 보기</summary>
          <ol className="hints">
            {review.hints.map((h) => (
              <li key={h}>{h}</li>
            ))}
          </ol>
        </details>
      </section>

      <section className="card again" aria-labelledby="again-title">
        <div>
          <h2 id="again-title">{practicing ? '다시 풀고 있는 업무입니다' : '해설을 가리고 다시 풀어 보세요'}</h2>
          <p className="muted">
            연습이라 장부에는 반영되지 않아요. 결재는 똑같이 받고, 연습으로 쓴 전표는 결재가 끝나면 지워집니다.
          </p>
        </div>
        {retryError && (
          <div className="alert" role="alert">
            {retryError}
          </div>
        )}
        <div className="now-actions">
          <button className="primary large" onClick={again} disabled={retry.isPending}>
            {practicing ? '이어서 풀기' : '다시 풀기'}
          </button>
          <Link to="/desk">내 책상으로</Link>
        </div>
      </section>
    </>
  )
}

/** 차변과 대변을 좌우로 나란히 놓는다 — 교과서의 분개 모양 */
function AnswerTable({ lines }: { lines: AnswerLine[] }) {
  const debit = lines.filter((l) => l.side === 'DEBIT')
  const credit = lines.filter((l) => l.side === 'CREDIT')
  const rows = Array.from({ length: Math.max(debit.length, credit.length) }, (_, i) => [debit[i], credit[i]] as const)
  const cell = (line: AnswerLine | undefined) =>
    line ? (
      <>
        <td>
          <span className="acct">{line.accountName}</span> <span className="muted">{line.accountCode}</span>
          {line.partnerName && <div className="muted partner">{line.partnerName}</div>}
        </td>
        <td className="money">{won(line.amount)}</td>
      </>
    ) : (
      <>
        <td />
        <td />
      </>
    )

  return (
    <div className="answer-wrap">
      <table className="answer">
        <thead>
          <tr>
            <th colSpan={2}>차변</th>
            <th colSpan={2}>대변</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([d, c]) => (
            <tr key={`${d?.accountCode ?? ''}-${c?.accountCode ?? ''}`}>
              {cell(d)}
              {cell(c)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const CALLOUT = /^(흔한 실수[^:]*|실무에서는):\s*(.*)$/

/**
 * 해설 글(data/course의 explanation)을 문단·목록으로 보여 준다.
 * 빈 줄로 문단을 나누고, "- "는 목록, "1. "은 번호 목록, "흔한 실수:"·"실무에서는:"으로 시작하는 문단은 따로 눈에 띄게.
 */
function Explanation({ text }: { text: string }) {
  return (
    <div className="explanation">
      {text.split(/\n\s*\n/).map((block) => {
        const lines = block.split('\n').map((l) => l.trim()).filter(Boolean)
        const callout = CALLOUT.exec(lines[0] ?? '')
        const content: ReactNode[] = []
        if (callout) {
          content.push(<strong key="label">{callout[1]}</strong>)
          lines[0] = callout[2]
        }
        const plain = lines.filter((l) => l && !/^(- |\d+\. )/.test(l))
        const bullets = lines.filter((l) => l.startsWith('- ')).map((l) => l.slice(2))
        const numbered = lines.filter((l) => /^\d+\. /.test(l)).map((l) => l.replace(/^\d+\. /, ''))
        plain.forEach((l) => content.push(<p key={l}>{l}</p>))
        if (bullets.length > 0) content.push(<ul key="ul">{bullets.map((l) => <li key={l}>{l}</li>)}</ul>)
        if (numbered.length > 0) content.push(<ol key="ol">{numbered.map((l) => <li key={l}>{l}</li>)}</ol>)
        const tone = callout ? (callout[1].startsWith('흔한') ? 'callout warn' : 'callout tip') : 'para'
        return (
          <div key={block} className={tone}>
            {content}
          </div>
        )
      })}
    </div>
  )
}
