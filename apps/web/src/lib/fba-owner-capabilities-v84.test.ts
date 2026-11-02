import {afterEach,describe,expect,it,vi} from 'vitest'
import {exceptionProfileSchema,getMyExceptionGrants,
 getExceptionOwners,saveExceptionOwnerGrants} from './exception-capabilities-v84'
import {batchDetailSchema,getExceptionBatchDetail} from './fba-purchase-exceptions'
afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const id='22222222-2222-4222-8222-222222222222'
const line='33333333-3333-4333-8333-333333333333'
const key='44444444-4444-4444-8444-444444444444'
const grants={view:true,assign:false,review:true,resolve:false,
 configure:false,manage:false}
const profile={ownerId:id,displayName:'Active Owner',
 grants,version:2,customized:true}
const detail={batch:{requestKey:key,actionType:'ACKNOWLEDGE',actorId:id,
 affectedCount:1,createdAt:'2026-11-02T12:00:00Z'},
 lineageComplete:true,linkedCount:1,linkedActions:[{
 lineId:line,shipmentReference:'LOCAL-ONLY',sellerSku:'SKU-X',
 action:'ACKNOWLEDGE',actorId:id,newState:'ACKNOWLEDGED',
 occurredAt:'2026-11-02T12:00:00Z'}]}
describe('V84 scoped Owner permissions and auditable batch identity',()=>{
 it('validates limited grants and true FK-linked row evidence',()=>{
  expect(exceptionProfileSchema.parse(profile).grants.assign).toBe(false)
  expect(exceptionProfileSchema.safeParse({...profile,grants:{
   ...grants,assign:'yes'}}).success).toBe(false)
  expect(batchDetailSchema.parse(detail).linkedActions[0].lineId).toBe(line)
  expect(batchDetailSchema.safeParse({...detail,linkedActions:[{
   ...detail.linkedActions[0],action:'AMAZON_WRITE'}]}).success).toBe(false)
  expect(batchDetailSchema.parse({...detail,lineageComplete:false,
   linkedCount:0,linkedActions:[]}).lineageComplete).toBe(false)
 })
 it('reads capability and exact batch lineage from tenant-free same origin',async()=>{
  const seen:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   seen.push({url:String(url),options})
   let data=String(url).endsWith('/me')?profile:
    String(url).endsWith('/owners')?[profile]:detail
   return new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}})
  }))
  expect((await getMyExceptionGrants(mp)).version).toBe(2)
  expect(await getExceptionOwners(mp)).toHaveLength(1)
  expect((await getExceptionBatchDetail(mp,key)).linkedCount).toBe(1)
  expect(seen.every(x=>x.options?.method==='GET')).toBe(true)
  expect(seen.every(x=>!x.url.includes('tenantId'))).toBe(true)
 })
 it('submits versioned grants only with CSRF and server-provided Owner identity',async()=>{
  const seen:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   seen.push({url:String(url),options})
   if(String(url).endsWith('/auth/csrf'))
    return new Response(JSON.stringify({token:'v84-csrf-must-be-long',headerName:'X-CSRF-TOKEN'}),
     {headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify(profile),{headers:{'Content-Type':'application/json'}})
  }))
  await saveExceptionOwnerGrants(mp,id,{grants,expectedVersion:1,
   reason:'V84 limited review assignment confirmed locally'})
  const req=seen.at(-1)!
  expect(req.url).toContain('/permissions/owners/'+id)
  expect(new Headers(req.options?.headers).get('X-CSRF-TOKEN')).toBe('v84-csrf-must-be-long')
  expect(JSON.parse(String(req.options?.body))).toMatchObject({
   grants:{resolve:false,manage:false},expectedVersion:1
  })
 })
})
