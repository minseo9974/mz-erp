import { createBrowserRouter, Navigate } from 'react-router'

import { AppLayout } from './layout/AppLayout'
import { RequireAuth } from './layout/RequireAuth'
import { CompanySelectPage } from './pages/CompanySelectPage'
import { FiscalYearsPage } from './pages/FiscalYearsPage'
import { DeskPage } from './pages/learning/DeskPage'
import { LoginPage } from './pages/LoginPage'

// 메뉴 = URL (docs/design/screens.md): 새로고침·북마크해도 같은 화면
// AG Grid(약 1MB)를 쓰는 화면은 들어갈 때 불러온다 → 로그인·회사 선택은 가볍게 뜬다
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      // 첫 화면은 실습의 '내 책상'. 미션 없이 ERP만 쓰는 자유 모드는 /companies
      { path: '/', element: <Navigate to="/desk" replace /> },
      { path: '/desk', element: <DeskPage /> },
      { path: '/companies', element: <CompanySelectPage /> },
      {
        path: '/companies/:companyId',
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="accounts" replace /> },
          { path: 'accounts', lazy: async () => ({ Component: (await import('./pages/AccountsPage')).AccountsPage }) },
          { path: 'partners', lazy: async () => ({ Component: (await import('./pages/PartnersPage')).PartnersPage }) },
          { path: 'fiscal-years', element: <FiscalYearsPage /> },
          {
            path: 'opening-balance',
            lazy: async () => ({ Component: (await import('./pages/opening/OpeningBalancePage')).OpeningBalancePage }),
          },
          {
            path: 'journal-entries',
            lazy: async () => ({ Component: (await import('./pages/journal/JournalEntryPage')).JournalEntryPage }),
          },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
