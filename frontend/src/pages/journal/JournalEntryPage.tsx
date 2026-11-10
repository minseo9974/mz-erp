import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'

import { api } from '../../api/client'
import { useAccounts, useFiscalYears, usePartners } from '../../api/hooks'
import { useCurrentCompany } from '../../layout/useCurrentCompany'
import { formatWon } from '../../lib/money'
import { AgGridReact } from '../../lib/grid'
import type { ColDef } from '../../lib/grid'
import { EntryEditor } from './EntryEditor'
import { useJournalEntries } from './hooks'
import { STATUS_LABEL } from './types'
import type { JournalEntry, Side } from './types'

interface Row {
  id: string
  entryId: number
  day: string
  entryNo: string
  side: Side
  account: string
  partner: string
  memo: string
  debit: number | null
  credit: number | null
  status: string
  posted: boolean
  reversed: boolean
  selected: boolean
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

function monthOf(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function lastDayOf(month: string) {
  return new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate()
}

/**
 * 일반전표 입력 (docs/design/screens.md).
 * 위: 그 달의 전표 라인 목록 (같은 일자·번호 = 한 전표). 행을 누르면 아래 편집기에 열린다.
 * 아래: 전표 한 장 편집기. 출금·입금·차변·대변 구분, F2 코드 도움.
 */
export function JournalEntryPage() {
  const company = useCurrentCompany()
  const companyId = company.companyId
  const [params, setParams] = useSearchParams()
  const [today] = useState(() => new Date()) // 화면을 연 날 기준 (렌더마다 시계를 읽지 않는다)
  const month = MONTH.test(params.get('month') ?? '') ? params.get('month')! : monthOf(today)
  const from = `${month}-01`
  const to = `${month}-${lastDayOf(month)}`

  const entries = useJournalEntries(companyId, from, to)
  const accounts = useAccounts(companyId)
  const partners = usePartners(companyId)
  const years = useFiscalYears(companyId)

  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [newCount, setNewCount] = useState(0) // "새 전표"를 누를 때마다 편집기를 새로 만든다
  const [notice, setNotice] = useState('')
  // 방금 저장·조회한 전표. 목록을 다시 받아 오기 전에도 편집기가 바로 그 전표를 보여 주게 한다
  const [pinned, setPinned] = useState<JournalEntry | null>(null)

  const found = entries.data?.find((e) => e.id === selectedId)
  const selected = pinned && pinned.id === selectedId && (!found || pinned.version >= found.version) ? pinned : found
  const period = years.data
    ?.flatMap((y) => y.periods)
    .find((p) => p.startDate <= from && from <= p.endDate)
  const periodClosed = period?.status === 'CLOSED'
  const noFiscalYear = years.isSuccess && !period
  const canApprove = company.role === 'APPROVER' || company.role === 'ADMIN'

  function changeMonth(next: string) {
    if (!MONTH.test(next)) return
    setParams({ month: next })
    setSelectedId(null)
    setNotice('')
  }

  /** 다른 달에 있을 수도 있는 전표(원전표·역분개 전표)를 연다 */
  async function openEntry(id: number) {
    const e = await api<JournalEntry>(`/api/v1/companies/${companyId}/journal-entries/${id}`)
    if (e.entryDate.slice(0, 7) !== month) setParams({ month: e.entryDate.slice(0, 7) })
    setPinned(e)
    setSelectedId(e.id)
    setNotice('')
  }

  const rows = useMemo<Row[]>(
    () =>
      (entries.data ?? []).flatMap((e) =>
        e.lines.map((l) => ({
          id: `${e.id}-${l.lineNo}`,
          entryId: e.id,
          day: e.entryDate.slice(8),
          entryNo: String(e.entryNo).padStart(4, '0'),
          side: l.side,
          account: `${l.accountCode} ${l.accountName}`,
          partner: l.partnerCode ? `${l.partnerCode} ${l.partnerName}` : '',
          memo: l.memo ?? e.description ?? '',
          debit: l.side === 'DEBIT' ? l.amount : null,
          credit: l.side === 'CREDIT' ? l.amount : null,
          status: (e.entryType === 'REVERSAL' ? '역분개·' : '') + STATUS_LABEL[e.status],
          posted: e.status === 'POSTED',
          reversed: e.reversedById !== null,
          selected: e.id === selectedId,
        })),
      ),
    [entries.data, selectedId],
  )

  const columns = useMemo<ColDef<Row>[]>(
    () => [
      { field: 'day', headerName: '일', width: 56 },
      { field: 'entryNo', headerName: '번호', width: 72 },
      { field: 'side', headerName: '구분', width: 68, valueFormatter: (p) => (p.value === 'DEBIT' ? '차변' : '대변') },
      { field: 'account', headerName: '계정과목', width: 150 },
      { field: 'partner', headerName: '거래처', width: 160 },
      { field: 'memo', headerName: '적요', flex: 1, minWidth: 140 },
      { field: 'debit', headerName: '차변', width: 120, cellClass: 'money', valueFormatter: (p) => formatWon(p.value) },
      { field: 'credit', headerName: '대변', width: 120, cellClass: 'money', valueFormatter: (p) => formatWon(p.value) },
      { field: 'status', headerName: '상태', width: 96 },
    ],
    [],
  )

  const monthTotals = useMemo(() => {
    const list = entries.data ?? []
    return {
      count: list.length,
      drafts: list.filter((e) => e.status === 'DRAFT').length,
      debit: list.reduce((s, e) => s + e.debitTotal, 0),
    }
  }, [entries.data])

  const editorKey = selected ? `${selected.id}-${selected.version}-${selected.status}-${selected.reversedById}` : `new-${newCount}`
  const ready = accounts.isSuccess && partners.isSuccess

  return (
    <>
      <div className="page-header">
        <h1>일반전표 입력</h1>
        <p className="muted">
          같은 일자·번호의 줄이 한 전표입니다. 저장하면 임시 전표, 전기하면 장부에 확정됩니다. 정정은 역분개로만 합니다.
        </p>
      </div>

      <div className="toolbar">
        <label>
          조회 월
          <input aria-label="조회 월" type="month" value={month} onChange={(e) => changeMonth(e.target.value)} />
        </label>
        <button
          onClick={() => {
            setSelectedId(null)
            setNewCount((n) => n + 1)
            setNotice('')
          }}
          disabled={periodClosed || noFiscalYear}
        >
          새 전표
        </button>
        <span className="muted">
          전표 {monthTotals.count}장 (임시 {monthTotals.drafts}) · 차변 합계 {formatWon(monthTotals.debit)}
        </span>
      </div>

      {noFiscalYear && (
        <div className="alert" role="alert">
          {month}을(를) 포함하는 회계연도가 없습니다. 회계연도 화면에서 먼저 만드세요.
        </div>
      )}
      {periodClosed && (
        <div className="alert" role="alert">
          마감된 기간입니다. 이 달에는 전표를 입력·전기할 수 없습니다 (정정은 열린 달에 역분개).
        </div>
      )}
      {notice && <p className="ok" role="status">{notice}</p>}

      <div className="grid-box short">
        <AgGridReact<Row>
          rowData={rows}
          columnDefs={columns}
          getRowId={(p) => p.data.id}
          loading={entries.isPending}
          overlayNoRowsTemplate="이 달에 입력된 전표가 없습니다"
          rowClassRules={{
            'row-posted': (p) => !!p.data?.posted,
            'row-reversed': (p) => !!p.data?.reversed,
            'row-selected': (p) => !!p.data?.selected,
          }}
          onRowClicked={(e) => {
            if (!e.data) return
            setSelectedId(e.data.entryId)
            setNotice('')
          }}
        />
      </div>

      {ready && !noFiscalYear && (
        <EntryEditor
          key={editorKey}
          companyId={companyId}
          month={month}
          entry={selected}
          defaultDay={month === monthOf(today) ? today.getDate() : 1}
          accounts={accounts.data}
          partners={partners.data}
          canApprove={canApprove}
          periodClosed={periodClosed}
          onSaved={(e, message) => {
            if (e.entryDate.slice(0, 7) !== month) setParams({ month: e.entryDate.slice(0, 7) })
            setPinned(e)
            setSelectedId(e.id)
            setNotice(message)
          }}
          onDeleted={() => {
            setSelectedId(null)
            setNotice('임시 전표를 삭제했습니다')
          }}
          onOpenEntry={(id) => void openEntry(id)}
        />
      )}
    </>
  )
}
