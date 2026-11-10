import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'

import { ApiError, ensureCsrfToken } from './api/client'
import { router } from './router'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 401·403·404는 다시 시도해도 같으므로 재시도하지 않는다
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      staleTime: 30_000,
    },
  },
})

// 첫 화면이 뜨는 동안 CSRF 쿠키를 미리 받아 둔다 (실패해도 요청 직전에 다시 시도)
void ensureCsrfToken().catch(() => undefined)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
