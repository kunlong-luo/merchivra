import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 workbenchSchema,getExceptionBoard,postExceptionAction,bulkAcknowledge,
 saveReminderPolicy,getExceptionHistory
} from './fba-purchase-exceptions'

const mp='11111111-1111-4111-8111-111111111111'
const line='33333333-3333-4333-8333-333333333333'
const item={
 lineId:line,shipmentId:'22222222-2222-4222-8222-222222222222',
 shipmentReference:'LOCAL-V77',sellerSku:'V77-MOCK-SKU',
 findingCode:'UNLINKED_DRAFT',severity:'INFO',fingerprint:'a'.repeat(64),
 state:'OPEN',assigneeId:null,dueAt:null,reminder:'NONE',
 stale:false,version:0,updatedAt:null
}
const board={
 truncated:false,shipmentScope:1,amazonVerified:false,fbaStockCredited:false,
 policy:{enabled:true,leadHours:24,defaultDueHours:72,version:0},
 assignees:[],items:[item]
}
afterEach(()=>vi.unstubAllGlobals())
describe('V77 local workflow / Zod and shared session transport',()=>{
 it('does not turn human triage into Amazon verified inventory',()=>{
  expect(workbenchSchema.parse(board).amazonVerified).toBe(false)
  expect(workbenchSchema.safeParse({...board,amazonVerified:true}).success).toBe(false)
  expect(workbenchSchema.safeParse({...board,
   items:[{...item,severity:'AMAZON_DELIVERED'}]}).success).toBe(false)
  expect(workbenchSchema.safeParse({...board,
   items:[{...item,dueAt:'not a date'}]}).success).toBe(false)
  expect(workbenchSchema.safeParse({...board,
   items:Array(501).fill(item)}).success).toBe(false)
  expect(workbenchSchema.safeParse({...board,
   items:[{...item,version:-1}]}).success).toBe(false)
 })
 it('reads bounded session-scoped workbench as GET',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   return new Response(JSON.stringify(board),{
    headers:{'Content-Type':'application/json'}})
  }))
  expect((await getExceptionBoard(mp)).items[0].state).toBe('OPEN')
  expect(requests[0].url).toBe(
   '/api/v1/fba/marketplaces/'+mp+'/purchase-reconciliation/workbench')
  expect(requests[0].options).toMatchObject({method:'GET',
   credentials:'same-origin',cache:'no-store'})
  expect(requests[0].url).not.toContain('tenantId')
 })
 it('POST mutation and bulk use CSRF and stable idempotency request key',async()=>{
  const reqs:Array<{url:string;options?:RequestInit}>=[]
  const response=(data:unknown)=>new Response(JSON.stringify(data),{
   headers:{'Content-Type':'application/json'}})
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   reqs.push({url,options})
   if(url==='/api/v1/auth/csrf')
    return response({token:'csrf-mock-long-token',headerName:'X-CSRF-TOKEN'})
   if(url.endsWith('/bulk-acknowledge'))
    return response({requestKey:'44444444-4444-4444-8444-444444444444',
     acknowledgedCount:1})
   return response({...item,state:'ACKNOWLEDGED',version:1})
  }))
  await postExceptionAction(mp,line,{action:'ACKNOWLEDGE',
   expectedVersion:0,expectedFingerprint:item.fingerprint,
   assigneeId:null,dueAt:null,note:'V77 local exception acknowledged'})
  expect(reqs[1].url).toContain('/cases/'+line+'/actions')
  expect(reqs[1].options?.method).toBe('POST')
  expect(new Headers(reqs[1].options?.headers).get('X-CSRF-TOKEN'))
   .toBe('csrf-mock-long-token')
  expect(JSON.parse(String(reqs[1].options?.body)).expectedVersion).toBe(0)
  await bulkAcknowledge(mp,'44444444-4444-4444-8444-444444444444',
   [{lineId:line,expectedVersion:0,expectedFingerprint:item.fingerprint}],
   'V77 local bulk acknowledgement note')
  expect(reqs[3].url).toContain('bulk-acknowledge')
  expect(JSON.parse(String(reqs[3].options?.body)).requestKey)
   .toBe('44444444-4444-4444-8444-444444444444')
 })
 it('policy change is scoped to marketplace, records expected version',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url==='/api/v1/auth/csrf')
    return new Response(JSON.stringify({token:'csrf-test-012345',
     headerName:'X-CSRF-TOKEN'}))
   return new Response(JSON.stringify({...board.policy,version:1}),{headers:{'Content-Type':'application/json'}})
  }))
  await saveReminderPolicy(mp,{enabled:false,leadHours:2,defaultDueHours:4,
   expectedVersion:0,note:'V77 local dashboard reminder changes'})
  expect(requests[1].url).toContain('/workbench/policy')
  expect(JSON.parse(String(requests[1].options?.body)).enabled).toBe(false)
 })
 it('refuses off-marketplace history lookup in the same-origin client',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(getExceptionHistory(mp,line)).rejects.toThrow('Marketplace')
 })
})
