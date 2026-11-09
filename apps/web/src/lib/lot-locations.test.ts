import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 fetchStockBins,createStockBin,fetchReceiptLotPieces,splitReceiptLot,
 moveReceiptLot,fetchReceiptSupplyPressure
} from './lot-locations'
afterEach(()=>vi.unstubAllGlobals())
const warehouse='11111111-1111-4111-8111-111111111111'
const receipt='22222222-2222-4222-8222-222222222222'
const piece='33333333-3333-4333-8333-333333333333'
const bin='44444444-4444-4444-8444-444444444444'
const otherBin='55555555-5555-4555-8555-555555555555'
const sku='66666666-6666-4666-8666-666666666666'
const key='77777777-7777-4777-8777-777777777777'
const item={id:piece,receiptId:receipt,warehouseId:warehouse,
 productSkuId:sku,binId:bin,pieceCode:'ROOT',quantity:10}
const binItem={id:bin,warehouseId:warehouse,code:'QC-HOLD',
 purpose:'QC_HOLD',status:'ACTIVE'}
function fake(handler:(url:string,method?:string)=>unknown){
 const calls:Array<{url:string;opts?:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,opts?:RequestInit)=>{
  calls.push({url,opts})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'lot-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url,opts?.method)),{status:200})
 }))
 return calls
}
describe('V47 receipt lot pieces and QC location contract',()=>{
 it('reads tenant-owned QC staging bins without inventing saleable stock',async()=>{
  const calls=fake(()=>[binItem])
  const result=await fetchStockBins(warehouse)
  expect(result[0].purpose).toBe('QC_HOLD')
  expect(calls[0].url).toBe(
   '/api/v1/purchasing/lot-locations/warehouses/'+warehouse+'/bins')
 })
 it('creates human-defined physical bin metadata using CSRF and purpose',async()=>{
  const calls=fake(()=>({...binItem,id:otherBin,code:'QC-RACK-A'}))
  const result=await createStockBin(warehouse,'QC-RACK-A','QC_HOLD')
  expect(result.code).toBe('QC-RACK-A')
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(new Headers(post?.opts?.headers).get('X-CSRF-TOKEN')).toBe('lot-csrf-token')
  expect(JSON.parse(String(post?.opts?.body))).toEqual({
   code:'QC-RACK-A',purpose:'QC_HOLD'})
 })
 it('split uses explicit request key and keeps entire original receipt in pieces',async()=>{
  const calls=fake(url=>url.endsWith('/split')?[
   {...item,quantity:7},
   {...item,id:otherBin,binId:otherBin,quantity:3,pieceCode:'ROOT-B'}]:[item])
  const base=await fetchReceiptLotPieces(receipt)
  expect(base[0].quantity).toBe(10)
  const result=await splitReceiptLot(receipt,{
   requestKey:key,pieceId:piece,quantity:3,pieceCode:'ROOT-B',
   destinationBinId:otherBin,reason:'Three cartons moved to second staging rack'
  })
  expect(result.reduce((n,p)=>n+p.quantity,0)).toBe(10)
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(post?.url).toBe('/api/v1/purchasing/lot-locations/receipts/'+receipt+'/split')
  expect(JSON.parse(String(post?.opts?.body))).toMatchObject({
   requestKey:key,pieceId:piece,quantity:3,destinationBinId:otherBin})
 })
 it('move requires source bin snapshot and does not imply a stock movement',async()=>{
  const calls=fake(()=>[{...item,binId:otherBin}])
  const value=await moveReceiptLot(receipt,{
   requestKey:key,pieceId:piece,expectedBinId:bin,
   destinationBinId:otherBin,reason:'Counted cartons now in QC rack B'
  })
  expect(value[0].binId).toBe(otherBin)
  const post=calls.find(c=>c.opts?.method==='POST')
  expect(JSON.parse(String(post?.opts?.body))).toEqual({
   requestKey:key,pieceId:piece,expectedBinId:bin,
   destinationBinId:otherBin,reason:'Counted cartons now in QC rack B'})
 })
 it('supply pressure keeps hold and supplier claims separate from usable stock',async()=>{
  const calls=fake(()=>[{
   warehouseId:warehouse,productSkuId:sku,internalSku:'SKU-1',
   usableUnits:4,pendingQcUnits:10,acceptedAwaitingReleaseUnits:7,
   declaredAsnInTransitUnits:12
  }])
  const result=await fetchReceiptSupplyPressure(warehouse)
  expect(result[0].usableUnits).toBe(4)
  expect(result[0].pendingQcUnits).toBe(10)
  expect(result[0].acceptedAwaitingReleaseUnits).toBe(7)
  expect(result[0].declaredAsnInTransitUnits).toBe(12)
  expect(calls[0].url).toContain('/pressure')
 })
})
