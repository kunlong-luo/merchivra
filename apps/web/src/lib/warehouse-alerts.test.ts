import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchWarehouseAdvice,scanWarehouseAlerts,saveWarehousePolicy,
 changeWarehouseFinding} from './warehouse-alerts'
afterEach(()=>vi.unstubAllGlobals())
const warehouse='11111111-1111-4111-8111-111111111111'
const sku='22222222-2222-4222-8222-222222222222'
const finding='33333333-3333-4333-8333-333333333333'
const scanId='44444444-4444-4444-8444-444444444444'

describe('V41 local warehouse advisory contract',()=>{
 it('preserves missing ledger as unknown and never invents a replenishment quantity',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
   policy:{warehouseId:warehouse,productSkuId:sku,reorderPoint:5,targetStock:20,
    updatedAt:'2026-10-31T09:00:00Z'},
   internalSku:'LOCAL-SKU',onHandQuantity:null,state:'NO_LEDGER',
   suggestedQuantity:null,approvedUnreceivedQuantity:12
  }]),{status:200})))
  const advice=await fetchWarehouseAdvice(warehouse)
  expect(advice[0].onHandQuantity).toBeNull()
  expect(advice[0].suggestedQuantity).toBeNull()
  expect(advice[0].approvedUnreceivedQuantity).toBe(12)
 })
 it('saves a tenant-free SKU policy over CSRF-protected POST',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'test-local-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({
    warehouseId:warehouse,productSkuId:sku,reorderPoint:5,targetStock:20,
    updatedAt:'2026-10-31T09:00:00Z'
   }),{status:200})
  }))
  await saveWarehousePolicy(warehouse,sku,5,20)
  const post=requests.find(r=>r.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/warehouse-alerts/'+warehouse+'/policies')
  expect(JSON.parse(String(post?.options?.body))).toEqual({
   productSkuId:sku,reorderPoint:5,targetStock:20
  })
  expect(post?.options?.headers).toMatchObject({'X-CSRF-TOKEN':'test-local-csrf-token'})
 })
 it('manual scans and state changes stay explicit owner actions',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'test-local-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   if(url.endsWith('/scan'))return new Response(JSON.stringify({
    id:scanId,triggerType:'MANUAL',matchedCount:2,clearedCount:1
   }),{status:200})
   return new Response(JSON.stringify({id:finding,state:'ACKNOWLEDGED'}),{status:200})
  }))
  expect((await scanWarehouseAlerts()).matchedCount).toBe(2)
  expect((await changeWarehouseFinding(finding,'ACKNOWLEDGED')).state).toBe('ACKNOWLEDGED')
  const posts=requests.filter(r=>r.options?.method==='POST')
  expect(posts).toHaveLength(2)
  expect(posts[1].url).toContain('/findings/'+finding+'/state')
  expect(JSON.parse(String(posts[1].options?.body))).toEqual({state:'ACKNOWLEDGED'})
  expect(posts[0].options?.headers).toMatchObject({'X-CSRF-TOKEN':'test-local-csrf-token'})
 })
})
