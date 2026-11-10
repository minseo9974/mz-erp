import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

export interface CodeItem {
  code: string
  name: string
  sub?: string
}

/**
 * F2 코드 도움 팝업 (계정·거래처 공용).
 * 코드 앞자리나 이름 일부로 거르고, ↑↓로 고르고, Enter로 확정, Esc로 닫는다.
 */
export function CodeHelp({
  title,
  items,
  initialKeyword,
  onSelect,
  onClose,
}: {
  title: string
  items: CodeItem[]
  initialKeyword: string
  onSelect: (item: CodeItem) => void
  onClose: () => void
}) {
  const [keyword, setKeyword] = useState(initialKeyword)
  const [cursor, setCursor] = useState(0)
  const listRef = useRef<HTMLUListElement>(null)

  const found = useMemo(() => {
    const k = keyword.trim().toLowerCase()
    return items.filter((i) => !k || i.code.startsWith(k) || i.name.toLowerCase().includes(k)).slice(0, 200)
  }, [items, keyword])

  useEffect(() => {
    listRef.current?.querySelector('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' })
  }, [cursor])

  function onKeyDown(e: KeyboardEvent) {
    if (e.nativeEvent.isComposing) return // 한글 조합을 끝내는 Enter는 선택으로 보지 않는다
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setCursor((c) => Math.min(c + 1, found.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setCursor((c) => Math.max(c - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      e.stopPropagation()
      if (found[cursor]) onSelect(found[cursor])
    } else if (e.key === 'Escape') {
      e.preventDefault()
      e.stopPropagation()
      onClose()
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal card" role="dialog" aria-label={title} onMouseDown={(e) => e.stopPropagation()}>
        <h2>{title}</h2>
        <input
          autoFocus
          aria-label={`${title} 검색`}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value)
            setCursor(0)
          }}
          onKeyDown={onKeyDown}
          placeholder="코드 또는 이름"
        />
        <ul className="code-list" ref={listRef} role="listbox">
          {found.map((item, i) => (
            <li
              key={item.code}
              role="option"
              aria-selected={i === cursor}
              onMouseEnter={() => setCursor(i)}
              onClick={() => onSelect(item)}
            >
              <span className="code">{item.code}</span> {item.name}
              {item.sub && <span className="muted"> · {item.sub}</span>}
            </li>
          ))}
          {found.length === 0 && <li className="muted">찾는 항목이 없습니다</li>}
        </ul>
        <p className="muted hint">↑↓ 이동 · Enter 선택 · Esc 닫기</p>
      </div>
    </div>
  )
}
