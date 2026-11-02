import {afterEach,describe,expect,it,vi} from 'vitest'
import {caseMetricsSchema,getExceptionMetrics} from './fba-purchase-exceptions'
afterEach(()=>vi.unstubAllGlobals())
const marketplace='33333333-3333-4333-8333-333333333333'
describe('V78 persisted local FBA exception SLA',()=>{
 it('does not present absent completion samples as a zero-hour SLA',()=>{
  expect(caseMetricsSchema.parse({
   total:2,open:1,acknowledged:1,resolved:0,overdue:1,
   meanResolutionHours:null
  }).meanResolutionHours).toBeNull()
  expect(caseMetricsSchema.safeParse({
   total:1,open:1,acknowledged:0,resolved:0,overdue:0,
   meanResolutionHours:-3
  }).success).toBe(false)
 })
 it('uses owner-session same-origin GET without trusting a browser tenant',async()=>{
  const requests:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{
   requests.push(String(input))
   return new Response(JSON.stringify({
    total:3,open:1,acknowledged:1,resolved:1,overdue:1,
    meanResolutionHours:2.5
   }),{status:200,headers:{'Content-Type':'application/json'}})
  }))
  const result=await getExceptionMetrics(marketplace)
  expect(result.meanResolutionHours).toBe(2.5)
  expect(requests).toHaveLength(1)
  expect(requests[0]).toContain('/fba/marketplaces/'+marketplace+'/purchase-reconciliation/workbench/metrics')
  expect(requests[0]).not.toContain('tenantId')
 })
})
