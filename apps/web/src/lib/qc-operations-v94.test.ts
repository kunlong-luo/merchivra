import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchQualityReturnsPage,fetchQualityReturnSummary,fetchQualityFindingsPage}
 from './purchase-quality'

afterEach(()=>vi.unstubAllGlobals())
const inspection='11111111-1111-4111-8111-111111111111'
const ret='22222222-2222-4222-8222-222222222222'
const actor='33333333-3333-4333-8333-333333333333'
const finding='44444444-4444-4444-8444-444444444444'
const receipt='55555555-5555-4555-8555-555555555555'
const returnRow={id:ret,inspectionId:inspection,supplierId:actor,quantity:1,
 reference:'RMA-V94',status:'DRAFT',createdBy:actor,createdAt:'2026-11-07T08:00:00Z',
 dispatchedAt:null}
const findingRow={id:finding,inspectionId:inspection,receiptId:receipt,
 ruleCode:'QC_REJECTED_PENDING',state:'OPEN',lastSeenAt:'2026-11-07T08:00:00Z'}
const page=(item:unknown)=>({items:[item],pageSize:50,hasMore:true,nextCursor:'next'})
function serve(data:unknown){
 const urls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  urls.push(url)
  expect(options?.method).toBe('GET')
  expect(options?.credentials).toBe('same-origin')
  expect(options?.cache).toBe('no-store')
  return new Response(JSON.stringify(data),{status:200})
 }))
 return urls
}
describe('V94 quality workflows never stop at legacy 100-row cap',()=>{
 it('can list DRAFT returns beyond 100 through inspection-scoped cursor',async()=>{
  const urls=serve(page(returnRow))
  const result=await fetchQualityReturnsPage(inspection,'last-100')
  expect(result.items[0].status).toBe('DRAFT')
  expect(result.hasMore).toBe(true)
  expect(urls[0]).toContain('/inspections/'+inspection+'/returns/page?limit=50&cursor=last-100')
 })
 it('uses an all-record allocation summary, never partial current-page totals',async()=>{
  const urls=serve({rejectedUnits:200,draftedUnits:70,dispatchedUnits:35,
   unallocatedUnits:95})
  const summary=await fetchQualityReturnSummary(inspection)
  expect(summary.unallocatedUnits).toBe(95)
  expect(summary.rejectedUnits).toBe(200)
  expect(urls[0]).toContain('/inspections/'+inspection+'/returns/summary')
 })
 it('paginates filtered actionable findings by scope',async()=>{
  const urls=serve(page(findingRow))
  const results=await fetchQualityFindingsPage('ACTIVE','after-100')
  expect(results.items[0].state).toBe('OPEN')
  expect(urls[0]).toContain('/findings/page?limit=50&filter=ACTIVE&cursor=after-100')
 })
 it('fails closed on malformed cursor pages and allocation sums',async()=>{
  serve({...page(returnRow),hasMore:false})
  await expect(fetchQualityReturnsPage(inspection)).rejects.toThrow()
  serve({rejectedUnits:200,draftedUnits:70,dispatchedUnits:35,unallocatedUnits:-1})
  await expect(fetchQualityReturnSummary(inspection)).rejects.toThrow()
 })
})
