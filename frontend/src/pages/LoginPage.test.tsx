import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'

import { LoginPage } from './LoginPage'

function renderLogin() {
  const router = createMemoryRouter(
    [
      { path: '/login', element: <LoginPage /> },
      { path: '/desk', element: <p>내 책상 화면</p> },
    ],
    { initialEntries: ['/login'] },
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  it('아이디·비밀번호를 넣기 전에는 로그인 버튼이 비활성', () => {
    renderLogin()
    expect(screen.getByRole('button', { name: '로그인' })).toBeDisabled()
  })

  it('로그인 실패 메시지를 보여준다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ code: 'LOGIN_FAILED', title: '아이디 또는 비밀번호가 올바르지 않습니다' }), { status: 401 }),
      ),
    )
    renderLogin()
    await userEvent.type(screen.getByLabelText('아이디'), 'admin')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('아이디 또는 비밀번호가 올바르지 않습니다')
  })

  it('로그인에 성공하면 내 책상으로 간다', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/'
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ userId: 1, loginId: 'admin', name: '관리자' }), { status: 200 })),
    )
    renderLogin()
    await userEvent.type(screen.getByLabelText('아이디'), 'admin')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'admin1234')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByText('내 책상 화면')).toBeInTheDocument()
  })
})
