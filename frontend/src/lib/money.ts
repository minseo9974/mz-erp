/**
 * 금액은 원 단위 정수만 다룬다 (ADR-0002).
 * 브라우저는 Number.MAX_SAFE_INTEGER(약 9천조 원)까지만 정확하므로 그 범위만 허용한다 (docs/design/api.md).
 */

/** 1234567 → "1,234,567", -50000 → "(50,000)" (재무제표 관행: 음수는 괄호) */
export function formatWon(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return ''
  if (!Number.isSafeInteger(amount)) throw new RangeError(`원 단위 정수가 아닙니다: ${amount}`)
  const text = Math.abs(amount).toLocaleString('ko-KR')
  return amount < 0 ? `(${text})` : text
}

/**
 * 사용자 입력 → 정수. "1,234,567" · " 1234567 " · "(50,000)" · "-50000" 을 받는다.
 * 숫자가 아니거나 소수·범위를 벗어나면 null.
 */
export function parseWon(input: string): number | null {
  const trimmed = input.trim()
  if (!trimmed) return null
  const negative = /^\(.*\)$/.test(trimmed) || trimmed.startsWith('-')
  const digits = trimmed.replace(/[(),\s-]/g, '')
  if (!/^\d+$/.test(digits)) return null
  const value = Number(digits) * (negative ? -1 : 1)
  return Number.isSafeInteger(value) ? value : null
}
