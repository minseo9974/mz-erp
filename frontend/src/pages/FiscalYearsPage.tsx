import { useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../api/client'
import { useCreateFiscalYear, useFiscalYears } from '../api/hooks'
import { useCurrentCompany } from '../layout/useCurrentCompany'

export function FiscalYearsPage() {
  const company = useCurrentCompany()
  const years = useFiscalYears(company.companyId)
  const create = useCreateFiscalYear(company.companyId)
  const [year, setYear] = useState(() => String(new Date().getFullYear()))
  const [termNo, setTermNo] = useState('')
  const isAdmin = company.role === 'ADMIN'

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate({ year: Number(year), termNo: termNo ? Number(termNo) : undefined })
  }

  const error = create.error instanceof ApiError ? create.error.userMessage : create.error?.message

  return (
    <>
      <div className="page-header">
        <h1>회계연도</h1>
        <p className="muted">회계연도를 만들면 12개월 회계기간이 함께 생깁니다. 마감은 월 단위입니다 (5단계).</p>
      </div>
      {isAdmin && (
        <form className="toolbar card" onSubmit={submit}>
          <label>
            연도
            <input type="number" value={year} onChange={(e) => setYear(e.target.value)} min={2000} max={2100} />
          </label>
          <label>
            기수 (비우면 직전 + 1)
            <input type="number" value={termNo} onChange={(e) => setTermNo(e.target.value)} min={1} placeholder="예: 6" />
          </label>
          <button className="primary" type="submit" disabled={!year || create.isPending}>
            회계연도 만들기
          </button>
        </form>
      )}
      {error && <div className="alert" role="alert" style={{ margin: '12px 0' }}>{error}</div>}
      <div style={{ display: 'grid', gap: 12, marginTop: 16 }}>
        {years.isPending && <p className="muted">불러오는 중…</p>}
        {years.data?.length === 0 && <p className="muted">아직 회계연도가 없습니다.</p>}
        {years.data
          ?.slice()
          .reverse()
          .map((y) => (
            <section key={y.termNo} className="card">
              <h2>
                제{y.termNo}기 <span className="muted">{y.startDate} ~ {y.endDate}</span>{' '}
                <span className={y.status === 'OPEN' ? 'badge' : 'badge closed'}>{y.status === 'OPEN' ? '진행' : '마감'}</span>
              </h2>
              <div className="periods">
                {y.periods.map((p) => (
                  <div key={p.periodNo} className="period">
                    <strong>{p.periodNo}월</strong>{' '}
                    <span className={p.status === 'OPEN' ? 'muted' : 'badge closed'}>{p.status === 'OPEN' ? '열림' : '마감'}</span>
                    <div className="muted">
                      {p.startDate.slice(5)} ~ {p.endDate.slice(5)}
                    </div>
                  </div>
                ))}
              </div>
            </section>
          ))}
      </div>
    </>
  )
}
