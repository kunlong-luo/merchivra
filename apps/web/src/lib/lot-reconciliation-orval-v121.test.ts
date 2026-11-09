import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 fetchLotCountCasesPage,fetchDestinationTransfersPage,
 fetchLotDispositionsPage,fetchUnplannedQuarantinesPage,
 submitLotCount,acknowledgeDestinationTransfer,decideLotDisposition
} from './lot-reconciliation'
import {fetchLotExceptionsPage,proposeLotException} from './lot-exceptions'

const id=(n:number)=>'00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
const wh=id(1),sku=id(2),bin=id(3),owner=id(4),actor=id(5),key=id(6),receipt=id(7)
const count=(n:number)=>({
 id:id(1000+n),observationActionId:id(2000+n),positionId:id(3000+n),
 warehouseId:wh,productSkuId:sku,expectedBinId:bin,
 expectedPositionQuantity:10,observedQuantity:8,expectedOnHand:42,
 reason:'Manual count inspected',status:'PENDING_REVIEW',submittedBy:owner,
 reviewedBy:null,operationId:null,reviewReason:null
})
const transfer=(n:number)=>({
 actionId:id(1000+n),destinationWarehouseId:wh,sourcePositionId:id(2000+n),
 destinationPositionId:id(3000+n),expectedQuantity:10,receivedQuantity:8,
 status:'MISMATCH',transferredBy:actor,acknowledgedBy:owner,reference:'RCV-2026'
})
const disposition=(n:number)=>({
 id:id(1000+n),quarantineActionId:id(2000+n),warehouseId:wh,quantity:3,
 method:'SUPPLIER_RETURN',status:'EXECUTED_REPORTED',reason:'Manually witnessed goods',
 proposedBy:owner,reviewedBy:actor,completedBy:owner,completionReference:'RET-2026'
})
const resolution=(n:number)=>({
 id:id(1000+n),resolutionType:'RELEASED_SURPLUS_BIND',countReviewId:id(2000+n),
 transferActionId:null,sourceReceiptId:receipt,positionId:id(3000+n),
 warehouseId:wh,productSkuId:sku,expectedBinId:bin,expectedPositionQuantity:10,
 expectedOnHand:42,adjustmentQuantity:2,evidenceReference:'COUNT-2026',
 reason:'Already QC-released unplaced units verified',status:'PENDING_REVIEW',
 proposedBy:owner,reviewedBy:null,reviewReason:null,operationId:null
})
afterEach(()=>vi.unstubAllGlobals())
describe('V121 V50/V51 generated keysets and signed evidence guards',()=>{
 it('retrieves 225 rows on each of 4 V50 sources after former first 200 cap',async()=>{
  const seen:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(raw:string,init:RequestInit={})=>{
   const url=new URL(raw,'http://localhost')
   seen.push(raw)
   expect(init.credentials).toBe('same-origin')
   expect(init.cache).toBe('no-store')
   expect(init.method).toBe('GET')
   const p=Number(url.searchParams.get('cursor')?.slice(1)??'0')
   const start=p*50,quantity=Math.min(50,225-start)
   const parts=url.pathname
   const kind=parts.includes('/counts/')?'counts':parts.includes('/transfers/')?
    'transfers':parts.includes('/dispositions/')?'dispositions':'unplanned'
   const items=Array.from({length:quantity},(_,i)=>{
    const n=start+i+1
    return kind==='counts'?count(n):kind==='transfers'?transfer(n):
     kind==='dispositions'?disposition(n):id(1000+n)
   })
   return new Response(JSON.stringify({items,pageSize:50,
    hasMore:start+50<225,nextCursor:start+50<225?'P'+(p+1):null}),{status:200})
  }))
  const readers=[
   {name:'counts',run:fetchLotCountCasesPage},
   {name:'transfers',run:fetchDestinationTransfersPage},
   {name:'dispositions',run:fetchLotDispositionsPage},
   {name:'unplanned',run:fetchUnplannedQuarantinesPage}
  ]
  for(const source of readers){
   let cursor:string|null=null
   const sizes:number[]=[]
   for(let p=0;p<5;p++){
    const result:{items:unknown[];nextCursor:string|null}=await source.run(wh,cursor)
    sizes.push(result.items.length)
    cursor=result.nextCursor
   }
   expect(sizes).toEqual([50,50,50,50,25])
   expect(cursor).toBeNull()
  }
  expect(seen).toHaveLength(20)
  expect(seen[19]).toContain('/unplanned-quarantines/page')
  expect(seen.every(x=>new URL(x,'http://localhost').searchParams.get('limit')==='50'))
   .toBe(true)
 })
 it('V51 approval page preserves filter and cursor, never inferred stock release',async()=>{
  let path=''
  vi.stubGlobal('fetch',vi.fn(async(raw:string)=>{
   path=raw
   return new Response(JSON.stringify({items:[resolution(201)],hasMore:false,
    nextCursor:null,pageSize:50}),{status:200})
  }))
  const page=await fetchLotExceptionsPage(wh,'ALL','after-200')
  expect(page.items[0].operationId).toBeNull()
  const url=new URL(path,'http://localhost')
  expect(url.pathname).toBe('/api/v1/purchasing/lot-exceptions/warehouses/'+wh+'/page')
  expect(url.searchParams.get('cursor')).toBe('after-200')
  expect(url.searchParams.get('filter')).toBe('ALL')
 })
 it('V50 warehouse count and transfer acknowledge use exact CSRF and source IDs',async()=>{
  const recorded:Array<{url:string;init:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit={})=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v121-secure-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   recorded.push({url,init})
   return new Response(JSON.stringify(url.endsWith('/acknowledge')?transfer(1):
    count(1)),{status:url.endsWith('/counts')?201:200})
  }))
  await submitLotCount({requestKey:key,observationActionId:id(2001),
   reason:'Manual count inspected'})
  const ack=await acknowledgeDestinationTransfer(id(1001),{
   requestKey:key,receivedQuantity:8,reference:'RCV-2026',
   reason:'Destination warehouse independently inspected'})
  expect(ack.status).toBe('MISMATCH')
  expect(recorded).toHaveLength(2)
  expect(JSON.parse(String(recorded[1].init.body))).toMatchObject({
   requestKey:key,receivedQuantity:8})
  for(const p of recorded){
   expect(p.init.method).toBe('POST')
   expect(p.init.credentials).toBe('same-origin')
   expect(new Headers(p.init.headers).get('X-CSRF-TOKEN'))
    .toBe('v121-secure-csrf-token')
  }
 })
 it('V51 surplus cannot be reported applied without actual source evidence',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v121-secure-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({message:'Released source receipt not valid'}),{status:409})
  }))
  await expect(proposeLotException({
   requestKey:key,resolutionType:'RELEASED_SURPLUS_BIND',sourceId:id(1001),
   sourceReceiptId:receipt,evidenceReference:'COUNT-2026',
   reason:'Already QC-released unplaced units verified'
  })).rejects.toThrow()
 })
 it('invalid UUIDs, contradictory cursors and unreviewed states fail closed',async()=>{
  const spy=vi.fn()
  vi.stubGlobal('fetch',spy)
  expect(()=>fetchLotCountCasesPage('bad-warehouse')).toThrow()
  expect(()=>fetchUnplannedQuarantinesPage('../foreign')).toThrow()
  expect(()=>decideLotDisposition('bad-id',{
   decision:'APPROVE',reason:'Independent physical verification'})).toThrow()
  expect(spy).not.toHaveBeenCalled()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[],hasMore:true,nextCursor:null,pageSize:50}),{status:200})))
  await expect(fetchLotDispositionsPage(wh)).rejects.toThrow()
 })
})
