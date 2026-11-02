import {afterEach,describe,expect,it,vi} from 'vitest'
import {resolvePreviewSchema,batchActivitySchema,
 preflightBulkResolve,getExceptionBatchActivities} from './fba-purchase-exceptions'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const line='33333333-3333-4333-8333-333333333333'
const actor='22222222-2222-4222-8222-222222222222'
const key='44444444-4444-4444-8444-444444444444'
const selection={lineId:line,expectedVersion:3,expectedFingerprint:'a'.repeat(64)}
const preview={allEligible:false,selectedCount:1,eligibleCount:0,
 amazonVerified:false,fbaStockCredited:false,
 lines:[{lineId:line,shipmentReference:'LOCAL-ONLY',sellerSku:'SKU-A',
  state:'ACKNOWLEDGED',assigneeId:actor,currentVersion:4,
  currentFingerprint:'b'.repeat(64),eligible:false,reasonCode:'VERSION_CONFLICT'}]}
const batch={requestKey:key,actionType:'RESOLVE',actorId:actor,
 affectedCount:2,createdAt:'2026-11-02T12:00:00Z'}
describe('V83 protected read-only per-row batch preflight',()=>{
 it('validates enumerated reasons, current state, bounded response and false Amazon attestation',()=>{
  expect(resolvePreviewSchema.parse(preview).lines[0].reasonCode).toBe('VERSION_CONFLICT')
  expect(resolvePreviewSchema.safeParse({...preview,amazonVerified:true}).success).toBe(false)
  expect(resolvePreviewSchema.safeParse({...preview,
   lines:[{...preview.lines[0],reasonCode:'AUTOMATICALLY_APPROVED'}]}).success).toBe(false)
  expect(resolvePreviewSchema.safeParse({...preview,
   lines:Array(51).fill(preview.lines[0])}).success).toBe(false)
  expect(resolvePreviewSchema.safeParse({...preview,
   lines:[{...preview.lines[0],currentFingerprint:'bad'}]}).success).toBe(false)
  expect(batchActivitySchema.parse(batch).actionType).toBe('RESOLVE')
  expect(batchActivitySchema.safeParse({...batch,actionType:'AMAZON_WRITE'}).success).toBe(false)
 })
 it('POST preview is explicitly tenant-free, CSRF-protected and returns diagnostics',async()=>{
  const requests:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url:String(url),options})
   if(String(url).endsWith('/auth/csrf'))
    return new Response(JSON.stringify({token:'preflight-csrf-token-long',headerName:'X-CSRF-TOKEN'}),
     {headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify(preview),{headers:{'Content-Type':'application/json'}})
  }))
  const result=await preflightBulkResolve(mp,[selection])
  expect(result.allEligible).toBe(false)
  const last=requests.at(-1)!
  expect(last.url).toContain('/workbench/bulk-resolve/preflight')
  expect(last.url).not.toContain('tenantId')
  expect(new Headers(last.options?.headers).get('X-CSRF-TOKEN')).toBe('preflight-csrf-token-long')
  expect(JSON.parse(String(last.options?.body))).toEqual({lines:[selection]})
 })
 it('GET batch history only reads existing ledger, never posts or fabricates row identities',async()=>{
  const requests:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url:String(url),options})
   return new Response(JSON.stringify([batch]),{headers:{'Content-Type':'application/json'}})
  }))
  expect(await getExceptionBatchActivities(mp)).toEqual([batch])
  expect(requests).toHaveLength(1)
  expect(requests[0].url).toContain('/workbench/batches')
  expect(requests[0].options).toMatchObject({method:'GET',cache:'no-store',
   credentials:'same-origin'})
 })
})
