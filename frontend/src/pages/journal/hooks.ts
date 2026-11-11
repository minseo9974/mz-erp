import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '../../api/client'
import type { EntryInput, JournalEntry } from './types'

const base = (companyId: number) => `/api/v1/companies/${companyId}/journal-entries`

/** 전표 쿼리 키. 저장·전기·역분개 후 이 접두사로 한꺼번에 무효화한다 (3단계부터 장부·시산표도 함께). */
export const journalKeys = {
  all: (companyId: number) => ['companies', companyId, 'journal-entries'] as const,
  range: (companyId: number, from: string, to: string) => ['companies', companyId, 'journal-entries', from, to] as const,
}

export function useJournalEntries(companyId: number, from: string, to: string) {
  return useQuery({
    queryKey: journalKeys.range(companyId, from, to),
    queryFn: () => api<JournalEntry[]>(`${base(companyId)}?from=${from}&to=${to}`),
    // 기초잔액 전표는 전기분 재무상태표 화면에서 다룬다 (일반전표 화면에서 고치면 재무상태표 계정 규칙을 건너뛴다)
    select: (list) => list.filter((e) => e.entryType !== 'OPENING'),
  })
}

function useJournalMutation<V>(companyId: number, fn: (vars: V) => Promise<JournalEntry | JournalEntry[] | void>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => client.invalidateQueries({ queryKey: journalKeys.all(companyId) }),
  })
}

export function useCreateEntry(companyId: number) {
  return useJournalMutation(companyId, (body: EntryInput) =>
    api<JournalEntry>(base(companyId), { method: 'POST', body }),
  )
}

export function useUpdateEntry(companyId: number) {
  return useJournalMutation(companyId, ({ id, ...body }: EntryInput & { id: number; version: number }) =>
    api<JournalEntry>(`${base(companyId)}/${id}`, { method: 'PUT', body }),
  )
}

export function useDeleteEntry(companyId: number) {
  return useJournalMutation(companyId, (id: number) => api<void>(`${base(companyId)}/${id}`, { method: 'DELETE' }))
}

export function usePostEntry(companyId: number) {
  return useJournalMutation(companyId, (id: number) =>
    api<JournalEntry>(`${base(companyId)}/${id}/post`, { method: 'POST' }),
  )
}

export function useReverseEntry(companyId: number) {
  return useJournalMutation(companyId, ({ id, ...body }: { id: number; reversalDate: string; reason: string }) =>
    api<JournalEntry>(`${base(companyId)}/${id}/reverse`, { method: 'POST', body }),
  )
}
