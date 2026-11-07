import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchFindingsPage} from './operations'
import {fetchLotExceptionsPage} from './lot-exceptions'

afterEach(()=>vi.unstubAllGlobals())
const id='11111111-1111-4111-8111-111111111111'
const mp='22222222-2222-4222-8222-222222222222'
const actor='33333333-3333-4333-8333-333333333333'
const row={id,marketplaceId:mp,ruleCode:'INVENTORY_UNKNOWN',
 sourceKey:'V95-OPS-150',title:'Mock ops finding',severity:'WARNING',
 state:'OPEN',dataOrigin:'MOCK',lastSeenAt:'2026-11-07T00:00:00Z',
 assignedTo:null,snoozedUntil:null}
const resolution={id,resolutionType:'RELEASED_SURPLUS_BIND',
 countReviewId:id,transferActionId:null,sourceReceiptId:id,positionId:id,
 warehouseId:mp,productSkuId:id,expectedBinId:mp,expectedPositionQuantity:1,
 expectedOnHand:0,adjustmentQuantity:1,evidenceReference:'V95-MOCK',
 reason:'synthetic evidence only',status:'PENDING_REVIEW',proposedBy:actor,
 reviewedBy:null,reviewReason:null,operationId:null}
function mockFetch(body:unknown){
 const calls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  calls.push(url)
  expect(options?.credentials).toBe('same-origin')
  expect(options?.cache).toBe('no-store')
  expect(options?.method===undefined||options?.method==='GET').toBe(true)
  return new Response(JSON.stringify(body),{status:200})
 }))
 return calls
}
const page=(item:unknown)=>({items:[item],nextCursor:'NEXT',hasMore:true,pageSize:50})
describe('V95 complete actionable cursor pages',()=>{
 it('navigates tenant-scoped operations beyond legacy 100 and retains provenance',async()=>{
  const urls=mockFetch(page(row))
  const response=await fetchFindingsPage('ACTIONABLE','after-100')
  expect(response.items[0].sourceKey).toBe('V95-OPS-150')
  expect(response.hasMore).toBe(true)
  expect(urls[0]).toContain('/operations/findings/page?filter=ACTIONABLE&limit=50&cursor=after-100')
 })
 it('navigates second-owner lot approvals beyond legacy 200',async()=>{
  const urls=mockFetch(page(resolution))
  const response=await fetchLotExceptionsPage(mp,'PENDING','after-200')
  expect(response.items[0].status).toBe('PENDING_REVIEW')
  expect(urls[0]).toContain('/purchasing/lot-exceptions/warehouses/'+mp+
   '/page?filter=PENDING&limit=50&cursor=after-200')
 })
 it('rejects inconsistent cursor and state responses instead of silently truncating',async()=>{
  mockFetch({...page(row),hasMore:false})
  await expect(fetchFindingsPage('ACTIVE')).rejects.toThrow()
  mockFetch({...page(resolution),items:[{...resolution,status:'INVALID'}]})
  await expect(fetchLotExceptionsPage(mp,'ALL')).rejects.toThrow()
 })
})
