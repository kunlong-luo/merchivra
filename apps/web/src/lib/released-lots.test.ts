import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchPieceGrades,gradePiece,finalizePieceGrades,fetchLotPositions,
 putawayReleasedLot,previewLotPick,pickReleasedLots,lotPickCapacityHint,
 fetchLotPositionsPage,fetchReleasedLotReceiptAllocation} from './released-lots'
afterEach(()=>vi.unstubAllGlobals())
const receipt='11111111-1111-4111-8111-111111111111'
const piece='22222222-2222-4222-8222-222222222222'
const actor='33333333-3333-4333-8333-333333333333'
const gradeId='44444444-4444-4444-8444-444444444444'
const reqKey='55555555-5555-4555-8555-555555555555'
const warehouse='66666666-6666-4666-8666-666666666666'
const sku='77777777-7777-4777-8777-777777777777'
const bin='88888888-8888-4888-8888-888888888888'
const pos='99999999-9999-4999-8999-999999999999'
const op='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const gradeData={id:gradeId,receiptId:receipt,pieceId:piece,
 acceptedQuantity:7,rejectedQuantity:3,reason:'Three damaged cartons',
 inspectedBy:actor,createdAt:'2026-11-01T12:00:00Z'}
const positionData={id:pos,receiptId:receipt,warehouseId:warehouse,
 productSkuId:sku,binId:bin,binCode:'SHELF-A',lotCode:'LOT-A',
 placedQuantity:7,remainingQuantity:7,expiryDate:'2026-12-01',
 createdAt:'2026-11-01T14:00:00Z'}
const pickData={id:gradeId,warehouseId:warehouse,productSkuId:sku,
 operationId:op,policy:'FEFO',quantity:3,expectedOnHand:10,
 reason:'Actually picked from storage rack',actorUserId:actor,allocations:[
  {positionId:pos,lotCode:'LOT-A',binCode:'SHELF-A',quantity:3,
   expiryDate:'2026-12-01'}]}
const inspected={id:gradeId,receiptId:receipt,warehouseId:warehouse,
 productSkuId:sku,inspectedQuantity:10,acceptedQuantity:7,rejectedQuantity:3,
 reason:'Completed lot quality inspection',inspectedBy:actor,
 createdAt:'2026-11-01T12:00:00Z'}
function fake(handler:(url:string,opts?:RequestInit)=>unknown){
 const calls:Array<{url:string;options?:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
  calls.push({url,options})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'valid-v48-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url,options)),{status:200})
 }))
 return calls
}
describe('V48 per-piece QC and lot stock workflows',()=>{
 it('V130 never treats exactly 500 preview rows as complete inventory',()=>{
  const partial=Array.from({length:500},()=>({remainingQuantity:1}))
  expect(lotPickCapacityHint(partial,650)).toEqual({
   shown:500,mayHaveMore:true,maxPickable:650
  })
  expect(lotPickCapacityHint(partial.slice(0,499),650)).toEqual({
   shown:499,mayHaveMore:false,maxPickable:499
  })
  expect(lotPickCapacityHint(partial,undefined).maxPickable).toBe(0)
  expect(lotPickCapacityHint([],100).maxPickable).toBe(0)
 })
 it('grade records immutable actual piece quantities with authenticated CSRF',async()=>{
  const calls=fake(url=>url.includes('/finalize')?inspected:gradeData)
  await gradePiece(receipt,{requestKey:reqKey,pieceId:piece,
   acceptedQuantity:7,rejectedQuantity:3,reason:'Three damaged cartons'})
  await finalizePieceGrades(receipt,{requestKey:reqKey,reason:'Completed lot quality inspection'})
  const posts=calls.filter(c=>c.options?.method==='POST')
  expect(posts).toHaveLength(2)
  expect(new Headers(posts[0].options?.headers).get('X-CSRF-TOKEN')).toBe('valid-v48-csrf-token')
  expect(JSON.parse(String(posts[0].options?.body))).toMatchObject({
   requestKey:reqKey,pieceId:piece,acceptedQuantity:7,rejectedQuantity:3
  })
  expect(posts[1].url).toBe('/api/v1/purchasing/piece-grades/receipts/'+receipt+'/finalize')
 })
 it('graded pieces are separate facts from final receipt inspection',async()=>{
  const calls=fake(()=>[gradeData])
  expect((await fetchPieceGrades(receipt))[0].rejectedQuantity).toBe(3)
  expect(calls[0].url).toBe('/api/v1/purchasing/piece-grades/receipts/'+receipt)
 })
 it('released lot positions can be inspected without posting an operation',async()=>{
  const calls=fake(()=>[positionData])
  const positions=await fetchLotPositions(warehouse)
  expect(positions[0].remainingQuantity).toBe(7)
  expect(positions[0].expiryDate).toBe('2026-12-01')
  expect(calls).toHaveLength(1)
 })
 it('explicit post-release putaway does not imply duplicate stock credit',async()=>{
  const calls=fake(()=>positionData)
  await putawayReleasedLot({requestKey:reqKey,receiptId:receipt,binId:bin,
   quantity:7,expiryDate:'2026-12-01',reason:'Actually stored in approved rack'})
  const post=calls.find(c=>c.options?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/released-lots/putaway')
  expect(JSON.parse(String(post?.options?.body))).toEqual({
   requestKey:reqKey,receiptId:receipt,binId:bin,quantity:7,
   expiryDate:'2026-12-01',reason:'Actually stored in approved rack'})
 })
 it('FEFO preview is read only, confirmed pick requires exact stock snapshot',async()=>{
  const calls=fake(url=>url.includes('pick-preview')?[positionData]:pickData)
  const preview=await previewLotPick(warehouse,sku,'FEFO')
  expect(preview[0].lotCode).toBe('LOT-A')
  const result=await pickReleasedLots({requestKey:reqKey,warehouseId:warehouse,
   productSkuId:sku,policy:'FEFO',quantity:3,expectedOnHand:10,
   reason:'Actually picked from storage rack'})
  expect(result.allocations[0].quantity).toBe(3)
  expect(result.operationId).toBe(op)
  expect(calls.find(c=>c.url.includes('pick-preview'))?.options?.method).toBe('GET')
  const post=calls.find(c=>c.options?.method==='POST')
  expect(JSON.parse(String(post?.options?.body))).toMatchObject({
   policy:'FEFO',quantity:3,expectedOnHand:10,requestKey:reqKey})
 })

 it('V133 uses Orval to browse 325 actual positions over seven source pages',async()=>{
  const data=Array.from({length:325},(_,n)=>({
   ...positionData,id:'00000000-0000-4000-8000-'+(n+1).toString(16).padStart(12,'0')
  }))
  const calls=fake(url=>{
   if(!url.includes('/positions/page'))throw Error('Unexpected URL '+url)
   const search=new URL(url,'http://127.0.0.1').searchParams
   expect(search.get('limit')).toBe('50')
   const offset=Number(search.get('cursor')??'0')
   const items=data.slice(offset,offset+50)
   const hasMore=offset+50<data.length
   return {items,hasMore,nextCursor:hasMore?String(offset+50):null,pageSize:50}
  })
  let cursor:string|null=null
  const seen=new Set<string>()
  const sizes:number[]=[]
  for(let i=0;i<7;i++){
   const response=await fetchLotPositionsPage(warehouse,cursor)
   sizes.push(response.items.length)
   expect(response.hasMore).toBe(i<6)
   response.items.forEach(p=>seen.add(p.id))
   cursor=response.nextCursor
  }
  expect(sizes).toEqual([50,50,50,50,50,50,25])
  expect(seen.size).toBe(325)
  expect(cursor).toBeNull()
  expect(calls).toHaveLength(7)
  expect(calls.every(c=>c.options?.method===undefined||c.options?.method==='GET')).toBe(true)
 })
 it('V133 exact receipt allocation never derives capacity from the first page',async()=>{
  const calls=fake(()=>({
   receiptId:receipt,warehouseId:warehouse,productSkuId:sku,status:'RELEASED',
   acceptedQuantity:650,placedQuantity:601,remainingQuantity:49
  }))
  const result=await fetchReleasedLotReceiptAllocation(receipt)
  expect(result.remainingQuantity).toBe(49)
  expect(result.placedQuantity).toBe(601)
  expect(calls[0].url).toBe('/api/v1/purchasing/released-lots/receipts/'+receipt+'/allocation')
  expect(calls[0].options?.method??'GET').toBe('GET')
 })
 it('V133 rejects contradictory allocation, cursors, and non-UUID receipt paths',async()=>{
  fake(()=>({
   receiptId:receipt,warehouseId:warehouse,productSkuId:sku,status:'RELEASED',
   acceptedQuantity:650,placedQuantity:601,remainingQuantity:100
  }))
  await expect(fetchReleasedLotReceiptAllocation(receipt)).rejects.toThrow()
  expect(()=>fetchReleasedLotReceiptAllocation('bad')).toThrow()
 })

})
