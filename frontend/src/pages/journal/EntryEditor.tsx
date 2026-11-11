import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

import { ApiError } from '../../api/client'
import type { Account, Partner } from '../../api/types'
import { formatWon, parseWon } from '../../lib/money'
import { CodeHelp } from './CodeHelp'
import type { CodeItem } from './CodeHelp'
import { emptyLine, fromEntryLines, KIND_OPTIONS, toApiLines, totals, validate } from './entryForm'
import type { FormLine, LineKind } from './entryForm'
import { useCreateEntry, useDeleteEntry, usePostEntry, useReverseEntry, useUpdateEntry } from './hooks'
import { STATUS_LABEL, TYPE_LABEL } from './types'
import type { JournalEntry } from './types'

interface Props {
  companyId: number
  month: string // YYYY-MM
  entry?: JournalEntry // 없으면 새 전표
  defaultDay: number
  accounts: Account[]
  partners: Partner[]
  canApprove: boolean
  periodClosed: boolean
  onSaved: (entry: JournalEntry, message: string) => void
  onDeleted: () => void
  onOpenEntry: (id: number) => void
}

type HelpState = { lineKey: number; field: 'account' | 'partner'; keyword: string; origin: HTMLInputElement }

/** 차변 칸에 금액을 넣는 구분: 차변, 출금(상대 현금은 대변). 나머지는 대변 칸 */
const onDebitColumn = (kind: LineKind) => kind === 'DEBIT' || kind === 'WITHDRAW'

const kindOption = (kind: LineKind) => KIND_OPTIONS.find((o) => o.value === kind)!

const label = (e: JournalEntry) => `${e.entryDate} ${String(e.entryNo).padStart(4, '0')}`

/**
 * 전표 한 장 편집기. 키보드 흐름: Enter = 다음 칸, F2 = 코드 도움, F3 = 저장, F8 = 전기.
 * 마지막 칸에서 Enter를 누르면 차액이 남아 있으면 반대편 줄을 차액으로 채워 추가하고, 맞으면 저장 버튼으로 간다.
 */
export function EntryEditor(props: Props) {
  const { companyId, month, entry, accounts, partners, canApprove, periodClosed } = props
  const [day, setDay] = useState(() => (entry ? String(Number(entry.entryDate.slice(8))) : String(props.defaultDay)))
  const [lines, setLines] = useState<FormLine[]>(() => (entry ? fromEntryLines(entry.lines) : [emptyLine()]))
  const [dirty, setDirty] = useState(false)
  const [showErrors, setShowErrors] = useState(false)
  const [help, setHelp] = useState<HelpState | null>(null)
  const [reverseForm, setReverseForm] = useState<{ date: string; reason: string } | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const saveRef = useRef<HTMLButtonElement>(null)
  const focusLineRef = useRef<number | null>(null)

  const create = useCreateEntry(companyId)
  const update = useUpdateEntry(companyId)
  const remove = useDeleteEntry(companyId)
  const post = usePostEntry(companyId)
  const reverse = useReverseEntry(companyId)

  const accountMap = useMemo(() => new Map(accounts.map((a) => [a.code, a])), [accounts])
  const partnerMap = useMemo(() => new Map(partners.map((p) => [p.code, p])), [partners])
  const accountItems = useMemo<CodeItem[]>(
    () => accounts.filter((a) => a.active && !a.system).map((a) => ({ code: a.code, name: a.name, sub: a.subgroup })),
    [accounts],
  )
  const partnerItems = useMemo<CodeItem[]>(
    () => partners.filter((p) => p.active).map((p) => ({ code: p.code, name: p.name, sub: p.bizRegNo ?? undefined })),
    [partners],
  )

  const readOnly = periodClosed || entry?.status === 'POSTED'
  const t = totals(lines)
  const errors = validate(lines, accountMap, partnerMap)
  const hasErrors = errors.entry.length > 0 || errors.byLine.size > 0
  const busy = create.isPending || update.isPending || remove.isPending || post.isPending || reverse.isPending
  const mutationError = [create, update, remove, post, reverse].find((m) => m.error)?.error
  const errorText = mutationError instanceof ApiError ? mutationError.userMessage : mutationError?.message

  // 줄을 추가한 뒤 그 줄의 첫 칸으로 커서를 옮긴다
  useEffect(() => {
    if (focusLineRef.current === null) return
    boxRef.current?.querySelector<HTMLElement>(`[data-line="${focusLineRef.current}"] [data-nav]`)?.focus()
    focusLineRef.current = null
  }, [lines])

  function change(key: number, patch: Partial<FormLine>) {
    setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)))
    setDirty(true)
  }

  function navElements(): HTMLElement[] {
    return [...(boxRef.current?.querySelectorAll<HTMLElement>('[data-nav]') ?? [])].filter(
      (el) => !(el as HTMLInputElement).disabled,
    )
  }

  function focusNext(from: HTMLElement) {
    const navs = navElements()
    const i = navs.indexOf(from)
    if (i >= 0 && i < navs.length - 1) {
      navs[i + 1].focus()
      return
    }
    // 마지막 칸: 차액이 남았으면 반대편 줄을 차액으로 채워 추가 (전산회계 프로그램과 같은 흐름)
    if (t.difference !== 0) {
      const last = lines[lines.length - 1]
      const added: FormLine = {
        ...emptyLine(last?.memo ?? ''),
        kind: t.difference > 0 ? 'CREDIT' : 'DEBIT',
        amount: formatWon(Math.abs(t.difference)),
      }
      focusLineRef.current = added.key
      setLines((ls) => [...ls, added])
      setDirty(true)
    } else {
      saveRef.current?.focus()
    }
  }

  function onBoxKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    // 한글 입력 중 Enter는 글자 조합을 끝내는 키다. 이때 칸을 옮기면 마지막 글자가 빠지거나 엉뚱한 칸에 들어간다
    if (help || e.nativeEvent.isComposing) return
    if (e.key === 'F3') {
      e.preventDefault()
      save()
    } else if (e.key === 'F8') {
      e.preventDefault()
      doPost()
    } else if (e.key === 'Enter' && (e.target as HTMLElement).dataset.nav !== undefined) {
      e.preventDefault()
      focusNext(e.target as HTMLElement)
    }
  }

  /** 계정·거래처 칸: F2이거나, 코드가 아닌 글자를 치고 Enter면 코드 도움을 연다 */
  function onCodeKeyDown(e: KeyboardEvent<HTMLInputElement>, line: FormLine, field: 'account' | 'partner') {
    if (e.nativeEvent.isComposing) return
    const value = e.currentTarget.value.trim()
    const known = field === 'account' ? accountMap.has(value) : !value || partnerMap.has(value)
    if (e.key === 'F2' || (e.key === 'Enter' && value && !known)) {
      e.preventDefault()
      e.stopPropagation()
      setHelp({ lineKey: line.key, field, keyword: value, origin: e.currentTarget })
    }
  }

  function pick(item: CodeItem) {
    if (!help) return
    change(help.lineKey, help.field === 'account' ? { accountCode: item.code } : { partnerCode: item.code })
    const origin = help.origin
    setHelp(null)
    requestAnimationFrame(() => focusNext(origin))
  }

  function entryDate() {
    return `${month}-${day.padStart(2, '0')}`
  }

  function save() {
    if (readOnly || busy) return
    if (hasErrors) {
      setShowErrors(true)
      return
    }
    const body = {
      entryDate: entryDate(),
      description: lines.find((l) => l.memo.trim())?.memo.trim() ?? null,
      lines: toApiLines(lines),
    }
    const done = (saved: JournalEntry | JournalEntry[] | void) => {
      const e = saved as JournalEntry
      setDirty(false)
      props.onSaved(e, `전표 ${label(e)}을(를) 임시 저장했습니다`)
    }
    if (entry) update.mutate({ id: entry.id, version: entry.version, ...body }, { onSuccess: done })
    else create.mutate(body, { onSuccess: done })
  }

  function doPost() {
    if (!entry || entry.status !== 'DRAFT' || dirty || !canApprove || periodClosed || busy) return
    if (!window.confirm('전기하면 이 전표는 수정·삭제할 수 없고, 정정은 역분개로만 할 수 있습니다. 전기할까요?')) return
    post.mutate(entry.id, {
      onSuccess: (e) => props.onSaved(e as JournalEntry, `전표 ${label(e as JournalEntry)}을(를) 전기했습니다`),
    })
  }

  function doDelete() {
    if (!entry || entry.status !== 'DRAFT' || busy) return
    if (!window.confirm('임시 전표를 삭제할까요?')) return
    remove.mutate(entry.id, { onSuccess: props.onDeleted })
  }

  function doReverse() {
    if (!entry || !reverseForm) return
    reverse.mutate(
      { id: entry.id, reversalDate: reverseForm.date, reason: reverseForm.reason },
      {
        onSuccess: (r) => {
          setReverseForm(null)
          props.onSaved(r as JournalEntry, `역분개 전표 ${label(r as JournalEntry)}을(를) 만들어 전기했습니다`)
        },
      },
    )
  }

  const canReverse =
    entry?.status === 'POSTED' && entry.entryType !== 'REVERSAL' && entry.reversedById === null && canApprove
  const lastDay = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate()

  return (
    <div className="card entry-editor" ref={boxRef} onKeyDown={onBoxKeyDown}>
      <div className="entry-head">
        <h2>
          {entry ? (
            <>
              전표 {label(entry)}{' '}
              <span className={entry.status === 'POSTED' ? 'badge closed' : 'badge'}>{STATUS_LABEL[entry.status]}</span>{' '}
              {entry.entryType !== 'GENERAL' && <span className="badge">{TYPE_LABEL[entry.entryType]}</span>}
            </>
          ) : (
            '새 전표'
          )}
        </h2>
        <label className="inline">
          일
          <input
            aria-label="일"
            data-nav
            type="number"
            min={1}
            max={lastDay}
            value={day}
            disabled={readOnly}
            onChange={(e) => {
              setDay(e.target.value)
              setDirty(true)
            }}
            style={{ width: 64 }}
          />
        </label>
      </div>

      {entry?.reversalOfId && (
        <p className="muted">
          역분개 전표입니다{entry.reversalReason ? ` — 사유: ${entry.reversalReason}` : ''}.{' '}
          <button className="link" onClick={() => props.onOpenEntry(entry.reversalOfId!)}>원전표 보기</button>
        </p>
      )}
      {entry?.reversedById && (
        <p className="muted">
          이 전표는 역분개되어 효과가 상쇄되었습니다.{' '}
          <button className="link" onClick={() => props.onOpenEntry(entry.reversedById!)}>역분개 전표 보기</button>
        </p>
      )}

      <table className="entry-lines">
        <thead>
          <tr>
            <th style={{ width: 92 }}>구분</th>
            <th>계정과목</th>
            <th>거래처</th>
            <th>적요</th>
            <th className="money" style={{ width: 140 }}>차변</th>
            <th className="money" style={{ width: 140 }}>대변</th>
            <th style={{ width: 32 }} />
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => {
            const account = accountMap.get(l.accountCode.trim())
            const partner = partnerMap.get(l.partnerCode.trim())
            const lineErrors = showErrors ? errors.byLine.get(l.key) : undefined
            const cashSide = l.kind === 'WITHDRAW' || l.kind === 'DEPOSIT'
            const amountInput = (
              <input
                aria-label={`${i + 1}번째 줄 금액`}
                data-nav
                className="money"
                inputMode="numeric"
                value={l.amount}
                disabled={readOnly}
                onChange={(e) => change(l.key, { amount: e.target.value })}
                onBlur={(e) => {
                  const v = parseWon(e.target.value)
                  if (v !== null) change(l.key, { amount: formatWon(v) })
                }}
              />
            )
            const cashHint = cashSide ? <span className="muted">현금 {l.amount}</span> : null
            return (
              <tr key={l.key} data-line={l.key} className={lineErrors ? 'has-error' : undefined}>
                <td>
                  {/* 전산회계 프로그램처럼 숫자 한 자리로 고른다: 1 출금 · 2 입금 · 3 차변 · 4 대변 */}
                  <div className="kind-cell">
                    <input
                      aria-label={`${i + 1}번째 줄 구분`}
                      data-nav
                      inputMode="numeric"
                      maxLength={1}
                      value={kindOption(l.kind).key}
                      disabled={readOnly}
                      title="1 출금 · 2 입금 · 3 차변 · 4 대변"
                      onFocus={(e) => e.currentTarget.select()}
                      onChange={(e) => {
                        const picked = KIND_OPTIONS.find((o) => o.key === e.target.value.slice(-1))
                        if (picked) change(l.key, { kind: picked.value })
                      }}
                    />
                    <span>{kindOption(l.kind).label}</span>
                  </div>
                </td>
                <td>
                  <div className="code-cell">
                    <input
                      aria-label={`${i + 1}번째 줄 계정`}
                      data-nav
                      value={l.accountCode}
                      disabled={readOnly}
                      placeholder="F2"
                      size={5}
                      onChange={(e) => change(l.key, { accountCode: e.target.value })}
                      onKeyDown={(e) => onCodeKeyDown(e, l, 'account')}
                    />
                    <span className="code-name">
                      {account?.name}
                      {account?.requiresPartner && <span className="muted"> (거래처 필수)</span>}
                    </span>
                  </div>
                </td>
                <td>
                  <div className="code-cell">
                    <input
                      aria-label={`${i + 1}번째 줄 거래처`}
                      data-nav
                      value={l.partnerCode}
                      disabled={readOnly}
                      placeholder={account?.requiresPartner ? '필수 F2' : 'F2'}
                      size={6}
                      onChange={(e) => change(l.key, { partnerCode: e.target.value })}
                      onKeyDown={(e) => onCodeKeyDown(e, l, 'partner')}
                    />
                    <span className="code-name">{partner?.name}</span>
                  </div>
                </td>
                <td>
                  <input
                    aria-label={`${i + 1}번째 줄 적요`}
                    data-nav
                    value={l.memo}
                    disabled={readOnly}
                    onChange={(e) => change(l.key, { memo: e.target.value })}
                  />
                  {lineErrors && <div className="line-error">{lineErrors.join(' · ')}</div>}
                </td>
                <td className="money">{onDebitColumn(l.kind) ? amountInput : cashHint}</td>
                <td className="money">{onDebitColumn(l.kind) ? cashHint : amountInput}</td>
                <td>
                  {!readOnly && lines.length > 1 && (
                    <button
                      className="icon"
                      aria-label={`${i + 1}번째 줄 삭제`}
                      onClick={() => {
                        setLines((ls) => ls.filter((x) => x.key !== l.key))
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
        {!readOnly && (
          <button
            onClick={() => {
              const l = emptyLine(lines[lines.length - 1]?.memo ?? '')
              focusLineRef.current = l.key
              setLines((ls) => [...ls, l])
            }}
          >
            줄 추가
          </button>
        )}
      </div>

      {showErrors && errors.entry.length > 0 && (
        <div className="alert" role="alert">{errors.entry.join(' · ')}</div>
      )}
      {errorText && <div className="alert" role="alert">{errorText}</div>}

      <div className="toolbar entry-actions">
        {!readOnly && (
          <button ref={saveRef} className="primary" onClick={save} disabled={busy || t.difference !== 0 || (!!entry && !dirty)}>
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
        {canReverse && !reverseForm && (
          <button onClick={() => setReverseForm({ date: entry!.entryDate, reason: '' })}>역분개</button>
        )}
        {entry?.status === 'DRAFT' && !canApprove && <span className="muted">전기는 승인자 이상만 할 수 있습니다</span>}
      </div>

      {reverseForm && (
        <div className="reverse-form">
          <p className="muted">
            원전표와 차변·대변이 반대인 전표를 만들어 바로 전기합니다. 원전표의 달이 마감됐으면 정정할 날짜(열린 달)를 고르세요.
          </p>
          <div className="toolbar">
            <label>
              역분개 일자
              <input
                type="date"
                min={entry?.entryDate}
                value={reverseForm.date}
                onChange={(e) => setReverseForm({ ...reverseForm, date: e.target.value })}
              />
            </label>
            <label>
              사유
              <input
                value={reverseForm.reason}
                onChange={(e) => setReverseForm({ ...reverseForm, reason: e.target.value })}
                placeholder="예: 계정 오류 (통신비 → 여비교통비)"
                size={32}
              />
            </label>
            <button className="primary" onClick={doReverse} disabled={busy || !reverseForm.date}>
              역분개 실행
            </button>
            <button onClick={() => setReverseForm(null)}>취소</button>
          </div>
        </div>
      )}

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
