import { describe, expect, it } from 'vitest'
import { coreStatusLabel, filterLoadedRows, formatSourceTime, sourceLabel } from './core-view'

describe('core workbench read-only presentation', () => {
  const rows = [
    { sku: 'ABC-100', asin: 'B012345678', internal: null },
    { sku: 'TEST-2', asin: null, internal: 'INTERNAL-100' }
  ]

  it('filters only loaded records and preserves the original list', () => {
    expect(filterLoadedRows(rows, '  abc  ', row => [row.sku, row.asin, row.internal]))
      .toEqual([rows[0]])
    expect(filterLoadedRows(rows, 'internal', row => [row.sku, row.asin, row.internal]))
      .toEqual([rows[1]])
    expect(filterLoadedRows(rows, 'missing', row => [row.sku, row.asin, row.internal]))
      .toEqual([])
    expect(filterLoadedRows(rows, '', row => [row.sku])).toHaveLength(2)
    expect(filterLoadedRows(undefined, 'x', row => [String(row)])).toEqual([])
    expect(rows).toHaveLength(2)
  })

  it('never labels mock or unverified facts as verified Amazon data', () => {
    expect(sourceLabel('MOCK')).toBe('模拟测试')
    expect(sourceLabel('UNVERIFIED')).toBe('来源未核验')
    expect(coreStatusLabel('NOT_OBSERVED')).toBe('尚无财务数据')
    expect(coreStatusLabel('UNKNOWN')).toBe('未知')
    expect(coreStatusLabel('CONNECTED')).toBe('已连接')
    expect(coreStatusLabel('FUTURE_NEW_STATUS')).toBe('FUTURE_NEW_STATUS')
  })

  it('distinguishes missing/invalid timestamps from valid UTC timestamps', () => {
    expect(formatSourceTime(null)).toBe('来源时间未知')
    expect(formatSourceTime('not-a-date')).toBe('来源时间无效')
    expect(formatSourceTime('2026-10-31T00:00:00Z')).toContain('UTC')
  })
})
