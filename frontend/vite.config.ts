/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 서버는 /api 등을 로컬 백엔드(./gradlew bootTestRun, 8080)로 넘긴다.
// 같은 출처(origin)처럼 보이므로 세션 쿠키·CSRF 쿠키가 운영(Caddy 뒤)과 똑같이 동작한다.
const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/actuator': backend,
      '/v3/api-docs': backend,
      '/swagger-ui': backend,
    },
  },
  build: {
    // AG Grid 청크(약 840KB, gzip 240KB)가 기본 경고(500KB)를 넘는다.
    // 그리드 화면에 들어갈 때만 불러오므로(src/lib/grid.ts, 라우터 lazy) 허용한다.
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
