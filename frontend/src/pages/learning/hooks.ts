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
  /** 본 시도의 상태 */
  status: MissionStatus
  attemptId: number | null
  /** 끝낸 미션을 지금 다시 풀고 있으면 그 연습 시도 */
  practiceAttemptId: number | null
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

/** 증빙: 실습자가 전표를 쓰기 전에 보는 자료. 종류마다 내용의 모양이 다르다 (data/course의 evidence) */
export interface BankEvidence {
  type: 'bank'
  title: string
  /** rows: [거래일, 적요, 출금, 입금, 잔액] */
  body: { account: string; opening: number; rows: [string, string, number, number, number][] }
}

export interface SlipEvidence {
  type: 'slip'
  title: string
  /** items: [항목, 내용] */
  body: { issuer: string; items: [string, string][]; amount: number }
}

export interface NoteEvidence {
  type: 'note'
  title: string
  body: { number: string; amount: number; issuer: string; payee: string; issueDate: string; dueDate: string; place: string }
}

export type Evidence = BankEvidence | SlipEvidence | NoteEvidence

export interface Attempt {
  id: number
  missionKey: string
  title: string
  brief: string
  date: string
  status: Exclude<MissionStatus, 'NOT_STARTED'>
  /** 끝낸 미션을 다시 푸는 연습: 채점만 하고 장부에는 반영하지 않는다 */
  practice: boolean
  evidence: Evidence[]
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

export interface AnswerLine {
  side: 'DEBIT' | 'CREDIT'
  accountCode: string
  accountName: string
  partnerName: string | null
  amount: number
}

/** 끝낸 미션 다시 보기 */
export interface MissionReview {
  key: string
  title: string
  chapterTitle: string
  brief: string
  date: string
  status: 'PASSED' | 'REVEALED'
  finishedAt: string
  evidence: Evidence[]
  answer: AnswerLine[]
  explanation: string
  hints: string[]
  hintsUsed: number
  submissions: number
  /** 본 시도에서 반려될 때마다 받은 사유 */
  rejections: string[][]
  practices: number
  practiceAttemptId: number | null
}

export const COURSE_KEY = 'accounting-basics'

const base = '/api/v1/learning'

export const learningKeys = {
  enrollments: ['learning', 'enrollments'] as const,
  attempt: (id: number) => ['learning', 'attempts', id] as const,
  reviews: ['learning', 'review'] as const,
  review: (enrollmentId: number, missionKey: string) => ['learning', 'review', enrollmentId, missionKey] as const,
}

export const allMissions = (e: Enrollment) => e.chapters.flatMap((c) => c.missions)

export const isFinished = (m: MissionSummary) => m.status === 'PASSED' || m.status === 'REVEALED'

/** 지금 풀고 있는 시도: 다시 푸는 연습이 있으면 그것, 아니면 진행 중인 본 시도 */
export const activeAttemptId = (m: MissionSummary) =>
  m.practiceAttemptId ?? (m.status === 'IN_PROGRESS' ? m.attemptId : null)

/** 지금 풀고 있는 미션 (한 번에 하나) */
export const activeMission = (e: Enrollment) => allMissions(e).find((m) => activeAttemptId(m) !== null)

/** 지금 할 업무: 풀고 있는 미션, 없으면 아직 시작하지 않은 첫 미션 */
export function nextMission(e: Enrollment): MissionSummary | undefined {
  return activeMission(e) ?? allMissions(e).find((m) => m.status === 'NOT_STARTED')
}

/** 미션의 거래일이 속한 달의 일반전표 화면 */
export const missionPath = (companyId: number, date: string) =>
  `/companies/${companyId}/journal-entries?month=${date.slice(0, 7)}`

export const reviewPath = (missionKey: string) => `/desk/missions/${missionKey}`

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

/** 끝낸 미션 다시 보기: 정답 분개, 해설, 내 기록 */
export function useMissionReview(enrollmentId: number | undefined, missionKey: string) {
  return useQuery({
    queryKey: learningKeys.review(enrollmentId ?? 0, missionKey),
    queryFn: () => api<MissionReview>(`${base}/enrollments/${enrollmentId}/missions/${missionKey}/review`),
    enabled: enrollmentId !== undefined,
    retry: false,
  })
}

/** 끝낸 미션 다시 풀기 (연습) */
export function useRetryMission(enrollmentId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (missionKey: string) =>
      api<Attempt>(`${base}/enrollments/${enrollmentId}/missions/${missionKey}/retry`, { method: 'POST' }),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: learningKeys.enrollments }),
        client.invalidateQueries({ queryKey: learningKeys.reviews }),
      ]),
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
      client.invalidateQueries({ queryKey: learningKeys.reviews }),
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
