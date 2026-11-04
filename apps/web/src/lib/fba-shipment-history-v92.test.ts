import {afterEach,describe,expect,it,vi} from 'vitest'
import {getShipmentsPage,inboundShipmentPageSchema} from './fba-inbound'

afterEach(()=>vi.unstubAllGlobals())
const mp='11111111-1111-4111-8111-111111111111'
const shipment=(i:number)=>({
 id:'22222222-2222-4222-8222-'+String(i).padStart(12,'0'),
 reference:'V92-LOCAL-'+String(i).padStart(3,'0'),
 origin:'MOCK',state:'DRAFT',
 createdAt:'2026-11-04T06:00:00Z',dispatchedAt:null,
 reviewStartedAt:null,reconciledAt:null
})
const page=(start:number,count:number,more:boolean)=>({
 items:Array.from({length:count},(_,i)=>shipment(start+i)),
 nextCursor:more?'opaque-next-page':null,
 hasMore:more,pageSize:50,searchPrefix:'v92-local-',state:'ALL'
})
describe('V92 local FBA shipment history full-store pages',()=>{
 it('keeps separate 50 + 50 + 25 page identities without source upgrades',()=>{
  const first=inboundShipmentPageSchema.parse(page(1,50,true))
  const second=inboundShipmentPageSchema.parse(page(51,50,true))
  const third=inboundShipmentPageSchema.parse(page(101,25,false))
  expect(first.items).toHaveLength(50)
  expect(second.items).toHaveLength(50)
  expect(third.items).toHaveLength(25)
  expect(new Set([...first.items,...second.items,...third.items].map(x=>x.id)).size)
   .toBe(125)
  expect(third.items.at(-1)?.reference).toBe('V92-LOCAL-125')
  expect(third.items.at(-1)?.origin).toBe('MOCK')
 })
 it('blocks forged overlong or incoherent pages and fake LIVE evidence',()=>{
  expect(inboundShipmentPageSchema.safeParse(page(1,101,false)).success).toBe(false)
  expect(inboundShipmentPageSchema.safeParse({
   ...page(1,50,true),nextCursor:null
  }).success).toBe(false)
  expect(inboundShipmentPageSchema.safeParse({
   ...page(1,10,false),nextCursor:'forged'
  }).success).toBe(false)
  expect(inboundShipmentPageSchema.safeParse({
   ...page(1,1,false),items:[{...shipment(1),origin:'LIVE'}]
  }).success).toBe(false)
  expect(inboundShipmentPageSchema.safeParse({
   ...page(1,1,false),state:'AMAZON_RECEIVED'
  }).success).toBe(false)
 })
 it('uses secure same-origin transport, URI-encoded scoped filters and cursor',async()=>{
  const requests:Array<{url:string,options:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   requests.push({url,options})
   return {ok:true,status:200,
    headers:new Headers({'content-type':'application/json'}),
    json:async()=>page(101,25,false)
   }
  }))
  const result=await getShipmentsPage(mp,' V92-LOCAL- ','ALL','encoded+/=token',50)
  expect(result.items).toHaveLength(25)
  const url=new URL(requests[0].url,'http://localhost')
  expect(url.pathname).toBe('/api/v1/fba/marketplaces/'+mp+'/shipments/page')
  expect(url.searchParams.get('search')).toBe('V92-LOCAL-')
  expect(url.searchParams.get('state')).toBe('ALL')
  expect(url.searchParams.get('limit')).toBe('50')
  expect(url.searchParams.get('cursor')).toBe('encoded+/=token')
  expect(url.searchParams.has('tenantId')).toBe(false)
  expect(requests[0].options.credentials).toBe('same-origin')
  expect(requests[0].options.cache).toBe('no-store')
 })
 it('omits unselected search/cursor while retaining filter field',async()=>{
  const paths:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   paths.push(url)
   return {ok:true,status:200,
    headers:new Headers({'content-type':'application/json'}),
    json:async()=>page(1,0,false)}
  }))
  await getShipmentsPage(mp)
  const url=new URL(paths[0],'http://localhost')
  expect(url.searchParams.has('cursor')).toBe(false)
  expect(url.searchParams.has('search')).toBe(false)
  expect(url.searchParams.get('state')).toBe('ALL')
 })
})
