import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSkusPage,type Sku} from './catalog'
import {visibleInternalSkus,validInternalSkuChoice,
 nextInternalSkuAfter} from './internal-sku-page-v91'

afterEach(()=>vi.unstubAllGlobals())
function sku(i:number,status:'ACTIVE'|'ARCHIVED'='ACTIVE'):Sku{
 return {id:'22222222-2222-4222-8222-'+String(i).padStart(12,'0'),
  internalSku:'V91-INTERNAL-'+String(i).padStart(3,'0'),
  displayName:'V91 fictitious SKU '+i,
  status,updatedAt:'2026-11-04T00:00:00Z'}
}
describe('V91 internal SKU selection >100 is a bounded server lookup',()=>{
 it('finds SKU 130 only from second 100+30 page, not first 100',()=>{
  const first=Array.from({length:100},(_,i)=>sku(i+1))
  const last=Array.from({length:30},(_,i)=>sku(i+101))
  expect(nextInternalSkuAfter(first)).toBe('V91-INTERNAL-100')
  expect(nextInternalSkuAfter(last)).toBeNull()
  expect(validInternalSkuChoice(first,sku(130).id)).toBe(false)
  expect(validInternalSkuChoice(last,sku(130).id)).toBe(true)
  expect(validInternalSkuChoice([],sku(130).id)).toBe(false)
  expect(validInternalSkuChoice(last,sku(100).id)).toBe(false)
  expect(visibleInternalSkus(last).at(-1)?.internalSku).toBe('V91-INTERNAL-130')
 })
 it('refuses archived SKU in active mapping/purchasing/stock selectors',()=>{
  const rows=[sku(1),sku(2,'ARCHIVED')]
  expect(visibleInternalSkus(rows).map(s=>s.id)).toEqual([sku(1).id])
  expect(validInternalSkuChoice(rows,sku(2).id)).toBe(false)
  expect(validInternalSkuChoice(rows,sku(2).id,false)).toBe(true)
  expect(validInternalSkuChoice(rows,'')).toBe(false)
 })
 it('uses canonical Owner-only prefix + after API, never browser tenantId',async()=>{
  const calls:Array<{url:string,options:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   calls.push({url,options})
   return {ok:true,status:200,json:async()=>[sku(130)]}
  }))
  const result=await fetchSkusPage(' V91-INTERNAL- ',
   'V91-INTERNAL-100',100)
  expect(result).toEqual([sku(130)])
  const u=new URL(calls[0].url,'http://localhost')
  expect(u.pathname).toBe('/api/v1/catalog/skus')
  expect(u.searchParams.get('prefix')).toBe('V91-INTERNAL-')
  expect(u.searchParams.get('after')).toBe('V91-INTERNAL-100')
  expect(u.searchParams.get('limit')).toBe('100')
  expect(u.searchParams.has('tenantId')).toBe(false)
  expect(calls[0].options.credentials).toBe('same-origin')
  expect(calls[0].options.cache).toBe('no-store')
 })
 it('disallows a forged 101-row API payload as a 100-item page',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>({
   ok:true,status:200,json:async()=>Array.from({length:101},(_,i)=>sku(i+1))
  })))
  await expect(fetchSkusPage()).rejects.toThrow()
 })
})
