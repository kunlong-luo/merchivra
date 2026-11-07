import {afterEach,describe,expect,it,vi} from 'vitest'
import {listBankManualAuditPage} from './bank-manual-review'
import {getSourceReviewAuditPage} from './settlement-source-review'
import {getRefundAuditPage} from './refund-review'

afterEach(()=>vi.unstubAllGlobals())
const marketplace='11111111-1111-4111-8111-111111111111'
const report='22222222-2222-4222-8222-222222222222'
const actor='33333333-3333-4333-8333-333333333333'
const event='44444444-4444-4444-8444-444444444444'
const date='2026-11-07T00:00:00Z'
const bank={action:'REQUESTED',version:131,actorId:actor,
 reason:'V98 mock bank history fixture',occurredAt:date}
const source={action:'REQUESTED',version:133,actorId:actor,
 reason:'V98 mock source history fixture',occurredAt:date,
 sourceState:'SOURCE_AMOUNTS_AGREE_UNVERIFIED'}
const refund={itemId:'V98-MOCK-ITEM-001',action:'CREATED',
 quantity:1,disposition:'UNKNOWN',evidenceReference:'V98-MOCK-EVIDENCE-001',
 revision:125,actorId:actor,timestamp:date}
const envelope=(item:unknown)=>({items:[item],nextCursor:'mock-after',
 hasMore:true,pageSize:50})
function serve(body:unknown){
 const urls:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  expect(options?.credentials).toBe('same-origin')
  expect(options?.cache).toBe('no-store')
  urls.push(url)
  return new Response(JSON.stringify(body),{status:200})
 }))
 return urls
}
describe('V98 immutable finance audit continuation',()=>{
 it('pages maker/checker source audit beyond old 100 entries',async()=>{
  const urls=serve(envelope(source))
  const page=await getSourceReviewAuditPage(marketplace,report,'after-100')
  expect(page.items[0].sourceState).toBe('SOURCE_AMOUNTS_AGREE_UNVERIFIED')
  expect(urls[0]).toContain('/reports/'+report+'/source-review/audit/page?limit=50&cursor=after-100')
 })
 it('pages bank reference audit with no finance confirmation',async()=>{
  const urls=serve(envelope(bank))
  const page=await listBankManualAuditPage(marketplace,report,'older')
  expect(page.items[0].version).toBe(131)
  expect(urls[0]).toContain('/reports/'+report+'/manual-bank-review/audit/page?limit=50&cursor=older')
 })
 it('pages manual refund order item audits without stock writes',async()=>{
  const urls=serve(envelope(refund))
  const page=await getRefundAuditPage(marketplace,event,'older')
  expect(page.items[0].revision).toBe(125)
  expect(urls[0]).toContain('/events/'+event+'/refund-items/audit/page?limit=50&cursor=older')
 })
 it('fails closed on inconsistent cursors and wrong audit actions',async()=>{
  serve({...envelope(refund),hasMore:false})
  await expect(getRefundAuditPage(marketplace,event)).rejects.toThrow()
  serve(envelope({...bank,action:'BANK_PAID'}))
  await expect(listBankManualAuditPage(marketplace,report)).rejects.toThrow()
 })
})
