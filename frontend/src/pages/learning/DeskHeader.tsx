import { Link, useNavigate } from 'react-router'

import { useLogout, useMe } from '../../api/hooks'

/** 실습 화면(내 책상, 미션 다시 보기)의 머리줄 */
export function DeskHeader() {
  const me = useMe()
  const logout = useLogout()
  const navigate = useNavigate()

  return (
    <header className="desk-header">
      <Link to="/desk" className="logo">
        MZ-ERP 실습
      </Link>
      <span className="spacer" />
      <span className="muted user-name">{me.data?.name}</span>
      <Link to="/companies">자유 모드</Link>
      <button onClick={() => logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}>
        로그아웃
      </button>
    </header>
  )
}
