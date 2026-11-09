import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchSupplierAsnsPage,fetchSupplierAsnEventsPage} from './supplier-asns'
import {gradePiece,pickReleasedLots} from './released-lots'
import {actLotPosition} from './lot-lifecycle'

const order='11111111-1111-4111-8111-111111111111'
const asn='22222222-2222-4222-8222-222222222222'
const warehouse='33333333-3333-4333-8333-333333333333'
const line='44444444-4444-4444-8444-444444444444'
const sku='55555555-5555-4555-8555-555555555555'
const position='66666666-6666-4666-8666-666666666666'
const bin='77777777-7777-4777-8777-777777777777'
const actor='88888888-8888-4888-8888-888888888888'
const operation='99999999-9999-4999-8999-999999999999'
const key='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
function id(n:number){return '00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')}
const notice=(n:number)=>({
 id:id(n),orderId:order,lineId:line,warehouseId:warehouse,
 quantity:10,receivedQuantity:0,expectedArrivalDate:'2026-11-19',
 trackingReference:null,status:'DRAFT',dispatchReportedAt:null,
 createdAt:'2026-11-09T06:00:00Z'
})
const event=(n:number)=>({
 id:id(n),eventType:'ETA_UPDATED',actorUserId:actor,quantity:null,
 oldEta:'2026-11-18',newEta:'2026-11-19',note:'Supplier notified delay',
 createdAt:'2026-11-09T06:00:00Z'
})
afterEach(()=>vi.unstubAllGlobals())

describe('V117 full ASN and V48/V49 generated API contracts',()=>{
 it('reads all 225 ASN notices as 50+50+50+50+25 and includes the 201st',async()=>{
  const seen:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(raw:string,options:RequestInit)=>{
   const url=new URL(raw,'http://localhost')
   seen.push(url.toString())
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   const page=Number(url.searchParams.get('cursor')?.slice(1)??'0')
   const start=page*50
   return new Response(JSON.stringify({
    items:Array.from({length:Math.min(50,225-start)},(_,i)=>notice(start+i+1)),
    pageSize:50,hasMore:start+50<225,
    nextCursor:start+50<225?'P'+(page+1):null
   }),{status:200})
  }))
  let cursor:string|null=null
  const pages=[]
  for(let n=0;n<5;n++){
   const page=await fetchSupplierAsnsPage(order,cursor)
   pages.push(page)
   cursor=page.nextCursor
  }
  expect(pages.map(p=>p.items.length)).toEqual([50,50,50,50,25])
  expect(pages[4].items[0].id).toBe(id(201))
  expect(pages.at(-1)?.hasMore).toBe(false)
  expect(cursor).toBeNull()
  expect(seen.map(s=>new URL(s).searchParams.get('limit'))).toEqual(Array(5).fill('50'))
  expect(seen[4]).toContain('/api/v1/purchasing/supplier-asns/orders/'+order+'/page?')
 })
 it('pages all 225 immutable ASN audit events and binds order+ASN URI',async()=>{
  const seen:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(raw:string)=>{
   seen.push(raw)
   const url=new URL(raw,'http://localhost')
   const page=Number(url.searchParams.get('cursor')?.slice(1)??'0')
   const start=page*50
   return new Response(JSON.stringify({items:Array.from({
    length:Math.min(50,225-start)},(_,i)=>event(start+i+1)),
    pageSize:50,hasMore:start+50<225,
    nextCursor:start+50<225?'P'+(page+1):null}),{status:200})
  }))
  let cursor:string|null=null
  const lengths=[]
  let last=''
  for(let n=0;n<5;n++){
   const p=await fetchSupplierAsnEventsPage(order,asn,cursor)
   lengths.push(p.items.length)
   last=p.items[0].id
   cursor=p.nextCursor
  }
  expect(lengths).toEqual([50,50,50,50,25])
  expect(last).toBe(id(201))
  expect(seen[0]).toContain('/orders/'+order+'/'+asn+'/events/page')
 })
 it('rejects missing page data, conflicting cursor and malformed path UUIDs',async()=>{
  const spy=vi.fn()
  vi.stubGlobal('fetch',spy)
  expect(()=>fetchSupplierAsnsPage('../bad')).toThrow()
  expect(()=>fetchSupplierAsnEventsPage(order,'not-id')).toThrow()
  expect(spy).not.toHaveBeenCalled()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([notice(1)]),{status:200})))
  await expect(fetchSupplierAsnsPage(order)).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[],pageSize:50,hasMore:true,nextCursor:null}),{status:200})))
  await expect(fetchSupplierAsnEventsPage(order,asn)).rejects.toThrow()
 })
 it('V48 exact-balance pick sends requestKey and CSRF, not an inferred zero',async()=>{
  const recorded:Array<{url:string;opts:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,opts:RequestInit={})=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v117-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   recorded.push({url,opts})
   const result=url.endsWith('/picks')?{
    id:asn,warehouseId:warehouse,productSkuId:sku,operationId:operation,
    policy:'FEFO',quantity:3,expectedOnHand:42,reason:'Physical three unit pick',
    actorUserId:actor,allocations:[{positionId:position,lotCode:'LOT-A',
     binCode:'STORAGE-A',quantity:3,expiryDate:null}]
   }:{id:asn,receiptId:order,pieceId:line,acceptedQuantity:2,
    rejectedQuantity:1,reason:'Physical quantity inspection',inspectedBy:actor,
    createdAt:'2026-11-09T06:00:00Z'}
   return new Response(JSON.stringify(result),{status:201})
  }))
  await gradePiece(order,{requestKey:key,pieceId:line,
   acceptedQuantity:2,rejectedQuantity:1,reason:'Physical quantity inspection'})
  await pickReleasedLots({requestKey:key,warehouseId:warehouse,productSkuId:sku,
   policy:'FEFO',quantity:3,expectedOnHand:42,reason:'Physical three unit pick'})
  expect(recorded).toHaveLength(2)
  const pick=recorded[1]
  expect(pick.opts.method).toBe('POST')
  expect(JSON.parse(String(pick.opts.body))).toMatchObject({
   requestKey:key,policy:'FEFO',quantity:3,expectedOnHand:42})
  expect(new Headers(pick.opts.headers).get('X-CSRF-TOKEN')).toBe('v117-valid-csrf-token')
 })
 it('V49 action preserves observed count as non-posting and source snapshot',async()=>{
  const requests:Array<{url:string;opts:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,opts:RequestInit={})=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v117-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   requests.push({url,opts})
   return new Response(JSON.stringify({
    id:asn,actionType:'COUNT_OBSERVATION',sourcePositionId:position,
    sourceWarehouseId:warehouse,sourceBinId:bin,
    destinationWarehouseId:null,destinationBinId:null,destinationPositionId:null,
    operationId:null,quantity:0,expectedRemaining:10,expectedOnHand:null,
    observedQuantity:9,reason:'Manual count observed nine',
    actorUserId:actor,createdAt:'2026-11-09T06:00:00Z'}),{status:201})
  }))
  const body={requestKey:key,actionType:'COUNT_OBSERVATION' as const,
   expectedBinId:bin,destinationBinId:null,quantity:0,expectedRemaining:10,
   expectedOnHand:null,observedQuantity:9,reason:'Manual count observed nine'}
  const saved=await actLotPosition(position,body)
  expect(saved.operationId).toBeNull()
  expect(saved.observedQuantity).toBe(9)
  expect(JSON.parse(String(requests[0].opts.body))).toEqual(body)
 })
 it('conflicting signed lot pick rejects instead of claiming physical fulfilment',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v117-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({message:'Stock balance changed'}),{status:409})
  }))
  await expect(pickReleasedLots({requestKey:key,warehouseId:warehouse,
   productSkuId:sku,policy:'FIFO',quantity:2,expectedOnHand:42,
   reason:'Physical goods counted'})).rejects.toThrow()
 })
})
