import { useState } from 'react'
import type { FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'

import { ApiError } from '../api/client'
import { useLogin } from '../api/hooks'

export function LoginPage() {
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/companies'

  function submit(e: FormEvent) {
    e.preventDefault()
    login.mutate({ loginId, password }, { onSuccess: () => navigate(from, { replace: true }) })
  }

  const error = login.error instanceof ApiError ? login.error.userMessage : login.error?.message

  return (
    <div className="center-page">
      <form className="card" onSubmit={submit}>
        <div>
          <h1>MZ-ERP</h1>
          <p className="muted">회계 흐름 전체를 직접 구현하는 회계 ERP</p>
        </div>
        <label>
          아이디
          <input value={loginId} onChange={(e) => setLoginId(e.target.value)} autoComplete="username" autoFocus />
        </label>
        <label>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        {error && <div className="alert" role="alert">{error}</div>}
        <button className="primary" type="submit" disabled={!loginId || !password || login.isPending}>
          {login.isPending ? '로그인 중…' : '로그인'}
        </button>
      </form>
    </div>
  )
}
