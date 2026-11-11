import {afterEach,describe,expect,it,vi} from 'vitest'
import {actLotPosition,fetchLotActionHistory,fetchLotActionHistoryPage,
 fetchLotExpiryAlerts,fetchLotExpiryAlertsPage} from './lot-lifecycle'
afterEach(()=>vi.unstubAllGlobals())
const position='11111111-1111-4111-8111-111111111111'
const warehouse='22222222-2222-4222-8222-222222222222'
const destinationWh='33333333-3333-4333-8333-333333333333'
const sourceBin='44444444-4444-4444-8444-444444444444'
const destinationBin='55555555-5555-4555-8555-555555555555'
const requestKey='66666666-6666-4666-8666-666666666666'
const actor='77777777-7777-4777-8777-777777777777'
const actionId='88888888-8888-4888-8888-888888888888'
const op='99999999-9999-4999-8999-999999999999'
const source='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const reason='Verified actual goods by warehouse owner'
const action=(type:string)=>({
 id:actionId,actionType:type,sourcePositionId:position,
 sourceWarehouseId:warehouse,sourceBinId:sourceBin,
 destinationWarehouseId:type==='TRANSFER'?destinationWh:null,
 destinationBinId:type==='MOVE'||type==='TRANSFER'?destinationBin:null,
 destinationPositionId:null,operationId:type==='TRANSFER'?op:null,
 quantity:type==='TRANSFER'?3:type==='EXPIRED_QUARANTINE'?2:0,
 expectedRemaining:10,expectedOnHand:['TRANSFER','EXPIRED_QUARANTINE'].includes(type)?20:null,
 observedQuantity:type==='COUNT_OBSERVATION'?8:null,
 reason,actorUserId:actor,createdAt:'2026-11-01T10:00:00Z'
})
const expiry={positionId:position,lotCode:'LOT-001',binCode:'SHELF-A',
 productSkuId:source,remainingQuantity:10,expiryDate:'2026-10-20',urgency:'EXPIRED'}
function mocked(handler:(url:string,method?:string)=>unknown){
 const calls:Array<{url:string;options?:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  calls.push({url,options})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'valid-lifecycle-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url,options?.method)),{status:200})
 }))
 return calls
}
describe('V49 released lot lifecycle API contracts',()=>{
 it('warehouse expiry alerts explicitly distinguish expired from near expiry',async()=>{
  const calls=mocked(()=>[expiry])
  const data=await fetchLotExpiryAlerts(warehouse)
  expect(data[0].urgency).toBe('EXPIRED')
  expect(data[0].remainingQuantity).toBe(10)
  expect(calls[0].url).toBe('/api/v1/purchasing/lot-lifecycle/warehouses/'+warehouse+
   '/expiry-alerts')
 })
 it('same-warehouse relocation only sends old lot snapshot and target STORAGE bin',async()=>{
  const calls=mocked(()=>action('MOVE'))
  const result=await actLotPosition(position,{
   requestKey,actionType:'MOVE',expectedBinId:sourceBin,destinationBinId:destinationBin,
   quantity:0,expectedRemaining:10,expectedOnHand:null,
   observedQuantity:null,reason})
  expect(result.actionType).toBe('MOVE')
  const post=calls.find(c=>c.options?.method==='POST')
  expect(new Headers(post?.options?.headers).get('X-CSRF-TOKEN'))
   .toBe('valid-lifecycle-csrf-token')
  expect(JSON.parse(String(post?.options?.body))).toEqual({
   requestKey,actionType:'MOVE',expectedBinId:sourceBin,destinationBinId:destinationBin,
   quantity:0,expectedRemaining:10,expectedOnHand:null,
   observedQuantity:null,reason})
 })
 it('transfer requires expected signed warehouse balance and preserves action evidence',async()=>{
  const calls=mocked(()=>action('TRANSFER'))
  const saved=await actLotPosition(position,{
   requestKey,actionType:'TRANSFER',expectedBinId:sourceBin,destinationBinId:destinationBin,
   quantity:3,expectedRemaining:10,expectedOnHand:20,
   observedQuantity:null,reason})
  expect(saved.operationId).toBe(op)
  expect(saved.destinationWarehouseId).toBe(destinationWh)
  const post=calls.find(c=>c.options?.method==='POST')
  expect(JSON.parse(String(post?.options?.body))).toMatchObject({
   actionType:'TRANSFER',quantity:3,expectedRemaining:10,expectedOnHand:20
  })
 })
 it('count observation explicitly has no warehouse deduction or stock approval',async()=>{
  const calls=mocked(()=>action('COUNT_OBSERVATION'))
  const saved=await actLotPosition(position,{
   requestKey,actionType:'COUNT_OBSERVATION',expectedBinId:sourceBin,destinationBinId:null,
   quantity:0,expectedRemaining:10,expectedOnHand:null,
   observedQuantity:8,reason})
  expect(saved.observedQuantity).toBe(8)
  expect(saved.operationId).toBeNull()
  expect(JSON.parse(String(calls.find(c=>c.options?.method==='POST')?.options?.body)))
   .toMatchObject({actionType:'COUNT_OBSERVATION',observedQuantity:8,quantity:0})
 })
 it('expired goods use their own quarantine action rather than generic OUTBOUND',async()=>{
  const calls=mocked(()=>action('EXPIRED_QUARANTINE'))
  const saved=await actLotPosition(position,{
   requestKey,actionType:'EXPIRED_QUARANTINE',expectedBinId:sourceBin,destinationBinId:null,
   quantity:2,expectedRemaining:10,expectedOnHand:20,
   observedQuantity:null,reason})
  expect(saved.actionType).toBe('EXPIRED_QUARANTINE')
  expect(saved.operationId).toBeNull()
  const post=calls.find(c=>c.options?.method==='POST')
  expect(JSON.parse(String(post?.options?.body))).toMatchObject({
   actionType:'EXPIRED_QUARANTINE',quantity:2,expectedOnHand:20
  })
 })
 it('audit query returns V49 action chain scoped by source position',async()=>{
  const calls=mocked(()=>[action('TRANSFER')])
  const history=await fetchLotActionHistory(position)
  expect(history[0].sourcePositionId).toBe(position)
  expect(history[0].operationId).toBe(op)
  expect(calls[0].url).toBe('/api/v1/purchasing/lot-lifecycle/positions/'+position+'/history')
 })

 it('V132 follows 225 tenant-position history rows across five generated Orval pages',async()=>{
  const data=Array.from({length:225},(_,n)=>({
   ...action(n%2===0?'TRANSFER':'MOVE'),
   id:'00000000-0000-4000-8000-'+(n+1).toString(16).padStart(12,'0'),
   createdAt:'2026-11-09T12:00:00Z'
  }))
  const calls=mocked(url=>{
   if(!url.includes('/history/page'))throw Error('Unexpected URL '+url)
   const params=new URL(url,'http://127.0.0.1').searchParams
   expect(params.get('limit')).toBe('50')
   const offset=Number(params.get('cursor')??'0')
   const items=data.slice(offset,offset+50)
   const more=offset+50<data.length
   return {items,nextCursor:more?String(offset+50):null,
    hasMore:more,pageSize:50}
  })
  let cursor:string|null=null
  const ids=new Set<string>()
  const sizes:number[]=[]
  for(let p=0;p<5;p++){
   const result=await fetchLotActionHistoryPage(position,cursor)
   sizes.push(result.items.length)
   result.items.forEach(x=>ids.add(x.id))
   expect(result.hasMore).toBe(p<4)
   cursor=result.nextCursor
  }
  expect(sizes).toEqual([50,50,50,50,25])
  expect(ids.size).toBe(225)
  expect(cursor).toBeNull()
  expect(calls).toHaveLength(5)
  expect(calls.every(c=>c.options?.method===undefined||c.options?.method==='GET')).toBe(true)
 })
 it('V132 malformed page cursor response fails closed and wrong UUID blocks request',async()=>{
  mocked(()=>({items:[],hasMore:true,nextCursor:null,pageSize:50}))
  await expect(fetchLotActionHistoryPage(position)).rejects.toThrow()
  expect(()=>fetchLotActionHistoryPage('wrong')).toThrow()
 })


 it('V134 browses 325 expired and near-expiry positions over seven pages',async()=>{
  const items=Array.from({length:325},(_,i)=>({
   ...expiry,positionId:'00000000-0000-4000-8000-'+(i+1).toString(16).padStart(12,'0'),
   expiryDate:i<175?'2026-11-01':'2026-11-19',
   urgency:i<175?'EXPIRED':'NEAR_EXPIRY'
  }))
  const calls=mocked(url=>{
   if(!url.includes('/expiry-alerts/page'))throw Error('Wrong route '+url)
   const params=new URL(url,'http://127.0.0.1').searchParams
   expect(params.get('limit')).toBe('50')
   const offset=Number(params.get('cursor')??'0')
   const rows=items.slice(offset,offset+50)
   const more=offset+50<items.length
   return {items:rows,hasMore:more,nextCursor:more?String(offset+50):null,
    pageSize:50}
  })
  let cursor:string|null=null
  const seen=new Set<string>()
  const sizes:number[]=[]
  for(let page=0;page<7;page++){
   const response=await fetchLotExpiryAlertsPage(warehouse,cursor)
   sizes.push(response.items.length)
   expect(response.hasMore).toBe(page<6)
   response.items.forEach(x=>seen.add(x.positionId))
   cursor=response.nextCursor
  }
  expect(sizes).toEqual([50,50,50,50,50,50,25])
  expect(seen.size).toBe(325)
  expect(cursor).toBeNull()
  expect(calls).toHaveLength(7)
  expect(calls.every(c=>c.options?.method===undefined||c.options?.method==='GET')).toBe(true)
 })
 it('V134 fails closed on inconsistent cursor, invalid warehouse and oversized pages',async()=>{
  mocked(()=>({items:[expiry],hasMore:true,nextCursor:null,pageSize:50}))
  await expect(fetchLotExpiryAlertsPage(warehouse)).rejects.toThrow()
  expect(()=>fetchLotExpiryAlertsPage('bad')).toThrow()
  mocked(()=>({items:Array.from({length:101},()=>expiry),
   hasMore:false,nextCursor:null,pageSize:50}))
  await expect(fetchLotExpiryAlertsPage(warehouse)).rejects.toThrow()
 })

})
