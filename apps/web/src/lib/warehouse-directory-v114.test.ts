import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchWarehousesPage,fetchWarehouseItem} from './purchasing'
const warehouse={id:'33333333-3333-4333-8333-333333333333',
 code:'V114-WH-0201',name:'Warehouse 201',status:'ACTIVE'}
afterEach(()=>vi.unstubAllGlobals())

describe('V114 local warehouse directory and exact selection',()=>{
 it('requests server ACTIVE prefix/next cursor with same-origin no-store',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   urls.push(url)
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   return new Response(JSON.stringify({items:[warehouse],
    nextCursor:'NEXT',hasMore:true,pageSize:50}),{status:200})
  }))
  const result=await fetchWarehousesPage({status:'ACTIVE',prefix:'V114-WH-02',
   cursor:'PREV',limit:50})
  expect(result.items[0].code).toBe('V114-WH-0201')
  const url=new URL(urls[0],'http://localhost')
  expect(url.pathname).toBe('/api/v1/purchasing/warehouses/page')
  expect(url.searchParams.get('status')).toBe('ACTIVE')
  expect(url.searchParams.get('prefix')).toBe('V114-WH-02')
  expect(url.searchParams.get('cursor')).toBe('PREV')
  expect(url.searchParams.get('limit')).toBe('50')
 })
 it('reads exact selected warehouse instead of a truncated list',async()=>{
  let url=''
  vi.stubGlobal('fetch',vi.fn(async(u:string)=>{
   url=u
   return new Response(JSON.stringify(warehouse),{status:200})
  }))
  expect((await fetchWarehouseItem(warehouse.id)).status).toBe('ACTIVE')
  const dest=new URL(url,'http://localhost')
  expect(dest.pathname).toBe('/api/v1/purchasing/warehouses/item')
  expect(dest.searchParams.get('warehouseId')).toBe(warehouse.id)
 })
 it('rejects old arrays and impossible next cursors',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(
   JSON.stringify([warehouse]),{status:200})))
  await expect(fetchWarehousesPage()).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[warehouse],nextCursor:null,hasMore:true,pageSize:50}),{status:200})))
  await expect(fetchWarehousesPage()).rejects.toThrow()
 })
 it('treats inaccessible exact warehouse as failure, never selectable',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('{}',{status:404})))
  await expect(fetchWarehouseItem(warehouse.id)).rejects.toThrow()
 })
})
