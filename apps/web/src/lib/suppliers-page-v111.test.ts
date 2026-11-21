import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSuppliersPage} from './purchasing'

const supplier={id:'11111111-1111-4111-8111-111111111111',
 code:'SUP-0201',name:'Supplier 201',status:'ACTIVE'}

afterEach(()=>vi.unstubAllGlobals())

describe('V111 full supplier directory page contract',()=>{
 it('requests server ACTIVE prefix and cursor instead of legacy first-200 array',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async (url:string,options:RequestInit)=>{
   urls.push(url)
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   return new Response(JSON.stringify({items:[supplier],nextCursor:'NEXT',
    hasMore:true,pageSize:50}),{status:200})
  }))
  const result=await fetchSuppliersPage({
   limit:50,status:'ACTIVE',prefix:'SUP-02',cursor:'PREV'})
  expect(result.items[0].code).toBe('SUP-0201')
  expect(result.hasMore).toBe(true)
  const url=new URL(urls[0],'http://localhost')
  expect(url.pathname).toBe('/api/v1/purchasing/suppliers/page')
  expect(url.searchParams.get('status')).toBe('ACTIVE')
  expect(url.searchParams.get('prefix')).toBe('SUP-02')
  expect(url.searchParams.get('cursor')).toBe('PREV')
  expect(url.searchParams.get('limit')).toBe('50')
 })
 it('rejects legacy arrays and incoherent cursors',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([supplier]),{status:200})))
  await expect(fetchSuppliersPage()).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[supplier],hasMore:true,nextCursor:null,pageSize:50}),{status:200})))
  await expect(fetchSuppliersPage()).rejects.toThrow()
 })
 it('accepts the last page without inventing a total',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[supplier],nextCursor:null,hasMore:false,pageSize:50}),{status:200})))
  const page=await fetchSuppliersPage()
  expect(page.items).toHaveLength(1)
  expect(page.nextCursor).toBeNull()
 })
})
