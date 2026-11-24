import {afterEach,describe,expect,it,vi} from 'vitest'
import {QueryClient} from '@tanstack/react-query'
import {purgeBusinessSessionCache} from './session-query-cache'
import {getReceiptCostIntegrityV217,receiptCostIntegrityV217,
 validPurchaseReceiptId} from './receipt-cost-integrity-v217'

const mp='11111111-1111-4111-8111-111111111111'
const receipt='22222222-2222-4222-8222-222222222222'
const source='33333333-3333-4333-8333-333333333333'
const plan='44444444-4444-4444-8444-444444444444'
const base={
 marketplaceId:mp,purchaseReceiptId:receipt,
 ruleVersion:'V216_RECEIPT_V112_V91_V92_V165_READ_GATE_V1',
 sourceBasis:'V112_RECEIPT_EXACT_V91_V92_SOURCE_AND_V165_LOCAL_COLLISION_UNVERIFIED',
 localPlanCount:1,plans:[{
  planId:plan,sourceId:source,state:'APPROVED_UNVERIFIED',
  originalCurrency:'USD',localReceiptCandidateAmount:'40.000000',
  localAllReceiptsCandidateAmount:'95.000000',globalReceiptCount:2,
  crossSourceIdentitySignalCount:0,sharedSettlementLineCount:0,
  integrityState:'LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED',
  blockerCodes:[],independentlyVerifiedLandedCost:false,
  actualLandedCost:null
 }],
 blockerCodes:[
  'V112_LOCAL_MANUAL_PROPOSAL_IS_NOT_EXTERNAL_LANDED_COST',
  'G1_CAPITALIZATION_FX_AND_REFUND_POLICY_NOT_APPROVED',
  'PHYSICAL_SALE_RETURN_ORIGINAL_LOT_NOT_EXTERNALLY_VERIFIED',
  'SOURCE_COLLISION_ABSENCE_DOES_NOT_PROVE_COMPLETE_COVERAGE'],
 completeExternalCostCoverage:false,receiptLandedCostVerified:false,
 actualBatchCogsVerified:false,actualBatchCogs:null,
 postingAllowed:false,realizedNetProfit:null
}

afterEach(()=>vi.unstubAllGlobals())
describe('V217 read-only original-receipt costs remain internal metadata',()=>{
 it('accepts exact backend V216 schema with reviewed but unverified local cost',()=>{
  const model=receiptCostIntegrityV217.parse(base)
  expect(model.localPlanCount).toBe(1)
  expect(model.plans[0].localReceiptCandidateAmount).toBe('40.000000')
  expect(model.receiptLandedCostVerified).toBe(false)
  expect(model.actualBatchCogs).toBeNull()
  expect(model.realizedNetProfit).toBeNull()
 })

 it.each([
  'completeExternalCostCoverage','receiptLandedCostVerified',
  'actualBatchCogsVerified','postingAllowed'
 ])('refuses server-side promotion to actual financial proof: %s',key=>{
  expect(()=>receiptCostIntegrityV217.parse({...base,[key]:true})).toThrow()
 })
 it('refuses posted COGS and realized net profit',()=>{
  expect(()=>receiptCostIntegrityV217.parse({
   ...base,actualBatchCogs:'40.000000'})).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({
   ...base,realizedNetProfit:'-10.500000'})).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({
   ...base,plans:[{...base.plans[0],independentlyVerifiedLandedCost:true}]
  })).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({
   ...base,plans:[{...base.plans[0],actualLandedCost:'25.0'}]
  })).toThrow()
 })

 it('rejects incomplete, duplicate and unbounded source scopes',()=>{
  expect(()=>receiptCostIntegrityV217.parse({...base,localPlanCount:2})).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({...base,localPlanCount:2,
   plans:[base.plans[0],{...base.plans[0],planId:
    '55555555-5555-4555-8555-555555555555'}]})).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({...base,localPlanCount:51,
   plans:Array.from({length:51},(_,i)=>({
    ...base.plans[0],planId:'00000000-0000-4000-8000-'+i.toString(16).padStart(12,'0'),
    sourceId:'00000000-0000-4000-9000-'+i.toString(16).padStart(12,'0')
   }))})).toThrow()
 })

 it('checks exact six-place original currency and cannot overrun local total',()=>{
  expect(()=>receiptCostIntegrityV217.parse({...base,plans:[{
   ...base.plans[0],localReceiptCandidateAmount:'95.000001',
   localAllReceiptsCandidateAmount:'95.000000'
  }]})).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({...base,plans:[{
   ...base.plans[0],originalCurrency:'US $'
  }]})).toThrow()
  expect(()=>receiptCostIntegrityV217.parse({...base,plans:[{
   ...base.plans[0],localReceiptCandidateAmount:'1.000000001'
  }]})).toThrow()
 })

 it('treats absent local manual proposals as UNKNOWN not zero',()=>{
  expect(()=>receiptCostIntegrityV217.parse({
   ...base,localPlanCount:0,plans:[],
   blockerCodes:base.blockerCodes
  })).toThrow()
  const empty=receiptCostIntegrityV217.parse({
   ...base,localPlanCount:0,plans:[],
   blockerCodes:[...base.blockerCodes,
    'NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO']
  })
  expect(empty.realizedNetProfit).toBeNull()
 })
 it('validates canonical receipt UUID before triggering any HTTP request',async()=>{
  const spy=vi.fn()
  vi.stubGlobal('fetch',spy)
  expect(validPurchaseReceiptId('not-a-receipt')).toBe(false)
  expect(validPurchaseReceiptId(receipt)).toBe(true)
  await expect(getReceiptCostIntegrityV217(mp,'bad-receipt')).rejects.toThrow()
  expect(spy).not.toHaveBeenCalled()
 })
 it('sends only scoped, same-origin no-store GET after an explicit valid selection',async()=>{
  const spy=vi.fn(async (_url:RequestInfo|URL,_options?:RequestInit)=>
   new Response(JSON.stringify(base),{status:200}))
  vi.stubGlobal('fetch',spy)
  const returned=await getReceiptCostIntegrityV217(mp,receipt)
  expect(returned.marketplaceId).toBe(mp)
  expect(spy).toHaveBeenCalledOnce()
  const [url,options]=spy.mock.calls[0]
  expect(url).toBe('/api/v1/finance/marketplaces/'+mp+
   '/purchase-receipts/'+receipt+'/manual-cost-integrity')
  expect(options).toMatchObject({method:'GET',credentials:'same-origin',
   cache:'no-store'})
  expect(url).not.toContain('tenantId')
 })
 it('forwards React Query abort signal, never escaping session scope',async()=>{
  const abort=new AbortController()
  const spy=vi.fn(async (_url:RequestInfo|URL,_opts?:RequestInit)=>
   new Response(JSON.stringify(base),{status:200}))
  vi.stubGlobal('fetch',spy)
  await getReceiptCostIntegrityV217(mp,receipt,abort.signal)
  expect(spy.mock.calls[0][1]?.signal).toBe(abort.signal)
 })
 it('V219 aborts V216 GET and erases in-flight finance evidence at Session boundary',async()=>{
  const client=new QueryClient({defaultOptions:{queries:{retry:false}}})
  const queryKey=['finance','tenant-A',mp,'v218-receipt-cost-integrity',receipt,1]
  const onAbort=vi.fn()
  const request=vi.fn((_url:RequestInfo|URL,init?:RequestInit)=>
   new Promise<Response>((_resolve,reject)=>{
    init?.signal?.addEventListener('abort',()=>{
     onAbort()
     reject(new DOMException('Request aborted on identity boundary','AbortError'))
    },{once:true})
   }))
  vi.stubGlobal('fetch',request)
  const pending=client.fetchQuery({queryKey,
   queryFn:({signal})=>getReceiptCostIntegrityV217(mp,receipt,signal)})
  await Promise.resolve()
  expect(request).toHaveBeenCalledOnce()
  await purgeBusinessSessionCache(client)
  await pending.catch(()=>{})
  expect(onAbort).toHaveBeenCalledOnce()
  expect(client.getQueryData(queryKey)).toBeUndefined()
  expect(client.getQueryCache().find({queryKey})).toBeUndefined()
  client.clear()
 })
 it('rejects data from another Marketplace or receipt',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   ...base,purchaseReceiptId:'66666666-6666-4666-8666-666666666666'
  }),{status:200})))
  await expect(getReceiptCostIntegrityV217(mp,receipt)).rejects.toThrow(
   '不一致')
 })
 it.each([401,403,404,409,500])(
  'never renders a partial cost or cached value after HTTP %i',async status=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status})))
  await expect(getReceiptCostIntegrityV217(mp,receipt)).rejects.toThrow()
 })
})
