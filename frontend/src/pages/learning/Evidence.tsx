import { useEffect, useRef, useState } from 'react'

import type { BankEvidence, Evidence, NoteEvidence, SlipEvidence } from './hooks'

const won = (n: number) => n.toLocaleString('ko-KR')

const KIND: Record<Evidence['type'], string> = { bank: '통장', slip: '확인증', note: '어음' }

/**
 * 증빙 목록 (docs/design/training-site.md §5 "증빙 뷰어").
 * 실무에서는 통장 내역과 증빙을 보고 전표를 쓴다 — 미션의 금액·거래처·날짜는 여기서 읽어 낸다.
 * 누르면 문서가 크게 열린다. 문서는 미션 데이터로 그리고, 모두 '실습용' 표시를 넣는다.
 */
export function EvidenceList({ evidence }: { evidence: Evidence[] }) {
  const [open, setOpen] = useState<number | null>(null)
  if (evidence.length === 0) return null

  return (
    <div className="evidence">
      <div className="evidence-title">자료</div>
      <ul className="evidence-list">
        {evidence.map((e, i) => (
          <li key={e.type + e.title}>
            <button type="button" className="evidence-item" onClick={() => setOpen(i)}>
              <span className="kind">{KIND[e.type]}</span>
              <span>{e.title}</span>
            </button>
          </li>
        ))}
      </ul>
      {open !== null && <EvidenceViewer evidence={evidence} index={open} onSelect={setOpen} onClose={() => setOpen(null)} />}
    </div>
  )
}

function EvidenceViewer({
  evidence,
  index,
  onSelect,
  onClose,
}: {
  evidence: Evidence[]
  index: number
  onSelect: (index: number) => void
  onClose: () => void
}) {
  const close = useRef<HTMLButtonElement>(null)
  const current = evidence[index]

  // 열리면 닫기 버튼으로 포커스를 옮기고, Esc로 닫는다
  useEffect(() => {
    close.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal card evidence-modal" role="dialog" aria-modal="true" aria-label={current.title} onMouseDown={(e) => e.stopPropagation()}>
        <div className="evidence-tabs" role="tablist" aria-label="자료">
          {evidence.map((e, i) => (
            <button
              key={e.type + e.title}
              type="button"
              role="tab"
              aria-selected={i === index}
              className={i === index ? 'tab active' : 'tab'}
              onClick={() => onSelect(i)}
            >
              {e.title}
            </button>
          ))}
          <span className="spacer" />
          <button type="button" ref={close} onClick={onClose}>
            닫기
          </button>
        </div>
        <div className="doc-scroll">
          <Document evidence={current} />
        </div>
      </div>
    </div>
  )
}

function Document({ evidence }: { evidence: Evidence }) {
  return (
    <article className={`doc doc-${evidence.type}`}>
      <span className="doc-stamp">실습용</span>
      {evidence.type === 'bank' && <BankStatement evidence={evidence} />}
      {evidence.type === 'slip' && <Slip evidence={evidence} />}
      {evidence.type === 'note' && <PromissoryNote evidence={evidence} />}
    </article>
  )
}

function BankStatement({ evidence }: { evidence: BankEvidence }) {
  const { account, opening, rows } = evidence.body
  return (
    <>
      <h3>{evidence.title}</h3>
      <p className="muted">{account}</p>
      <div className="doc-table-wrap">
        <table className="doc-table">
          <thead>
            <tr>
              <th>거래일</th>
              <th>적요</th>
              <th className="money">출금</th>
              <th className="money">입금</th>
              <th className="money">잔액</th>
            </tr>
          </thead>
          <tbody>
            <tr className="muted">
              <td />
              <td>이전 잔액</td>
              <td />
              <td />
              <td className="money">{won(opening)}</td>
            </tr>
            {rows.map(([date, memo, out, into, balance]) => (
              <tr key={date + memo}>
                <td>{date}</td>
                <td>{memo}</td>
                <td className="money out">{out > 0 ? won(out) : ''}</td>
                <td className="money in">{into > 0 ? won(into) : ''}</td>
                <td className="money">{won(balance)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function Slip({ evidence }: { evidence: SlipEvidence }) {
  const { issuer, items, amount } = evidence.body
  return (
    <>
      <h3>{evidence.title}</h3>
      <p className="muted">{issuer}</p>
      <dl className="doc-items">
        {items.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <p className="doc-amount">
        <span>금액</span>
        <strong>{won(amount)}원</strong>
      </p>
    </>
  )
}

function PromissoryNote({ evidence }: { evidence: NoteEvidence }) {
  const { number, amount, issuer, payee, issueDate, dueDate, place } = evidence.body
  return (
    <>
      <p className="muted">
        {evidence.title} · 어음번호 {number}
      </p>
      <h3 className="note-heading">약 속 어 음</h3>
      <p className="note-payee">
        <strong>{payee}</strong> 귀하
      </p>
      <p className="doc-amount">
        <span>금</span>
        <strong>{won(amount)}원</strong>
      </p>
      <p>위 금액을 귀하 또는 귀하의 지시인에게 이 약속어음과 상환하여 지급하겠습니다.</p>
      <dl className="doc-items">
        <div>
          <dt>지급 기일</dt>
          <dd>{dueDate}</dd>
        </div>
        <div>
          <dt>지급 장소</dt>
          <dd>{place}</dd>
        </div>
        <div>
          <dt>발행일</dt>
          <dd>{issueDate}</dd>
        </div>
        <div>
          <dt>발행인</dt>
          <dd>{issuer}</dd>
        </div>
      </dl>
    </>
  )
}
