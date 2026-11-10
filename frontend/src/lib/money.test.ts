import { describe, expect, it } from 'vitest'

import { formatWon, parseWon } from './money'

describe('formatWon', () => {
  it('천 단위 쉼표', () => {
    expect(formatWon(140166520)).toBe('140,166,520')
    expect(formatWon(0)).toBe('0')
  })

  it('음수는 재무제표 관행대로 괄호', () => {
    expect(formatWon(-465000)).toBe('(465,000)')
  })

  it('비어 있으면 빈 문자열', () => {
    expect(formatWon(null)).toBe('')
    expect(formatWon(undefined)).toBe('')
  })

  it('소수나 안전 범위를 벗어난 값은 거부 (원 단위 정수만)', () => {
    expect(() => formatWon(0.1 + 0.2)).toThrow(RangeError)
    expect(() => formatWon(Number.MAX_SAFE_INTEGER + 1)).toThrow(RangeError)
  })
})

describe('parseWon', () => {
  it.each([
    ['1,234,567', 1234567],
    [' 1234567 ', 1234567],
    ['(50,000)', -50000],
    ['-50000', -50000],
    ['0', 0],
  ])('%s → %d', (input, expected) => {
    expect(parseWon(input)).toBe(expected)
  })

  it.each(['', '   ', '12.5', 'abc', '1,2a', '99999999999999999999'])('"%s" → null', (input) => {
    expect(parseWon(input)).toBeNull()
  })
})
