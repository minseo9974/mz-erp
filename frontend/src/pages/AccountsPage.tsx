import { useMemo, useState } from 'react'
import { AgGridReact } from '../lib/grid'
import type { ColDef } from '../lib/grid'

import { filterAccounts, useAccounts, useUpdateAccount } from '../api/hooks'
import { CATEGORY_LABEL, STATEMENT_LABEL } from '../api/types'
import type { Account, AccountCategory } from '../api/types'
import { useCurrentCompany } from '../layout/useCurrentCompany'
import { ApiError } from '../api/client'

export function AccountsPage() {
  const company = useCurrentCompany()
  const accounts = useAccounts(company.companyId)
  const update = useUpdateAccount(company.companyId)
  const [q, setQ] = useState('')
  const [category, setCategory] = useState<AccountCategory | ''>('')
  const isAdmin = company.role === 'ADMIN'

  const rows = useMemo(() => filterAccounts(accounts.data ?? [], { q, category }), [accounts.data, q, category])

  const columns = useMemo<ColDef<Account>[]>(
    () => [
      { field: 'code', headerName: '코드', width: 80, pinned: 'left' },
      { field: 'name', headerName: '계정과목', width: 160, editable: (p) => isAdmin && !p.data?.system },
      { field: 'category', headerName: '대분류', width: 80, valueFormatter: (p) => CATEGORY_LABEL[p.value as AccountCategory] },
      { field: 'subgroup', headerName: '중분류', width: 120 },
      {
        field: 'normalBalance',
        headerName: '정상잔액',
        width: 90,
        valueFormatter: (p) => (p.value === 'DEBIT' ? '차변' : '대변'),
        cellStyle: (p) => (p.data?.contraOfCode ? { color: 'var(--danger)', fontWeight: 600 } : null),
      },
      { field: 'contraOfCode', headerName: '차감 대상', width: 90 },
      { field: 'requiresPartner', headerName: '거래처 필수', width: 100, cellDataType: 'boolean', editable: isAdmin },
      { field: 'statement', headerName: '재무제표', width: 120, valueFormatter: (p) => STATEMENT_LABEL[p.value as keyof typeof STATEMENT_LABEL] },
      { field: 'active', headerName: '사용', width: 70, cellDataType: 'boolean', editable: (p) => isAdmin && !p.data?.system },
    ],
    [isAdmin],
  )

  const error = update.error instanceof ApiError ? update.error.userMessage : update.error?.message

  return (
    <>
      <div className="page-header">
        <h1>계정과목</h1>
        <p className="muted">
          정상잔액이 <span style={{ color: 'var(--danger)', fontWeight: 600 }}>빨간색</span>인 계정은 차감계정입니다
          (자산이지만 대변 잔액 — 회계 노트 01). {isAdmin ? '이름·거래처 필수·사용 여부는 셀을 눌러 고칠 수 있습니다.' : ''}
        </p>
      </div>
      <div className="toolbar">
        <label>
          검색 (코드 앞자리·이름)
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="예: 108, 외상" />
        </label>
        <label>
          대분류
          <select value={category} onChange={(e) => setCategory(e.target.value as AccountCategory | '')}>
            <option value="">전체</option>
            {Object.entries(CATEGORY_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <span className="muted">{rows.length}개</span>
      </div>
      {error && <div className="alert" role="alert" style={{ marginBottom: 12 }}>{error}</div>}
      {accounts.error && <div className="alert">{accounts.error.message}</div>}
      <div className="grid-box">
        <AgGridReact<Account>
          rowData={rows}
          columnDefs={columns}
          getRowId={(p) => p.data.code}
          loading={accounts.isPending}
          overlayNoRowsTemplate="조건에 맞는 계정이 없습니다"
          singleClickEdit
          onCellValueChanged={(e) => {
            const field = e.colDef.field as 'name' | 'requiresPartner' | 'active'
            update.mutate({ code: e.data.code, [field]: e.newValue })
          }}
        />
      </div>
    </>
  )
}
