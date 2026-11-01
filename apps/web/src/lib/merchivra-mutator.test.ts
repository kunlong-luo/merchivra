import {afterEach,describe,expect,it,vi} from 'vitest'
import {merchivraFetch} from './merchivra-mutator'

describe('Orval mature-client Session and CSRF transport',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('reads only same-origin application paths without caching',async()=>{
  const get=vi.fn().mockResolvedValue(new Response(JSON.stringify({hello:'world'}),{
   status:200,headers:{'content-type':'application/json'}
  }))
  vi.stubGlobal('fetch',get)
  const result=await merchivraFetch<{data:{hello:string},status:number,headers:Headers}>('/api/v1/ping',{method:'GET'})
  expect(result.data.hello).toBe('world')
  expect(result.status).toBe(200)
  const [url,init]=get.mock.calls[0]
  expect(url).toBe('/api/v1/ping')
  expect(init.credentials).toBe('same-origin')
  expect(init.cache).toBe('no-store')
  expect(init.headers.get('Accept')).toBe('application/json')
  expect(init.headers.has('X-CSRF-TOKEN')).toBe(false)
 })
 it('always rejects external URLs and never makes outbound requests',async()=>{
  const get=vi.fn()
  vi.stubGlobal('fetch',get)
  await expect(merchivraFetch('https://evil.example.test/api/v1/secrets')).rejects.toThrow()
  await expect(merchivraFetch('//evil.example.test/api/v1/secrets')).rejects.toThrow()
  expect(get).not.toHaveBeenCalled()
 })
 it('writes with real server CSRF and never supplies client tenant authority',async()=>{
  const get=vi.fn().mockImplementation(async (url:string)=>{
   return new Response(JSON.stringify(url.endsWith('/auth/csrf')?
     {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:{ok:true}),{
     status:200,headers:{'content-type':'application/json'}
   })
  })
  vi.stubGlobal('fetch',get)
  const payload=JSON.stringify({after:'2026-09-01'})
  await merchivraFetch('/api/v1/finance/marketplaces/mp/profit-reports',{
   method:'POST',body:payload,headers:{'Content-Type':'application/json'}
  })
  expect(get).toHaveBeenCalledTimes(2)
  const [url,init]=get.mock.calls[1]
  expect(url).toContain('/profit-reports')
  expect(init.headers.get('X-CSRF-TOKEN')).toBe('1234567890abcdef')
  expect(init.credentials).toBe('same-origin')
  expect(init.cache).toBe('no-store')
  expect(init.body).toBe(payload)
  expect(init.body).not.toContain('tenantId')
 })
 it('does not parse a no-content success as JSON',async()=>{
  const get=vi.fn().mockResolvedValue(new Response(null,{status:204}))
  vi.stubGlobal('fetch',get)
  const result=await merchivraFetch<{data:undefined,status:number}>('/api/v1/ping')
  expect(result.data).toBeUndefined()
  expect(result.status).toBe(204)
 })
})
