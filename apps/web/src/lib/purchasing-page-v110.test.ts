import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchPurchaseOrdersPage} from './purchasing'

const id='11111111-1111-4111-8111-111111111111'
const supplier='22222222-2222-4222-8222-222222222222'
const user='33333333-3333-4333-8333-333333333333'
const order={id,orderNumber:'PO-V110-EXAMPLE',supplierId:supplier,status:'APPROVED',
 currency:'USD',createdBy:user,approvedBy:null,notes:null,
 updatedAt:'2026-11-08T09:00:00Z'}

afterEach(()=>vi.unstubAllGlobals())

describe('purchase order server cursor pages',()=>{
 it('includes server-side status/search/cursor; never fetches legacy /orders',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async (url:string,options:RequestInit)=>{
   urls.push(url)
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   return new Response(JSON.stringify({items:[order],nextCursor:'SAFE_NEXT',
    hasMore:true,pageSize:50}),{status:200})
  }))
  const result=await fetchPurchaseOrdersPage({
   limit:50,status:'APPROVED',search:'PO-42',cursor:'SAFE_PREV'})
  expect(result.hasMore).toBe(true)
  expect(result.items[0].id).toBe(id)
  const parsed=new URL(urls[0], 'http://localhost')
  expect(parsed.pathname).toBe('/api/v1/purchasing/orders/page')
  expect(parsed.searchParams.get('status')).toBe('APPROVED')
  expect(parsed.searchParams.get('search')).toBe('PO-42')
  expect(parsed.searchParams.get('cursor')).toBe('SAFE_PREV')
  expect(parsed.searchParams.get('limit')).toBe('50')
 })
 it('requires correctly structured pages instead of interpreting array as all orders',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([order]),{status:200})))
  await expect(fetchPurchaseOrdersPage()).rejects.toThrow()
 })
 it('accepts exact last page with no further cursor',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[order],nextCursor:null,hasMore:false,pageSize:50}),{status:200})))
  const result=await fetchPurchaseOrdersPage()
  expect(result.nextCursor).toBeNull()
  expect(result.items).toHaveLength(1)
 })
})
