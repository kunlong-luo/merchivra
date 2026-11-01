import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchPieceGrades,gradePiece,finalizePieceGrades,fetchLotPositions,
 putawayReleasedLot,previewLotPick,pickReleasedLots} from './released-lots'
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
 it('grade records immutable actual piece quantities with authenticated CSRF',async()=>{
  const calls=fake(url=>url.includes('/finalize')?inspected:gradeData)
  await gradePiece(receipt,{requestKey:reqKey,pieceId:piece,
   acceptedQuantity:7,rejectedQuantity:3,reason:'Three damaged cartons'})
  await finalizePieceGrades(receipt,{requestKey:reqKey,reason:'Completed lot quality inspection'})
  const posts=calls.filter(c=>c.options?.method==='POST')
  expect(posts).toHaveLength(2)
  expect(posts[0].options?.headers).toMatchObject({'X-CSRF-TOKEN':'valid-v48-csrf-token'})
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
})
