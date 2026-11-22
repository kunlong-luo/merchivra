import {afterEach,describe,expect,it,vi} from 'vitest'
import {compareSettlementReport,settlementComparison} from './settlement-comparison'

const mp='22222222-2222-4222-8222-222222222222'
const id='11111111-1111-4111-8111-111111111111'
const sample={
 reportId:'RPT-001',settlementId:'SET-001',currency:'USD',
 reportedNet:'17.500000',reportLinesNet:'17.500000',financesNet:'17.500000',
 financeTransactionCount:2,otherCurrencyTransactionCount:0,
 expectedReportLineCount:2,actualReportLineCount:2,
 foreignCurrencyReportLineCount:0,
 multiplyReferencedFinancesEventCount:0,otherReportSameSettlementCount:0,
 state:'SOURCE_AMOUNTS_AGREE_UNVERIFIED',reconciled:false,bankVerified:false
}

describe('V198 settlement evidence conflicts cannot pass as reconciled',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('reads only the current marketplace and preserves unverified flags',async()=>{
  const fetchSpy=vi.fn().mockResolvedValue({ok:true,json:async()=>sample})
  vi.stubGlobal('fetch',fetchSpy)
  const found=await compareSettlementReport(mp,id)
  expect(found.state).toBe('SOURCE_AMOUNTS_AGREE_UNVERIFIED')
  expect(found.reconciled).toBe(false)
  expect(found.bankVerified).toBe(false)
  const [url,options]=fetchSpy.mock.calls[0]
  expect(url).toContain('/marketplaces/'+mp+'/reports/'+id+'/finance-comparison')
  expect(url).not.toContain('tenantId')
  expect(options.credentials).toBe('same-origin')
  expect(options.cache).toBe('no-store')
 })
 it('accepts explicit multi-reference and duplicate-report blocking states',()=>{
  const conflicted={...sample,
   state:'FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM',
   multiplyReferencedFinancesEventCount:1}
  expect(settlementComparison.parse(conflicted).state)
   .toBe('FINANCES_EVENT_MULTI_SETTLEMENT_CLAIM')
  expect(settlementComparison.parse({...sample,
   state:'SETTLEMENT_REFERENCE_SHARED_REPORTS',
   otherReportSameSettlementCount:1}).otherReportSameSettlementCount).toBe(1)
 })
 it('rejects missing, invalid or fabricated source checks',()=>{
  const {expectedReportLineCount:omitted,...incomplete}=sample
  expect(()=>settlementComparison.parse(incomplete)).toThrow()
  expect(()=>settlementComparison.parse({...sample,
   multiplyReferencedFinancesEventCount:-1})).toThrow()
  expect(()=>settlementComparison.parse({...sample,bankVerified:true})).toThrow()
  expect(()=>settlementComparison.parse({...sample,reconciled:true})).toThrow()
  expect(()=>settlementComparison.parse({...sample,state:'BANK_SETTLED'})).toThrow()
 })
})
