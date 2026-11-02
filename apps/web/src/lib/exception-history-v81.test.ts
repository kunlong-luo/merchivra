import {afterEach,describe,expect,it,vi} from 'vitest'
import {exceptionAuditHistorySchema,getExceptionAuditHistory} from './exception-history-v81'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const owner='22222222-2222-4222-8222-222222222222'
const line='33333333-3333-4333-8333-333333333333'
const days=Array.from({length:30},(_,i)=>{
 const date=new Date(Date.UTC(2026,9,4+i)).toISOString().slice(0,10)
 return {day:date,registered:i===0?1:0,resolvedActions:i===1?1:0,
  reopenActions:i===2?1:0,assignedActions:0,acknowledgementActions:0,
  evidenceRefreshActions:i===2?1:0}
})
const result={
 marketplaceId:mp,fromDay:days[0].day,throughDay:days.at(-1)!.day,
 dayCount:30,timezone:'UTC',origin:'LOCAL_AUDIT',amazonVerified:false,
 fbaStockCredited:false,
 summary:{registered:1,auditedActions:3,resolvedActions:1,
  reopenActions:1,affectedByReopen:1,evidenceRefreshActions:1},
 daily:days,actors:[{userId:owner,displayName:'Owner',
  resolvedActions:1,reopenedActions:1,assignedActions:0,
  acknowledgedActions:0,meanFirstResolutionHours:1.5}],
 actorsTruncated:false,recurrences:[{lineId:line,shipmentId:owner,
  shipmentReference:'LOCAL-FBA',sellerSku:'SKU-X',state:'OPEN',
  reopenActions:1,evidenceRefreshActions:1,lastReopenedAt:'2026-10-06T12:00:00Z'}],
 recurrencesTruncated:false
}
describe('V81 read-only local audit timeline',()=>{
 it('validates UTC horizon, immutable provenance, bounded actors and recurrence',()=>{
  expect(exceptionAuditHistorySchema.parse(result).daily).toHaveLength(30)
  expect(exceptionAuditHistorySchema.safeParse({...result,
   origin:'AMAZON_CONFIRMED'}).success).toBe(false)
  expect(exceptionAuditHistorySchema.safeParse({...result,
   amazonVerified:true}).success).toBe(false)
  expect(exceptionAuditHistorySchema.safeParse({...result,
   daily:days.slice(0,29)}).success).toBe(false)
  expect(exceptionAuditHistorySchema.safeParse({...result,
   recurrences:Array(21).fill(result.recurrences[0])}).success).toBe(false)
  expect(exceptionAuditHistorySchema.safeParse({...result,
   actors:[{...result.actors[0],meanFirstResolutionHours:null}]}).success).toBe(true)
  expect(exceptionAuditHistorySchema.safeParse({...result,
   summary:{...result.summary,registered:-1}}).success).toBe(false)
 })
 it('does not post CSRF, tenant ID, owner identity or mutations on history reads',async()=>{
  const requests:Array<{url:string,options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url:String(url),options})
   return new Response(JSON.stringify(result),{headers:{'Content-Type':'application/json'}})
  }))
  const history=await getExceptionAuditHistory(mp)
  expect(history.recurrences[0].lineId).toBe(line)
  expect(requests).toHaveLength(1)
  expect(requests[0].url).toBe('/api/v1/exception-insights/marketplaces/'+mp+'/history')
  expect(requests[0].url).not.toContain('tenantId')
  expect(requests[0].options).toMatchObject({method:'GET',
   credentials:'same-origin',cache:'no-store'})
 })
 it('reports missing/foreign marketplace errors rather than fake zeroes',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:404})))
  await expect(getExceptionAuditHistory(mp)).rejects.toThrow()
 })
})
