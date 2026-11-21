import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchWarehousesPage,fetchWarehouseItem} from './purchasing'

const warehouse={id:'11111111-1111-4111-8111-111111111111',
 code:'WH-0201',name:'Warehouse 201',status:'ACTIVE'}
afterEach(()=>vi.unstubAllGlobals())

describe('V114 all-warehouse directory API contract',()=>{
 it('uses server ACTIVE prefix, cursor, no-store and authenticated same-origin',async()=>{
  const seen:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   seen.push(url)
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   return new Response(JSON.stringify({items:[warehouse],nextCursor:'NEXT',
    hasMore:true,pageSize:50}),{status:200})
  }))
  const page=await fetchWarehousesPage({
   limit:50,status:'ACTIVE',prefix:'WH-02',cursor:'PREV'})
  expect(page.items[0].code).toBe('WH-0201')
  const url=new URL(seen[0],'http://localhost')
  expect(url.pathname).toBe('/api/v1/purchasing/warehouses/page')
  expect(url.searchParams.get('prefix')).toBe('WH-02')
  expect(url.searchParams.get('status')).toBe('ACTIVE')
  expect(url.searchParams.get('cursor')).toBe('PREV')
 })
 it('exact lookup validates a warehouse after selecting outside first 200',async()=>{
  let url=''
  vi.stubGlobal('fetch',vi.fn(async(u:string)=>{
   url=u;return new Response(JSON.stringify(warehouse),{status:200})
  }))
  const item=await fetchWarehouseItem(warehouse.id)
  expect(item).toEqual(warehouse)
  const params=new URL(url,'http://localhost')
  expect(params.pathname).toBe('/api/v1/purchasing/warehouses/item')
  expect(params.searchParams.get('warehouseId')).toBe(warehouse.id)
 })
 it('rejects legacy 200-row array and conflicting pagination contract',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([warehouse]),{status:200})))
  await expect(fetchWarehousesPage()).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[warehouse],hasMore:true,nextCursor:null,pageSize:50}),{status:200})))
  await expect(fetchWarehousesPage()).rejects.toThrow()
 })
 it('does not silently accept invalid or foreign exact lookup',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('{}',{status:404})))
  await expect(fetchWarehouseItem(warehouse.id)).rejects.toThrow()
 })
})
