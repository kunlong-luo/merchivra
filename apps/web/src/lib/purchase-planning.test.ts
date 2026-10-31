import {afterEach,describe,expect,it,vi} from 'vitest'
import {actOnLocalPurchasePlan,fetchLocalPurchaseRecommendations,
 proposeLocalPurchasePlan,setSupplierSkuRule} from './purchase-planning'
afterEach(()=>vi.unstubAllGlobals())

const warehouse='11111111-1111-4111-8111-111111111111'
const supplier='22222222-2222-4222-8222-222222222222'
const sku='33333333-3333-4333-8333-333333333333'
const movement='44444444-4444-4444-8444-444444444444'
const key='55555555-5555-4555-8555-555555555555'
const actor='66666666-6666-4666-8666-666666666666'
const planId='77777777-7777-4777-8777-777777777777'
const orderId='88888888-8888-4888-8888-888888888888'
const policyTimestamp='2026-10-31T07:00:00Z'
const ruleTimestamp='2026-10-31T07:30:00Z'

describe('V42 supplier purchasing plans',()=>{
 it('preserves missing local movement as nonactionable rather than zero stock',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   warehouseId:warehouse,supplierId:supplier,supplierName:'Supply One',
   productSkuId:sku,internalSku:'SKU-1',sourceMovementId:null,
   policyUpdatedAt:policyTimestamp,ruleUpdatedAt:ruleTimestamp,
   onHandQuantity:null,reorderPoint:5,targetStock:19,
   minimumOrderQuantity:20,packMultiple:12,leadTimeDays:14,
   currency:'USD',unitCost:'2.50',state:'NO_LEDGER',
   suggestedQuantity:null,orderQuantity:null
  }]),{status:200})))
  const rec=await fetchLocalPurchaseRecommendations(warehouse)
  expect(rec).toHaveLength(1)
  expect(rec[0].sourceMovementId).toBeNull()
  expect(rec[0].orderQuantity).toBeNull()
 })
 it('persists MOQ and lead time only via owner CSRF mutation',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-csrf-token-for-testing',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({supplierId:supplier,productSkuId:sku,
    minimumOrderQuantity:20,packMultiple:12,leadTimeDays:14,currency:'USD',
    unitCost:'2.50',updatedAt:ruleTimestamp}),{status:200})
  }))
  const rule=await setSupplierSkuRule(supplier,sku,20,12,14,'USD','2.50')
  expect(rule.packMultiple).toBe(12)
  const post=requests.find(r=>r.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/planning/supplier-rules')
  expect(JSON.parse(String(post?.options?.body))).toEqual({
   supplierId:supplier,productSkuId:sku,minimumOrderQuantity:20,
   packMultiple:12,leadTimeDays:14,currency:'USD',unitCost:'2.50'})
  expect(post?.options?.headers).toMatchObject({
   'X-CSRF-TOKEN':'valid-csrf-token-for-testing'})
 })
 it('sends exact freshness evidence and converts only after separate human action',async()=>{
  const calls:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   calls.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'valid-csrf-token-for-testing',headerName:'X-CSRF-TOKEN'}),{status:200})
   const converted=url.endsWith('/convert')
   return new Response(JSON.stringify({
    id:planId,warehouseId:warehouse,supplierId:supplier,productSkuId:sku,
    sourceMovementId:movement,policyUpdatedAt:policyTimestamp,
    ruleUpdatedAt:ruleTimestamp,onHandQuantity:5,reorderPoint:5,targetStock:19,
    suggestedQuantity:14,minimumOrderQuantity:20,packMultiple:12,
    leadTimeDays:14,orderedQuantity:24,currency:'USD',unitCost:'2.50',
    estimatedArrivalDate:'2026-11-14',
    status:converted?'CONVERTED':'PROPOSED',
    purchaseOrderId:converted?orderId:null,createdBy:actor,
    createdAt:'2026-10-31T08:00:00Z'
   }),{status:converted?200:201})
  }))
  const body={requestKey:key,warehouseId:warehouse,supplierId:supplier,
   productSkuId:sku,expectedLastMovementId:movement,
   expectedPolicyUpdatedAt:policyTimestamp,expectedRuleUpdatedAt:ruleTimestamp,
   expectedOnHand:5,expectedOrderQuantity:24}
  const proposed=await proposeLocalPurchasePlan(body)
  expect(proposed.status).toBe('PROPOSED')
  const converted=await actOnLocalPurchasePlan(proposed.id,'convert')
  expect(converted.purchaseOrderId).toBe(orderId)
  const posts=calls.filter(x=>x.options?.method==='POST')
  expect(posts.map(x=>x.url)).toEqual([
   '/api/v1/purchasing/planning/plans',
   '/api/v1/purchasing/planning/plans/'+planId+'/convert'
  ])
  expect(JSON.parse(String(posts[0].options?.body))).toEqual(body)
  expect(posts[1].options?.body).toBeUndefined()
 })
})
