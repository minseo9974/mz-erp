import { Navigate, Outlet, useLocation } from 'react-router'

import { ApiError } from '../api/client'
import { useMe } from '../api/hooks'

/** 로그인하지 않았으면(/me가 401) 로그인 화면으로 보낸다. 돌아올 주소는 state로 넘긴다. */
export function RequireAuth() {
  const me = useMe()
  const location = useLocation()

  if (me.isPending) return <p className="center-page muted">불러오는 중…</p>
  if (me.error instanceof ApiError && me.error.status === 401) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (me.error) {
    return (
      <div className="center-page">
        <div className="alert">서버에 연결할 수 없습니다. {me.error.message}</div>
      </div>
    )
  }
  return <Outlet />
}
