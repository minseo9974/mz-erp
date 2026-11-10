import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './client'
import type { Account, AccountCategory, FiscalYear, Me, Partner, PartnerType } from './types'

export const queryKeys = {
  me: ['me'] as const,
  accounts: (companyId: number) => ['companies', companyId, 'accounts'] as const,
  partners: (companyId: number) => ['companies', companyId, 'partners'] as const,
  fiscalYears: (companyId: number) => ['companies', companyId, 'fiscal-years'] as const,
}

const base = (companyId: number) => `/api/v1/companies/${companyId}`

// ---------- 인증

export function useMe() {
  return useQuery({ queryKey: queryKeys.me, queryFn: () => api<Me>('/api/v1/me'), retry: false })
}

export function useLogin() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { loginId: string; password: string }) =>
      api('/api/v1/auth/login', { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.me }),
  })
}

export function useLogout() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api('/api/v1/auth/logout', { method: 'POST' }),
    onSuccess: () => client.clear(),
  })
}

export function useCreateCompany() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; bizRegNo?: string; industry?: string }) =>
      api<{ companyId: number; name: string }>('/api/v1/companies', { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.me }),
  })
}

// ---------- 계정과목 (검색은 화면에서 거름: 회사당 100~200개)

export function useAccounts(companyId: number) {
  return useQuery({
    queryKey: queryKeys.accounts(companyId),
    queryFn: () => api<Account[]>(`${base(companyId)}/accounts`),
  })
}

export function useUpdateAccount(companyId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ code, ...body }: { code: string; name?: string; active?: boolean; requiresPartner?: boolean }) =>
      api<Account>(`${base(companyId)}/accounts/${code}`, { method: 'PATCH', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.accounts(companyId) }),
  })
}

export type AccountFilter = { q: string; category: AccountCategory | '' }

export function filterAccounts(accounts: Account[], { q, category }: AccountFilter): Account[] {
  const keyword = q.trim().toLowerCase()
  return accounts.filter(
    (a) =>
      (!keyword || a.code.startsWith(keyword) || a.name.toLowerCase().includes(keyword)) &&
      (!category || a.category === category),
  )
}

// ---------- 거래처

export function usePartners(companyId: number) {
  return useQuery({
    queryKey: queryKeys.partners(companyId),
    queryFn: () => api<Partner[]>(`${base(companyId)}/partners`),
  })
}

export function useCreatePartner(companyId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { code: string; name: string; bizRegNo?: string; partnerType: PartnerType }) =>
      api<Partner>(`${base(companyId)}/partners`, { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.partners(companyId) }),
  })
}

export function useUpdatePartner(companyId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ code, ...body }: { code: string; name?: string; active?: boolean }) =>
      api<Partner>(`${base(companyId)}/partners/${code}`, { method: 'PATCH', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.partners(companyId) }),
  })
}

// ---------- 회계연도

export function useFiscalYears(companyId: number) {
  return useQuery({
    queryKey: queryKeys.fiscalYears(companyId),
    queryFn: () => api<FiscalYear[]>(`${base(companyId)}/fiscal-years`),
  })
}

export function useCreateFiscalYear(companyId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { year: number; startMonth?: number; termNo?: number }) =>
      api<FiscalYear>(`${base(companyId)}/fiscal-years`, { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.fiscalYears(companyId) }),
  })
}
