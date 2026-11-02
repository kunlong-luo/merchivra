import {afterEach,describe,expect,it,vi} from 'vitest'
import {bulkAssignCases,getOwnerWorkload,ownerWorkloadSchema,
 bulkAssignReceiptSchema} from './fba-purchase-exceptions'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const reviewer='22222222-2222-4222-8222-222222222222'
const line='33333333-3333-4333-8333-333333333333'
const key='44444444-4444-4444-8444-444444444444'

describe('V79 owner load and batch allocation same-origin contract',()=>{
 it('keeps persisted scope, nonnegative counts and non-zero assignment receipt',()=>{
  expect(ownerWorkloadSchema.parse({
   totalCases:3,unassigned:1,inactiveAssignee:0,
   owners:[{userId:reviewer,displayName:'Owner review',open:1,
    acknowledged:1,overdue:1}]
  }).owners[0].overdue).toBe(1)
  expect(ownerWorkloadSchema.safeParse({
   totalCases:1,unassigned:-1,inactiveAssignee:0,owners:[]
  }).success).toBe(false)
  expect(bulkAssignReceiptSchema.safeParse({
   requestKey:key,assigneeId:reviewer,assignedCount:0
  }).success).toBe(false)
 })
 it('uses session-only GET workload and never sends tenantId',async()=>{
  const requests:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   requests.push(String(url))
   return new Response(JSON.stringify({totalCases:0,unassigned:0,
    inactiveAssignee:0,owners:[]}),{headers:{'Content-Type':'application/json'}})
  }))
  const result=await getOwnerWorkload(mp)
  expect(result.owners).toHaveLength(0)
  expect(requests).toEqual([
   '/api/v1/fba/marketplaces/'+mp+'/purchase-reconciliation/workbench/workload'])
  expect(requests[0]).not.toContain('tenantId')
 })
 it('posts explicit assignee, evidence version, request key and CSRF',async()=>{
  const requests:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url:String(url),options})
   if(String(url).endsWith('/auth/csrf'))
    return new Response(JSON.stringify({token:'V79-local-csrf-mock-token-0000',headerName:'X-CSRF-TOKEN'}),
      {headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify({requestKey:key,assigneeId:reviewer,
    assignedCount:1}),{headers:{'Content-Type':'application/json'}})
  }))
  const result=await bulkAssignCases(mp,key,reviewer,
   [{lineId:line,expectedVersion:3,expectedFingerprint:'a'.repeat(64)}],
   'V79 allocate one local review case to owner')
  expect(result.assignedCount).toBe(1)
  const request=requests.at(-1)!
  expect(request.url).toContain('/workbench/bulk-assign')
  expect(request.options?.method).toBe('POST')
  expect(new Headers(request.options?.headers).get('X-CSRF-TOKEN')).toBe('V79-local-csrf-mock-token-0000')
  const body=JSON.parse(String(request.options?.body))
  expect(body).toMatchObject({requestKey:key,assigneeId:reviewer})
  expect(body.lines[0].expectedVersion).toBe(3)
  expect(String(request.options?.body)).not.toContain('tenantId')
 })
})
