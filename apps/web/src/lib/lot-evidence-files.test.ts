import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchProofFileInfo,uploadProofFile,proofDownloadHref,
 fetchSupplierReturnTraces,reportSupplierReturnDispatch,
 reportSupplierReturnReceipt} from './lot-evidence-files'
afterEach(()=>vi.unstubAllGlobals())
const proof='11111111-1111-4111-8111-111111111111'
const fileId='22222222-2222-4222-8222-222222222222'
const actor='33333333-3333-4333-8333-333333333333'
const request='44444444-4444-4444-8444-444444444444'
const warehouse='55555555-5555-4555-8555-555555555555'
const traceId='66666666-6666-4666-8666-666666666666'
const disposition='77777777-7777-4777-8777-777777777777'
const receipt='88888888-8888-4888-8888-888888888888'
const supplier='99999999-9999-4999-8999-999999999999'
const meta={id:fileId,proofId:proof,mediaType:'application/pdf',
 sizeBytes:14,sha256:'a'.repeat(64),uploadedBy:actor}
const trace=(status:string)=>({id:traceId,dispositionId:disposition,
 sourceReceiptId:receipt,supplierId:supplier,quantity:8,
 carrierReference:'TRACK-001',reason:'Physical supplier return was reported',
 status,dispatchedBy:actor,
 receivedQuantity:status==='DISPATCH_REPORTED'?null:5,
 receiptReference:status==='DISPATCH_REPORTED'?null:'VENDOR-ACK-001',
 receivedReportedBy:status==='DISPATCH_REPORTED'?null:proof})
function mock(handler:(url:string)=>unknown){
 const calls:Array<{url:string;options:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit={})=>{
  calls.push({url,options})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'valid-v52-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url)),{status:200})
 }))
 return calls
}
describe('V52 private evidence original upload and supplier return contracts',()=>{
 it('binary metadata and download URL are tenant-session protected API routes',async()=>{
  const calls=mock(()=>meta)
  expect((await fetchProofFileInfo(proof))?.sha256).toBe('a'.repeat(64))
  expect(proofDownloadHref(proof)).toContain('/lot-proof-files/'+proof+'/download')
  expect(calls[0].options.method).toBeUndefined()
 })
 it('uploads multipart with CSRF and no forced JSON content type',async()=>{
  const calls=mock(()=>meta)
  const file=new File(['%PDF-1.4\nhello'],'evidence.pdf',{type:'application/pdf'})
  const result=await uploadProofFile(proof,request,file)
  expect(result.id).toBe(fileId)
  const post=calls.find(x=>x.options.method==='POST')
  expect(post?.options.headers).toMatchObject({'X-CSRF-TOKEN':'valid-v52-csrf-token'})
  expect(post?.url).toContain('requestKey='+request)
  expect(post?.options.body).toBeInstanceOf(FormData)
  expect((post?.options.body as FormData).get('file')).toBe(file)
  expect(post?.options.headers).not.toHaveProperty('Content-Type')
 })
 it('fails client validation for oversized or unsupported uploads',async()=>{
  vi.stubGlobal('fetch',vi.fn())
  await expect(uploadProofFile(proof,request,new File(['evil'],'x.html',
   {type:'text/html'}))).rejects.toThrow('仅支持')
  await expect(uploadProofFile(proof,request,new File([new Uint8Array(2097153)],
   'large.pdf',{type:'application/pdf'}))).rejects.toThrow('2 MiB')
  expect(fetch).not.toHaveBeenCalled()
 })
 it('reads supplier trace anchored to original purchase receipt and supplier',async()=>{
  const calls=mock(()=>[trace('DISPATCH_REPORTED')])
  const rows=await fetchSupplierReturnTraces(warehouse)
  expect(rows[0].sourceReceiptId).toBe(receipt)
  expect(rows[0].supplierId).toBe(supplier)
  expect(calls[0].url).toContain('/warehouses/'+warehouse)
 })
 it('dispatch and second-person supplier receipt reports never invoke stock route',async()=>{
  const calls=mock(url=>url.includes('/receipt')?trace('RECEIPT_MISMATCH'):
   trace('DISPATCH_REPORTED'))
  await reportSupplierReturnDispatch({requestKey:request,dispositionId:disposition,
   carrierReference:'TRACK-001',reason:'Physical supplier return was reported'})
  const received=await reportSupplierReturnReceipt(traceId,{receivedQuantity:5,
   receiptReference:'VENDOR-ACK-001',reason:'Supplier reported five cartons'})
  expect(received.status).toBe('RECEIPT_MISMATCH')
  const posts=calls.filter(x=>x.options.method==='POST')
  expect(posts).toHaveLength(2)
  expect(posts[0].url).toBe('/api/v1/purchasing/lot-supplier-returns')
  expect(posts[1].url).toContain('/'+traceId+'/receipt')
  expect(JSON.parse(String(posts[1].options.body)).receivedQuantity).toBe(5)
 })
})
