import { describe, expect, it, vi } from 'vitest'

import { api, ApiError } from './client'
import { filterAccounts } from './hooks'
import type { Account } from './types'

function mockFetch(...responses: Response[]) {
  const fn = vi.fn<typeof fetch>()
  responses.forEach((r) => fn.mockResolvedValueOnce(r))
  vi.stubGlobal('fetch', fn)
  return fn
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('api()', () => {
  it('GET에는 CSRF 헤더를 붙이지 않는다', async () => {
    const fetchMock = mockFetch(json(200, { ok: true }))
    await api('/api/v1/me')
    const headers = fetchMock.mock.calls[0][1]!.headers as Record<string, string>
    expect(headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('POST에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 붙인다', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/'
    const fetchMock = mockFetch(json(201, { companyId: 1 }))
    await api('/api/v1/companies', { method: 'POST', body: { name: 'A' } })
    const init = fetchMock.mock.calls[0][1]!
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('token-123')
    expect(init.body).toBe('{"name":"A"}')
    expect(init.credentials).toBe('same-origin')
  })

  it('쿠키가 없으면 /csrf로 먼저 발급받는다', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementationOnce(async () => {
      document.cookie = 'XSRF-TOKEN=fresh; path=/' // 서버가 쿠키를 내려준 것처럼
      return json(200, {})
    })
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/v1/auth/logout', { method: 'POST' })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/auth/csrf')
    expect((fetchMock.mock.calls[1][1]!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('fresh')
  })

  it('Problem Details 오류를 ApiError로 바꾼다', async () => {
    mockFetch(
      json(409, { code: 'DUPLICATE_CODE', title: '이미 사용 중인 코드입니다', detail: '거래처코드 00101은(는) 이미 있습니다' }),
    )
    const error = await api('/api/v1/x').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('DUPLICATE_CODE')
    expect((error as ApiError).userMessage).toBe('이미 사용 중인 코드입니다 — 거래처코드 00101은(는) 이미 있습니다')
  })

  it('본문 없는 401도 ApiError(UNAUTHENTICATED)', async () => {
    mockFetch(new Response('', { status: 401 }))
    const error = (await api('/api/v1/me').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(401)
    expect(error.code).toBe('UNAUTHENTICATED')
  })
})

describe('filterAccounts', () => {
  const account = (code: string, name: string, category: Account['category']): Account => ({
    code, name, category, subgroup: '', normalBalance: 'DEBIT', contraOfCode: null,
    requiresPartner: false, statement: 'BS', system: false, active: true,
  })
  const all = [account('108', '외상매출금', 'ASSET'), account('251', '외상매입금', 'LIABILITY'), account('101', '현금', 'ASSET')]

  it('코드 앞자리 또는 이름 일부로 찾는다 (F2 코드 도움과 같은 규칙)', () => {
    expect(filterAccounts(all, { q: '외상', category: '' }).map((a) => a.code)).toEqual(['108', '251'])
    expect(filterAccounts(all, { q: '10', category: '' }).map((a) => a.code)).toEqual(['108', '101'])
  })

  it('대분류로 거른다', () => {
    expect(filterAccounts(all, { q: '외상', category: 'ASSET' }).map((a) => a.code)).toEqual(['108'])
  })
})
