import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Link } from 'react-router'

import { ApiError } from '../../api/client'
import { useAccounts, useFiscalYears, usePartners } from '../../api/hooks'
import type { Account, Partner } from '../../api/types'
import { useCurrentCompany } from '../../layout/useCurrentCompany'
import { formatWon, parseWon } from '../../lib/money'
import { CodeHelp } from '../journal/CodeHelp'
import type { CodeItem } from '../journal/CodeHelp'
import { useDeleteEntry, usePostEntry, useReverseEntry } from '../journal/hooks'
import { STATUS_LABEL } from '../journal/types'
import type { JournalEntry } from '../journal/types'
import { useOpeningBalance, useSaveOpeningBalance } from './hooks'
import { emptyRow, fromEntryLines, toApiLines, totals, validate } from './openingForm'
import type { OpeningRow } from './openingForm'

/**
 * 전기분 재무상태표 (docs/design/screens.md, 회계 노트 03).
 * 프로그램을 처음 쓰는 해의 시작 잔액을 입력한다. 저장하면 회계연도 첫날의 기초잔액 전표(임시)가 되고,
 * 전기해야 장부에 반영된다. 전기한 뒤에는 역분개하고 다시 입력해서 고친다.
 */
export function OpeningBalancePage() {
  const company = useCurrentCompany()
  const companyId = company.companyId
  const years = useFiscalYears(companyId)
  const accounts = useAccounts(companyId)
  const partners = usePartners(companyId)
  const first = years.data?.[0] // 시작일 순 → 첫 회계연도
  const year = first ? Number(first.startDate.slice(0, 4)) : null
  const opening = useOpeningBalance(companyId, year)
  const [notice, setNotice] = useState('')
  // 역분개한 직후에는 살아 있는 전표가 없어진다. 방금까지의 줄을 넘겨받아 처음부터 다시 치지 않게 한다
  const [carried, setCarried] = useState<OpeningRow[] | null>(null)

  const entry = opening.data?.entry ?? undefined

  return (
    <>
      <div className="page-header">
        <h1>전기분 재무상태표</h1>
        <p className="muted">
          프로그램을 처음 쓰는 해의 <strong>시작 잔액</strong>을 입력합니다. 직전 연도 말 재무상태표의 자산·부채·자본을 옮겨 적으면
          되고, 수익·비용은 넣지 않습니다. 외상매출금처럼 거래처가 필요한 계정은 거래처별로 나눠 적습니다.
        </p>
      </div>

      {years.isSuccess && !first && (
        <div className="alert" role="alert">
          회계연도가 없습니다. <Link to="../fiscal-years">회계연도</Link>를 먼저 만드세요.
        </div>
      )}
      {notice && (
        <p className="ok" role="status">
          {notice}
        </p>
      )}

      {first && year !== null && opening.data && accounts.data && partners.data && (
        <OpeningEditor
          key={`${entry?.id ?? 'new'}-${entry?.version ?? 0}-${entry?.status ?? ''}`}
          companyId={companyId}
          year={year}
          termNo={first.termNo}
          startDate={first.startDate}
          entry={entry}
          carried={carried}
          accounts={accounts.data}
          partners={partners.data}
          canApprove={company.role === 'APPROVER' || company.role === 'ADMIN'}
          periodClosed={first.periods[0]?.status === 'CLOSED'}
          onCarry={setCarried}
          onNotice={(message, keepCarried) => {
            setNotice(message)
            // 역분개 안내는 새 편집기가 뜨기 전에 올 수 있다 — 그때 지우면 넘겨준 줄이 사라진다
            if (!keepCarried) setCarried(null)
          }}
        />
      )}
    </>
  )
}

interface EditorProps {
  companyId: number
  year: number
  termNo: number
  startDate: string
  entry?: JournalEntry
  carried: OpeningRow[] | null
  accounts: Account[]
  partners: Partner[]
  canApprove: boolean
  periodClosed: boolean
  /** 역분개 직전에 지금 줄을 맡긴다 — 전표가 사라진 뒤 새로 뜨는 편집기가 이어받는다 */
  onCarry: (rows: OpeningRow[]) => void
  /** @param keepCarried 맡긴 줄을 아직 지우지 않는다 (역분개 직후) */
  onNotice: (message: string, keepCarried?: boolean) => void
}

type HelpState = { rowKey: number; field: 'account' | 'partner'; keyword: string; origin: HTMLInputElement }

/**
 * 저장·전기·삭제·역분개가 끝나면 전표 상태가 바뀌어 이 편집기는 새로 그려진다.
 * mutate()에 준 콜백은 컴포넌트가 사라지면 불리지 않으므로, 사라져도 이어지는 mutateAsync().then()으로 안내를 띄운다.
 * 실패는 mutation.error로 화면에 표시하므로 여기서는 삼킨다.
 */
const ignore = () => {}

/** 키보드 흐름은 일반전표와 같다: Enter = 다음 칸(마지막 칸이면 줄 추가), F2 = 코드 도움, F3 = 저장, F8 = 전기 */
export function OpeningEditor(props: EditorProps) {
  const { companyId, year, entry, accounts, partners, canApprove, periodClosed } = props
  const accountMap = useMemo(() => new Map(accounts.map((a) => [a.code, a])), [accounts])
  const partnerMap = useMemo(() => new Map(partners.map((p) => [p.code, p])), [partners])
  const [rows, setRows] = useState<OpeningRow[]>(() =>
    entry ? fromEntryLines(entry.lines, accountMap) : (props.carried ?? [emptyRow()]),
  )
  const [dirty, setDirty] = useState(() => !entry && props.carried !== null)
  const [showErrors, setShowErrors] = useState(false)
  const [help, setHelp] = useState<HelpState | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const focusRowRef = useRef<number | null>(null)

  const save = useSaveOpeningBalance(companyId, year)
  const post = usePostEntry(companyId)
  const remove = useDeleteEntry(companyId)
  const reverse = useReverseEntry(companyId)

  // 기초잔액에 올 수 있는 계정만 코드 도움에 보여 준다
  const accountItems = useMemo<CodeItem[]>(
    () =>
      accounts
        .filter((a) => a.active && !a.system && a.statement === 'BS')
        .map((a) => ({ code: a.code, name: a.name, sub: a.subgroup })),
    [accounts],
  )
  const partnerItems = useMemo<CodeItem[]>(
    () => partners.filter((p) => p.active).map((p) => ({ code: p.code, name: p.name, sub: p.bizRegNo ?? undefined })),
    [partners],
  )

  const readOnly = periodClosed || entry?.status === 'POSTED'
  const t = totals(rows, accountMap)
  const errors = validate(rows, accountMap, partnerMap)
  const hasErrors = errors.entry.length > 0 || errors.byRow.size > 0
  const busy = save.isPending || post.isPending || remove.isPending || reverse.isPending
  const mutationError = [save, post, remove, reverse].find((m) => m.error)?.error
  const errorText = mutationError instanceof ApiError ? mutationError.userMessage : mutationError?.message

  useEffect(() => {
    if (focusRowRef.current === null) return
    boxRef.current?.querySelector<HTMLElement>(`[data-row="${focusRowRef.current}"] [data-nav]`)?.focus()
    focusRowRef.current = null
  }, [rows])

  function change(key: number, patch: Partial<OpeningRow>) {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)))
    setDirty(true)
  }

  function addRow() {
    const row = emptyRow()
    focusRowRef.current = row.key
    setRows((rs) => [...rs, row])
  }

  function focusNext(from: HTMLElement) {
    const navs = [...(boxRef.current?.querySelectorAll<HTMLElement>('[data-nav]') ?? [])]
    const i = navs.indexOf(from)
    if (i >= 0 && i < navs.length - 1) navs[i + 1].focus()
    else addRow() // 마지막 칸: 다음 계정을 이어서 입력
  }

  function onBoxKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (help || e.nativeEvent.isComposing) return // 한글 조합을 끝내는 Enter는 칸 이동이 아니다
    if (e.key === 'F3') {
      e.preventDefault()
      doSave()
    } else if (e.key === 'F8') {
      e.preventDefault()
      doPost()
    } else if (e.key === 'Enter' && (e.target as HTMLElement).dataset.nav !== undefined) {
      e.preventDefault()
      focusNext(e.target as HTMLElement)
    }
  }

  function onCodeKeyDown(e: KeyboardEvent<HTMLInputElement>, row: OpeningRow, field: 'account' | 'partner') {
    if (e.nativeEvent.isComposing) return
    const value = e.currentTarget.value.trim()
    const known = field === 'account' ? accountMap.has(value) : !value || partnerMap.has(value)
    if (e.key === 'F2' || (e.key === 'Enter' && value && !known)) {
      e.preventDefault()
      e.stopPropagation()
      setHelp({ rowKey: row.key, field, keyword: value, origin: e.currentTarget })
    }
  }

  function pick(item: CodeItem) {
    if (!help) return
    change(help.rowKey, help.field === 'account' ? { accountCode: item.code } : { partnerCode: item.code })
    const origin = help.origin
    setHelp(null)
    requestAnimationFrame(() => focusNext(origin))
  }

  function doSave() {
    if (readOnly || busy) return
    if (hasErrors) {
      setShowErrors(true)
      return
    }
    save.mutateAsync(toApiLines(rows, accountMap)).then(() => {
      setDirty(false)
      props.onNotice('기초잔액을 임시 저장했습니다. 전기해야 장부에 반영됩니다.')
    }, ignore)
  }

  function doPost() {
    if (!entry || entry.status !== 'DRAFT' || dirty || !canApprove || periodClosed || busy) return
    if (!window.confirm('전기하면 기초잔액을 바로 고칠 수 없고, 정정하려면 역분개한 뒤 다시 입력해야 합니다. 전기할까요?')) return
    post.mutateAsync(entry.id).then(() => props.onNotice('기초잔액을 전기했습니다. 이제 장부의 시작 잔액이 됩니다.'), ignore)
  }

  function doDelete() {
    if (!entry || entry.status !== 'DRAFT' || busy) return
    if (!window.confirm('임시 저장한 기초잔액을 삭제할까요?')) return
    remove.mutateAsync(entry.id).then(() => props.onNotice('임시 기초잔액을 삭제했습니다.'), ignore)
  }

  function doReverse() {
    if (!entry || entry.status !== 'POSTED' || !canApprove || busy) return
    if (!window.confirm('기초잔액 전표를 역분개합니다. 지금 금액은 입력 칸에 그대로 남으니 고친 뒤 다시 저장·전기하세요. 계속할까요?')) return
    props.onCarry(rows)
    reverse
      .mutateAsync({ id: entry.id, reversalDate: entry.entryDate, reason: '기초잔액 정정' })
      .then(() => props.onNotice('기초잔액을 역분개했습니다. 금액을 고친 뒤 다시 저장하세요.', true), ignore)
  }

  return (
    <div className="card entry-editor" ref={boxRef} onKeyDown={onBoxKeyDown}>
      <div className="entry-head">
        <h2>
          제{props.termNo}기 기초잔액 <span className="muted">{props.startDate} 기준</span>{' '}
          {entry ? (
            <span className={entry.status === 'POSTED' ? 'badge closed' : 'badge'}>{STATUS_LABEL[entry.status]}</span>
          ) : (
            <span className="muted">아직 입력하지 않았습니다</span>
          )}
        </h2>
      </div>
      {entry?.status === 'POSTED' && (
        <p className="muted">전기되어 장부에 반영된 상태입니다. 고치려면 역분개한 뒤 다시 입력합니다.</p>
      )}

      <table className="entry-lines">
        <thead>
          <tr>
            <th>계정과목</th>
            <th>거래처</th>
            <th className="money" style={{ width: 160 }}>차변 (자산)</th>
            <th className="money" style={{ width: 160 }}>대변 (부채·자본)</th>
            <th style={{ width: 32 }} />
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const account = accountMap.get(r.accountCode.trim())
            const partner = partnerMap.get(r.partnerCode.trim())
            const rowErrors = showErrors ? errors.byRow.get(r.key) : undefined
            const onDebit = (account?.normalBalance ?? 'DEBIT') === 'DEBIT'
            const amountInput = (
              <input
                aria-label={`${i + 1}번째 줄 금액`}
                data-nav
                className="money"
                inputMode="numeric"
                value={r.amount}
                disabled={readOnly}
                onChange={(e) => change(r.key, { amount: e.target.value })}
                onBlur={(e) => {
                  const v = parseWon(e.target.value)
                  if (v !== null) change(r.key, { amount: formatWon(v) })
                }}
              />
            )
            return (
              <tr key={r.key} data-row={r.key} className={rowErrors ? 'has-error' : undefined}>
                <td>
                  <div className="code-cell">
                    <input
                      aria-label={`${i + 1}번째 줄 계정`}
                      data-nav
                      value={r.accountCode}
                      disabled={readOnly}
                      placeholder="F2"
                      size={5}
                      onChange={(e) => change(r.key, { accountCode: e.target.value })}
                      onKeyDown={(e) => onCodeKeyDown(e, r, 'account')}
                    />
                    <span className="code-name">
                      {account?.name}
                      {account && <span className="muted"> · {account.subgroup}</span>}
                    </span>
                  </div>
                  {rowErrors && <div className="line-error">{rowErrors.join(' · ')}</div>}
                </td>
                <td>
                  <div className="code-cell">
                    <input
                      aria-label={`${i + 1}번째 줄 거래처`}
                      data-nav
                      value={r.partnerCode}
                      disabled={readOnly}
                      placeholder={account?.requiresPartner ? '필수 F2' : 'F2'}
                      size={6}
                      onChange={(e) => change(r.key, { partnerCode: e.target.value })}
                      onKeyDown={(e) => onCodeKeyDown(e, r, 'partner')}
                    />
                    <span className="code-name">{partner?.name}</span>
                  </div>
                </td>
                <td className="money">{onDebit ? amountInput : null}</td>
                <td className="money">{onDebit ? null : amountInput}</td>
                <td>
                  {!readOnly && rows.length > 1 && (
                    <button
                      className="icon"
                      aria-label={`${i + 1}번째 줄 삭제`}
                      onClick={() => {
                        setRows((rs) => rs.filter((x) => x.key !== r.key))
                        setDirty(true)
                      }}
                    >
                      ✕
                    </button>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <div className="entry-totals" aria-label="합계">
        <span>
          차변 합계 <strong className="money">{formatWon(t.debit)}</strong>
        </span>
        <span>
          대변 합계 <strong className="money">{formatWon(t.credit)}</strong>
        </span>
        <span className={t.difference === 0 ? 'ok' : 'danger'}>
          차액 <strong className="money">{formatWon(Math.abs(t.difference))}</strong>
        </span>
        {!readOnly && <button onClick={addRow}>줄 추가</button>}
      </div>

      {showErrors && errors.entry.length > 0 && (
        <div className="alert" role="alert">
          {errors.entry.join(' · ')}
        </div>
      )}
      {errorText && (
        <div className="alert" role="alert">
          {errorText}
        </div>
      )}

      <div className="toolbar entry-actions">
        {!readOnly && (
          <button className="primary" onClick={doSave} disabled={busy || t.difference !== 0 || (!!entry && !dirty)}>
            저장 (F3)
          </button>
        )}
        {entry?.status === 'DRAFT' && canApprove && !periodClosed && (
          <button onClick={doPost} disabled={busy || dirty} title={dirty ? '저장한 뒤 전기할 수 있습니다' : undefined}>
            전기 (F8)
          </button>
        )}
        {entry?.status === 'DRAFT' && !periodClosed && (
          <button className="danger" onClick={doDelete} disabled={busy}>
            삭제
          </button>
        )}
        {entry?.status === 'POSTED' && canApprove && (
          <button onClick={doReverse} disabled={busy}>
            역분개
          </button>
        )}
        {entry?.status === 'DRAFT' && !canApprove && <span className="muted">전기는 승인자 이상만 할 수 있습니다</span>}
      </div>

      {help && (
        <CodeHelp
          title={help.field === 'account' ? '계정과목 코드 도움' : '거래처 코드 도움'}
          items={help.field === 'account' ? accountItems : partnerItems}
          initialKeyword={help.keyword}
          onSelect={pick}
          onClose={() => {
            const origin = help.origin
            setHelp(null)
            requestAnimationFrame(() => origin.focus())
          }}
        />
      )}
    </div>
  )
}
