import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '../../api/client'
import { journalKeys } from '../journal/hooks'
import type { EntryLineInput, JournalEntry } from '../journal/types'

export interface OpeningBalance {
  termNo: number
  startDate: string
  endDate: string
  /** 살아 있는(역분개되지 않은) 기초잔액 전표. 아직 입력하지 않았으면 null */
  entry: JournalEntry | null
}

const url = (companyId: number, year: number) => `/api/v1/companies/${companyId}/fiscal-years/${year}/opening-balance`

// 기초잔액도 전표다 → 전표 키 아래에 둬서 전기·삭제·역분개(전표 API) 뒤에 함께 다시 불러온다
const key = (companyId: number, year: number | null) => [...journalKeys.all(companyId), 'opening', year] as const

export function useOpeningBalance(companyId: number, year: number | null) {
  return useQuery({
    queryKey: key(companyId, year),
    queryFn: () => api<OpeningBalance>(url(companyId, year!)),
    enabled: year !== null,
  })
}

export function useSaveOpeningBalance(companyId: number, year: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (lines: EntryLineInput[]) => api<OpeningBalance>(url(companyId, year), { method: 'PUT', body: { lines } }),
    onSuccess: (saved) => {
      client.setQueryData(key(companyId, year), saved) // 다시 불러오기 전에 저장된 상태를 바로 보여 준다
      return client.invalidateQueries({ queryKey: journalKeys.all(companyId) })
    },
  })
}
