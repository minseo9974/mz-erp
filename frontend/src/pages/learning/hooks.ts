import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '../../api/client'
import { queryKeys } from '../../api/hooks'
import { journalKeys } from '../journal/hooks'

// 실습 API (docs/design/api.md "실습"). 전표를 쓰고 고치는 일은 여기가 아니라 일반전표 API로 한다.

export type MissionStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PASSED' | 'REVEALED'

export interface MissionSummary {
  key: string
  title: string
  brief: string
  date: string
  status: MissionStatus
  attemptId: number | null
}

export interface Chapter {
  key: string
  title: string
  missions: MissionSummary[]
}

export interface Enrollment {
  id: number
  companyId: number
  courseKey: string
  courseTitle: string
  chapters: Chapter[]
}

export interface Attempt {
  id: number
  missionKey: string
  title: string
  brief: string
  date: string
  status: Exclude<MissionStatus, 'NOT_STARTED'>
  hints: string[]
  hintsRemaining: number
  submissions: number
}

export interface Finding {
  type: string
  message: string
}

export interface SubmitResult {
  passed: boolean
  findings: Finding[]
  attempt: Attempt
}

export const COURSE_KEY = 'accounting-basics'

const base = '/api/v1/learning'

export const learningKeys = {
  enrollments: ['learning', 'enrollments'] as const,
  attempt: (id: number) => ['learning', 'attempts', id] as const,
}

export const allMissions = (e: Enrollment) => e.chapters.flatMap((c) => c.missions)

/** 지금 할 업무: 진행 중인 미션, 없으면 아직 시작하지 않은 첫 미션 */
export function nextMission(e: Enrollment): MissionSummary | undefined {
  const missions = allMissions(e)
  return missions.find((m) => m.status === 'IN_PROGRESS') ?? missions.find((m) => m.status === 'NOT_STARTED')
}

/** 미션의 거래일이 속한 달의 일반전표 화면 */
export const missionPath = (companyId: number, date: string) =>
  `/companies/${companyId}/journal-entries?month=${date.slice(0, 7)}`

export function useMyEnrollments() {
  return useQuery({ queryKey: learningKeys.enrollments, queryFn: () => api<Enrollment[]>(`${base}/enrollments`) })
}

/** 과정 시작 = 실습 회사 생성. 접근할 수 있는 회사가 늘어나므로 /me도 다시 불러온다 */
export function useEnroll() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => api<Enrollment>(`${base}/enrollments`, { method: 'POST', body: { courseKey: COURSE_KEY } }),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: learningKeys.enrollments }),
        client.invalidateQueries({ queryKey: queryKeys.me }),
      ]),
  })
}

export function useStartMission(enrollmentId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (missionKey: string) =>
      api<Attempt>(`${base}/enrollments/${enrollmentId}/missions/${missionKey}/start`, { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: learningKeys.enrollments }),
  })
}

export function useAttempt(attemptId: number | null) {
  return useQuery({
    queryKey: learningKeys.attempt(attemptId ?? 0),
    queryFn: () => api<Attempt>(`${base}/attempts/${attemptId}`),
    enabled: attemptId !== null,
  })
}

/**
 * 결재 요청 · 힌트 · 정답 보고 넘어가기.
 * 결재가 나거나 정답 전표가 들어가면 실습 회사의 전표 목록이 바뀌므로 전표 쿼리도 함께 다시 불러온다.
 */
export function useAttemptActions(attemptId: number, companyId: number) {
  const client = useQueryClient()
  const refresh = () =>
    Promise.all([
      client.invalidateQueries({ queryKey: learningKeys.enrollments }),
      client.invalidateQueries({ queryKey: learningKeys.attempt(attemptId) }),
      client.invalidateQueries({ queryKey: journalKeys.all(companyId) }),
    ])
  const submit = useMutation({
    mutationFn: () => api<SubmitResult>(`${base}/attempts/${attemptId}/submit`, { method: 'POST' }),
    onSuccess: refresh,
  })
  const hint = useMutation({
    mutationFn: () => api<Attempt>(`${base}/attempts/${attemptId}/hint`, { method: 'POST' }),
    onSuccess: (attempt) => client.setQueryData(learningKeys.attempt(attemptId), attempt),
  })
  const reveal = useMutation({
    mutationFn: () => api<Attempt>(`${base}/attempts/${attemptId}/reveal`, { method: 'POST' }),
    onSuccess: refresh,
  })
  return { submit, hint, reveal }
}
