/**
 * 백엔드 API 호출 공통 함수.
 * - 세션 쿠키로 인증 (같은 출처라 credentials: 'same-origin')
 * - 상태를 바꾸는 요청에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 붙인다 (ADR-0008)
 * - 오류 응답(RFC 7807 Problem Details)은 ApiError로 바꿔 던진다
 */

export interface FieldError {
  field: string
  message: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly title: string
  readonly detail?: string
  readonly errors: FieldError[]

  constructor(status: number, body: Partial<{ code: string; title: string; detail: string; errors: FieldError[] }>) {
    super(body.detail ?? body.title ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code ?? (status === 401 ? 'UNAUTHENTICATED' : 'HTTP_' + status)
    this.title = body.title ?? (status === 401 ? '로그인이 필요합니다' : '요청을 처리하지 못했습니다')
    this.detail = body.detail
    this.errors = body.errors ?? []
  }

  /** 화면에 보여줄 한 줄 메시지 */
  get userMessage(): string {
    const fields = this.errors.map((e) => `${e.field}: ${e.message}`).join(', ')
    return [this.title, this.detail, fields].filter(Boolean).join(' — ')
  }
}

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith(name + '='))
    ?.slice(name.length + 1)
}

/** XSRF-TOKEN 쿠키가 없으면 발급받는다. 앱 시작과 상태 변경 요청 직전에 호출. */
export async function ensureCsrfToken(): Promise<string> {
  const existing = readCookie('XSRF-TOKEN')
  if (existing) return decodeURIComponent(existing)
  await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
  const issued = readCookie('XSRF-TOKEN')
  if (!issued) throw new ApiError(500, { title: 'CSRF 토큰을 받지 못했습니다' })
  return decodeURIComponent(issued)
}

export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (UNSAFE_METHODS.has(method)) headers['X-XSRF-TOKEN'] = await ensureCsrfToken()

  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })

  if (response.status === 204) return undefined as T
  const text = await response.text()
  const data = text ? JSON.parse(text) : undefined
  if (!response.ok) throw new ApiError(response.status, data ?? {})
  return data as T
}
