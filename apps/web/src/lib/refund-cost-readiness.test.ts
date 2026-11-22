import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchRefundCostReadiness} from './refund-cost-readiness'

const mp='22222222-2222-4222-8222-222222222222'
const event='11111111-1111-4111-8111-111111111111'
const barriers=[
 'EXTERNAL_REFUND_SOURCE_AUTHENTICITY_UNVERIFIED',
 'PHYSICAL_RETURN_RECEIPT_AND_DISPOSITION_UNVERIFIED',
 'G1_REFUND_COGS_REVERSAL_POLICY_NOT_APPROVED',
 'INVENTORY_AND_ACCOUNTING_POSTING_NOT_AUTHORIZED'
]
const item={
 eventId:event,postedAtUtc:'2026-11-01T00:00:00Z',currency:'USD',
 signedSourceRefundAmount:'-20.000000',
 reviewedOrderId:'33333333-3333-4333-8333-333333333333',
 evidenceState:'REVIEWED',evidenceGap:'PHYSICAL_RECEIPT_AND_ACCOUNTING_NOT_VERIFIED',
 historicalCostExposure:'8.000000',claimedRestockCostSensitivity:'8.000000',
 evidenceCurrent:true,externalReceiptVerified:false,
 actualCogsReversalAllowed:false,actualCogsReversal:null,
 cogsReversalBlockerCodes:barriers
}
const body={
 marketplaceId:mp,periodStartUtc:'2026-11-01',
 periodEndExclusiveUtc:'2026-12-01',
 buckets:[{currency:'USD',sourceEventCount:1,
  signedProductRefundAmount:'-20.000000',
  completeRefundCoverage:false,verifiedCogsReversal:false}],
 events:[item],hasMore:false,nextBeforeAt:null,nextBeforeId:null,
 allFinancesRefundsCovered:false,bankReconciled:false,
 actualCogsReversalsVerified:false,realizedNetProfitVerified:false
}

describe('V197 refund COGS readiness cannot silently upgrade manual evidence',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 function respond(data:unknown){
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({
   ok:true,json:async()=>data
  }))
 }
 it('reads structured blockers and keeps the original signed refund',async()=>{
  respond(body)
  const data=await fetchRefundCostReadiness(mp,'2026-11-01','2026-12-01')
  expect(data.events[0].cogsReversalBlockerCodes).toEqual(barriers)
  expect(data.events[0].signedSourceRefundAmount).toBe('-20.000000')
  expect(data.events[0].actualCogsReversal).toBeNull()
  const [url,init]=vi.mocked(fetch).mock.calls[0]
  expect(url).toContain('/finance/marketplaces/'+mp+'/refund-cost-readiness?')
  expect(url).not.toContain('tenantId')
  expect(init?.credentials).toBe('same-origin')
  expect(init?.cache).toBe('no-store')
 })
 it('rejects a response without blockers or with forged accounting release',async()=>{
  respond({...body,events:[{...item,cogsReversalBlockerCodes:[]}]})
  await expect(fetchRefundCostReadiness(mp,'2026-11-01','2026-12-01'))
   .rejects.toThrow()
  respond({...body,events:[{...item,actualCogsReversalAllowed:true}]})
  await expect(fetchRefundCostReadiness(mp,'2026-11-01','2026-12-01'))
   .rejects.toThrow()
  respond({...body,events:[{...item,actualCogsReversal:'8.000000'}]})
  await expect(fetchRefundCostReadiness(mp,'2026-11-01','2026-12-01'))
   .rejects.toThrow()
 })
})
