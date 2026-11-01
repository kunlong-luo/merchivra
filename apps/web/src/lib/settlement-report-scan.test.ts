import {afterEach,describe,expect,it,vi} from 'vitest'
import {reportScanStatus,getReportScan,startReportScan} from './settlement-report-scan'
import {settlementComparison} from './settlement-comparison'

const mp='22222222-2222-4222-8222-222222222222'
const status={jobId:'11111111-1111-4111-8111-111111111111',state:'RETRY',
  generation:2,completedPages:3,importedReports:1,retryCount:2,rowOffset:1,
  nextAttemptAt:'2026-11-01T00:00:00Z',
  lastFailureCode:'UPSTREAM_RETRY',updatedAt:'2026-11-01T00:00:00Z'}

describe('Reports task and independent Finances reconciliation',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('validates lease queue status without leaking cursor or tokens',()=>{
  expect(reportScanStatus.parse({...status,nextToken:'DO_NOT_LEAK'})).not.toHaveProperty('nextToken')
  expect(()=>reportScanStatus.parse({...status,state:'BANK_PAID'})).toThrow()
  expect(()=>reportScanStatus.parse({...status,retryCount:-1})).toThrow()
 })
 it('GET uses only trusted current Owner Session marketplace ID',async()=>{
  const fetcher=vi.fn().mockResolvedValue({ok:true,json:async()=>status})
  vi.stubGlobal('fetch',fetcher)
  expect((await getReportScan(mp)).rowOffset).toBe(1)
  expect(fetcher.mock.calls[0][0]).toBe('/api/v1/settlement-report-scan/'+mp)
  expect(fetcher.mock.calls[0][1].credentials).toBe('same-origin')
  expect(fetcher.mock.calls[0][1].cache).toBe('no-store')
  expect(fetcher.mock.calls[0][1]).not.toHaveProperty('body')
 })
 it('POST is explicit and sends CSRF without a remote URL or token',async()=>{
  const fetcher=vi.fn().mockImplementation((url:string)=>Promise.resolve({
   ok:true,json:async()=>url.endsWith('/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:status
  }))
  vi.stubGlobal('fetch',fetcher)
  await startReportScan(mp)
  const [url,req]=fetcher.mock.calls[1]
  expect(url).toBe('/api/v1/settlement-report-scan/'+mp+'/start')
  expect(req.method).toBe('POST')
  expect(req.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(req).not.toHaveProperty('body')
 })
 it('never accepts auto-confirmed settlement or bank verified',()=>{
  const comparison={
   reportId:'RPT-12345678',settlementId:'SETTLE-12345678',currency:'USD',
   reportedNet:'17.50',reportLinesNet:'17.50',financesNet:'17.50',
   financeTransactionCount:2,otherCurrencyTransactionCount:0,
   state:'SOURCE_AMOUNTS_AGREE_UNVERIFIED',reconciled:false,bankVerified:false
  }
  expect(settlementComparison.parse(comparison).bankVerified).toBe(false)
  expect(()=>settlementComparison.parse({...comparison,bankVerified:true})).toThrow()
  expect(()=>settlementComparison.parse({...comparison,reconciled:true})).toThrow()
 })
})
