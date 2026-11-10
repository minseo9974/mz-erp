import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { AgGridReact } from '../lib/grid'
import type { ColDef } from '../lib/grid'

import { ApiError } from '../api/client'
import { useCreatePartner, usePartners, useUpdatePartner } from '../api/hooks'
import { PARTNER_TYPE_LABEL } from '../api/types'
import type { Partner, PartnerType } from '../api/types'
import { useCurrentCompany } from '../layout/useCurrentCompany'

const EMPTY = { code: '', name: '', bizRegNo: '', partnerType: 'CUSTOMER' as PartnerType }

export function PartnersPage() {
  const company = useCurrentCompany()
  const partners = usePartners(company.companyId)
  const create = useCreatePartner(company.companyId)
  const update = useUpdatePartner(company.companyId)
  const [form, setForm] = useState(EMPTY)
  const [q, setQ] = useState('')

  const rows = useMemo(() => {
    const keyword = q.trim().toLowerCase()
    return (partners.data ?? []).filter(
      (p) =>
        !keyword ||
        p.code.startsWith(keyword) ||
        p.name.toLowerCase().includes(keyword) ||
        (p.bizRegNo ?? '').replaceAll('-', '').includes(keyword.replaceAll('-', '')),
    )
  }, [partners.data, q])

  const columns = useMemo<ColDef<Partner>[]>(
    () => [
      { field: 'code', headerName: '코드', width: 90 },
      { field: 'name', headerName: '거래처명', width: 200, editable: true },
      { field: 'bizRegNo', headerName: '사업자등록번호', width: 150 },
      { field: 'partnerType', headerName: '구분', width: 110, valueFormatter: (p) => PARTNER_TYPE_LABEL[p.value as PartnerType] },
      { field: 'active', headerName: '사용', width: 70, cellDataType: 'boolean', editable: true },
    ],
    [],
  )

  function submit(e: FormEvent) {
    e.preventDefault()
    create.mutate(
      { code: form.code, name: form.name, bizRegNo: form.bizRegNo || undefined, partnerType: form.partnerType },
      { onSuccess: () => setForm(EMPTY) },
    )
  }

  const mutationError = create.error ?? update.error
  const error = mutationError instanceof ApiError ? mutationError.userMessage : mutationError?.message

  return (
    <>
      <div className="page-header">
        <h1>거래처</h1>
        <p className="muted">외상매출금·외상매입금처럼 거래처 필수 계정은 전표에 이 거래처가 반드시 들어갑니다.</p>
      </div>
      <form className="toolbar card" onSubmit={submit}>
        <label>
          코드 (숫자 5자리)
          <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="00101" size={8} />
        </label>
        <label>
          거래처명
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="(주)한빛전자" />
        </label>
        <label>
          사업자등록번호
          <input value={form.bizRegNo} onChange={(e) => setForm({ ...form, bizRegNo: e.target.value })} placeholder="000-00-00000" size={14} />
        </label>
        <label>
          구분
          <select value={form.partnerType} onChange={(e) => setForm({ ...form, partnerType: e.target.value as PartnerType })}>
            {Object.entries(PARTNER_TYPE_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <button className="primary" type="submit" disabled={!form.code || !form.name || create.isPending}>
          등록
        </button>
      </form>
      {error && <div className="alert" role="alert" style={{ margin: '12px 0' }}>{error}</div>}
      <div className="toolbar" style={{ marginTop: 16 }}>
        <label>
          검색 (코드·이름·사업자번호)
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="예: 한빛" />
        </label>
        <span className="muted">{rows.length}곳</span>
      </div>
      <div className="grid-box">
        <AgGridReact<Partner>
          rowData={rows}
          columnDefs={columns}
          getRowId={(p) => p.data.code}
          loading={partners.isPending}
          overlayNoRowsTemplate="등록된 거래처가 없습니다"
          singleClickEdit
          onCellValueChanged={(e) => {
            const field = e.colDef.field as 'name' | 'active'
            update.mutate({ code: e.data.code, [field]: e.newValue })
          }}
        />
      </div>
    </>
  )
}
