import { NavLink, Navigate, Outlet, useNavigate, useParams } from 'react-router'

import { useLogout, useMe } from '../api/hooks'
import { ROLE_LABEL } from '../api/types'

const MENU = [
  { group: '기초정보', items: [
    { to: 'accounts', label: '계정과목' },
    { to: 'partners', label: '거래처' },
    { to: 'fiscal-years', label: '회계연도' },
  ] },
]

// 다음 단계에서 열릴 메뉴 (화면 설계: docs/design/screens.md)
const COMING = ['전표입력 (2단계)', '장부조회 (3단계)', '부가가치세 (4단계)', '결산/재무제표 (5단계)']

export function AppLayout() {
  const { companyId } = useParams()
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()

  const company = me.data?.companies.find((c) => String(c.companyId) === companyId)
  // 권한이 없는 회사 주소로 들어오면 회사 선택으로
  if (me.data && !company) return <Navigate to="/companies" replace />
  if (!company) return null

  return (
    <div className="app">
      <header className="app-header">
        <span className="logo">MZ-ERP</span>
        <select
          aria-label="회사 선택"
          value={company.companyId}
          onChange={(e) => navigate(`/companies/${e.target.value}`)}
        >
          {me.data!.companies.map((c) => (
            <option key={c.companyId} value={c.companyId}>
              {c.name}
            </option>
          ))}
        </select>
        <span className="badge">{ROLE_LABEL[company.role]}</span>
        <span className="spacer" />
        <span className="muted user-name">{me.data!.name}</span>
        <button
          onClick={() => logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}
          disabled={logout.isPending}
        >
          로그아웃
        </button>
      </header>
      <nav className="app-sidebar" aria-label="메뉴">
        {MENU.map((g) => (
          <div key={g.group}>
            <div className="group">{g.group}</div>
            {g.items.map((item) => (
              <NavLink key={item.to} to={item.to}>
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}
        <div className="group">준비 중</div>
        {COMING.map((label) => (
          <span key={label} className="soon">
            {label}
          </span>
        ))}
      </nav>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}
