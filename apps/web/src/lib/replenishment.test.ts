import {afterEach,describe,expect,it,vi} from 'vitest'
import {createReplenishmentPurchaseDraft,getReplenishmentSuggestions} from './replenishment'

afterEach(()=>vi.unstubAllGlobals())
const marketplace='33333333-3333-4333-8333-333333333333'
const orderId='44444444-4444-4444-8444-444444444444'
const supplier='55555555-5555-4555-8555-555555555555'
const actor='66666666-6666-4666-8666-666666666666'
const product='77777777-7777-4777-8777-777777777777'
const evidence='88888888-8888-4888-8888-888888888888'

describe('replenishment-to-purchase browser contract',()=>{
 it('preserves null evidence on unobserved stock rather than inventing zero',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   policy:{marketplaceId:marketplace,sellerSku:'S-1',reorderPoint:3,
    targetStock:20,leadTimeDays:14,updatedAt:'2026-10-31T07:00:00Z'},
   observationId:null,observationQuality:null,fulfillable:null,reserved:null,
   observedAt:null,state:'NO_OBSERVATION',suggestedQuantity:null
  }]),{status:200})))
  const result=await getReplenishmentSuggestions(marketplace)
  expect(result[0].observationId).toBeNull()
  expect(result[0].suggestedQuantity).toBeNull()
 })
 it('submits a single explicit draft action with the seen evidence and CSRF',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'test-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({
    order:{id:orderId,orderNumber:'PO-TEST',supplierId:supplier,status:'DRAFT',
     currency:'USD',createdBy:actor,approvedBy:null,notes:null,
     updatedAt:'2026-10-31T08:00:00Z'},
    lines:[{id:'99999999-9999-4999-8999-999999999999',productSkuId:product,
     orderedQuantity:17,receivedQuantity:0,unitCost:'4.50'}]
   }),{status:201})
  }))
  const payload={
   requestKey:'11111111-1111-4111-8111-111111111111',sellerSku:'S-1',supplierId:supplier,
   currency:'USD',unitCost:'4.50',expectedPolicyUpdatedAt:'2026-10-31T07:00:00Z',
   expectedObservationId:evidence,expectedQuantity:17
  }
  const created=await createReplenishmentPurchaseDraft(marketplace,payload)
  expect(created.order.status).toBe('DRAFT')
  const post=requests.find(r=>r.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/replenishment/marketplaces/'+marketplace+'/purchase-drafts')
  expect(JSON.parse(String(post?.options?.body))).toEqual(payload)
  expect(post?.options?.headers).toMatchObject({'X-CSRF-TOKEN':'test-csrf-token'})
 })
})
