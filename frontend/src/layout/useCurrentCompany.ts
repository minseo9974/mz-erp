import { useParams } from 'react-router'

import { useMe } from '../api/hooks'
import type { MyCompany } from '../api/types'

/** 회사 화면(AppLayout 아래)에서 현재 회사·역할을 꺼내 쓴다. AppLayout이 존재를 보장한다. */
export function useCurrentCompany(): MyCompany {
  const { companyId } = useParams()
  const me = useMe()
  const company = me.data?.companies.find((c) => String(c.companyId) === companyId)
  if (!company) throw new Error('회사 정보를 찾을 수 없습니다')
  return company
}
