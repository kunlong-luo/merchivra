import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchOperationRules,opsRuleSchema,putOperationRule} from './ops-rules'
afterEach(()=>vi.unstubAllGlobals())
const market='33333333-3333-4333-8333-333333333333'
const defaultRow={ruleCode:'LOW_STOCK',enabled:true,lowStockThreshold:3,
 version:0,updatedAt:null}
describe('V69 runtime Orval operations rule schema and CSRF',()=>{
 it('rejects invented codes, invalid threshold and stale versions',()=>{
  expect(opsRuleSchema.safeParse(defaultRow).success).toBe(true)
  expect(opsRuleSchema.safeParse({...defaultRow,ruleCode:'LIVE_ADS_WRITE'}).success).toBe(false)
  expect(opsRuleSchema.safeParse({...defaultRow,lowStockThreshold:-1}).success).toBe(false)
  expect(opsRuleSchema.safeParse({...defaultRow,version:-1}).success).toBe(false)
 })
 it('refuses non-integer low-stock without calling API',async()=>{
  const fetcher=vi.fn()
  vi.stubGlobal('fetch',fetcher)
  await expect(putOperationRule(market,defaultRow as never,true,10001))
   .rejects.toThrow('0 至 10000')
  expect(fetcher).not.toHaveBeenCalled()
 })
 it('uses generated same-origin endpoint and CSRF but never tenant id',async()=>{
  const seen:Array<{url:string,init?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL,init?:RequestInit)=>{
   seen.push({url:String(input),init})
   if(String(input).endsWith('/auth/csrf'))
    return new Response(JSON.stringify({token:'V69-FAKE-LOCAL-TOKEN',headerName:'X-CSRF-TOKEN'}),
      {status:200,headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify({...defaultRow,enabled:false,version:1,
    updatedAt:'2026-11-01T00:00:00Z'}),
    {status:200,headers:{'Content-Type':'application/json'}})
  }))
  const result=await putOperationRule(market,defaultRow as never,false,3)
  expect(result.version).toBe(1)
  expect(seen).toHaveLength(2)
  expect(seen[1].url).toContain('/api/v1/operations/marketplaces/'+market+'/rules/LOW_STOCK')
  expect(seen[1].init?.method).toBe('POST')
  expect(new Headers(seen[1].init?.headers).get('X-CSRF-TOKEN')).toBe('V69-FAKE-LOCAL-TOKEN')
  expect(String(seen[1].init?.body)).toContain('"expectedVersion":0')
  expect(String(seen[1].init?.body)).not.toContain('tenantId')
 })
})
