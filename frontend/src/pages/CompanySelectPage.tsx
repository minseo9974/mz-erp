import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'

import { ApiError } from '../api/client'
import { useCreateCompany, useLogout, useMe } from '../api/hooks'
import { ROLE_LABEL } from '../api/types'

export function CompanySelectPage() {
  const me = useMe()
  const create = useCreateCompany()
  const logout = useLogout()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [bizRegNo, setBizRegNo] = useState('')

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate(
      { name, bizRegNo: bizRegNo || undefined },
      { onSuccess: (c) => navigate(`/companies/${c.companyId}/accounts`) },
    )
  }

  const companies = me.data?.companies ?? []
  const error = create.error instanceof ApiError ? create.error.userMessage : create.error?.message

  return (
    <div className="center-page">
      <div className="card">
        <div>
          <h1>회사 선택</h1>
          <p className="muted">{me.data?.name}님이 접근할 수 있는 회사</p>
        </div>
        {companies.length === 0 ? (
          <p className="muted">아직 회사가 없습니다. 아래에서 만들어 보세요.</p>
        ) : (
          <ul className="company-list">
            {companies.map((c) => (
              <li key={c.companyId}>
                <Link to={`/companies/${c.companyId}`}>
                  <strong>{c.name}</strong>
                  <span className="badge">{ROLE_LABEL[c.role]}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        <form onSubmit={submit} style={{ display: 'grid', gap: 10 }}>
          <h2 style={{ margin: '8px 0 0' }}>새 회사 만들기</h2>
          <p className="muted" style={{ margin: 0 }}>표준 계정과목표(94개)가 함께 만들어지고, 만든 사람이 관리자가 됩니다.</p>
          <label>
            회사명
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="(주)엠제트전자" />
          </label>
          <label>
            사업자등록번호 (선택)
            <input value={bizRegNo} onChange={(e) => setBizRegNo(e.target.value)} placeholder="000-00-00000" />
          </label>
          {error && <div className="alert" role="alert">{error}</div>}
          <button className="primary" type="submit" disabled={!name.trim() || create.isPending}>
            회사 만들기
          </button>
        </form>
        <button onClick={() => logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
          로그아웃
        </button>
      </div>
    </div>
  )
}
