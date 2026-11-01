import {afterEach,describe,expect,it,vi} from 'vitest'
import {decideRefundRecovery,getRefundRecovery,refundRecovery} from './refund-recovery'
const mp='22222222-2222-4222-8222-222222222222'
const event='11111111-1111-4111-8111-111111111111'
const reviewed={
 eventId:event,orderId:'33333333-3333-4333-8333-333333333333',
 state:'REVIEWED',revision:1,evidenceOrigin:'MANUAL_UNVERIFIED',
 requestedBy:'44444444-4444-4444-8444-444444444444',
 decidedBy:'55555555-5555-4555-8555-555555555555',
 evidenceCurrent:true,inventoryReceiptVerified:false,accountingPosted:false,
 exposure:{
  status:'MANUAL_EVIDENCE_BALANCED_UNVERIFIED',currency:'USD',
  signedRefundTotal:'-20.00',allocatedRefund:'-20.00',remainingRefund:'0',
  historicalCostExposure:'10.50',claimedRestockCostSensitivity:'10.50',
  actualCogsReversal:null,inventoryReceiptVerified:false,ledgerPosted:false,
  items:[{itemId:'AMZ-ITEM-1',sku:'SKU-1',refundedQuantity:1,
   disposition:'RESTOCKED_CLAIMED',signedRefund:'-20.00',
   historicalCostExposure:'10.50',claimedRestockCostSensitivity:'10.50'}]
 }
}
describe('V63 manual return cost exposure',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('rejects any forged actual inventory or accounting recovery',()=>{
  expect(refundRecovery.parse(reviewed).exposure.actualCogsReversal).toBeNull()
  expect(()=>refundRecovery.parse({...reviewed,inventoryReceiptVerified:true})).toThrow()
  expect(()=>refundRecovery.parse({...reviewed,accountingPosted:true})).toThrow()
  expect(()=>refundRecovery.parse({...reviewed,exposure:{
   ...reviewed.exposure,actualCogsReversal:'10.50'}})).toThrow()
  expect(()=>refundRecovery.parse({...reviewed,evidenceOrigin:'BANK_API'})).toThrow()
 })
 it('GET is scoped to server Session marketplace and never sends a tenant',async()=>{
  const mock=vi.fn().mockResolvedValue({ok:true,json:async()=>reviewed})
  vi.stubGlobal('fetch',mock)
  const data=await getRefundRecovery(mp,event)
  expect(data.state).toBe('REVIEWED')
  const [url,init]=mock.mock.calls[0]
  expect(url).toContain('/finance/marketplaces/'+mp+'/events/'+event+'/refund-recovery')
  expect(url).not.toContain('tenantId')
  expect(init.credentials).toBe('same-origin')
  expect(init.cache).toBe('no-store')
 })
 it('review mutation uses CSRF with reason only and no receipt assertion',async()=>{
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:reviewed
  }))
  vi.stubGlobal('fetch',mock)
  await decideRefundRecovery(mp,event,'approve',
   'Independently reviewed item quantities and signed amount references')
  const [url,init]=mock.mock.calls[1]
  expect(url.endsWith('/approve')).toBe(true)
  expect(init.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(JSON.parse(init.body)).toEqual({
   reason:'Independently reviewed item quantities and signed amount references'
  })
  expect(init.body).not.toContain('inventoryReceiptVerified')
 })
})
