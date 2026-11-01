import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchDispositionProofs,submitDispositionProof,decideDispositionProof} from './lot-disposition-proofs'
afterEach(()=>vi.unstubAllGlobals())
const warehouse='11111111-1111-4111-8111-111111111111'
const disposition='22222222-2222-4222-8222-222222222222'
const id='33333333-3333-4333-8333-333333333333'
const key='44444444-4444-4444-8444-444444444444'
const owner='55555555-5555-4555-8555-555555555555'
const reviewer='66666666-6666-4666-8666-666666666666'
const proof=(state:'PENDING_REVIEW'|'METADATA_REVIEWED')=>({
 id,dispositionId:disposition,proofKind:'DISPOSAL_CERTIFICATE',
 evidenceReference:'DIS-2026-001',documentSha256:'a'.repeat(64),
 explanation:'Recorded disposal paperwork metadata',submittedBy:owner,status:state,
 reviewedBy:state==='PENDING_REVIEW'?null:reviewer,
 reviewReason:state==='PENDING_REVIEW'?null:'Internal fields checked'
})
function fake(handler:(url:string,method:string)=>unknown){
 const calls:Array<{url:string;options:RequestInit}>=[]
 vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit={})=>{
  calls.push({url,options})
  if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
   token:'valid-disposition-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
  return new Response(JSON.stringify(handler(url,options.method??'GET')),{status:200})
 }))
 return calls
}
describe('V51 manual document metadata review',()=>{
 it('retrieves only the warehouse internal proof records',async()=>{
  const calls=fake(()=>[proof('PENDING_REVIEW')])
  const rows=await fetchDispositionProofs(warehouse)
  expect(rows[0].status).toBe('PENDING_REVIEW')
  expect(calls[0].url).toContain('/warehouses/'+warehouse)
 })
 it('stores a hash string as metadata, never claiming to upload or verify the original',async()=>{
  const calls=fake(()=>proof('PENDING_REVIEW'))
  await submitDispositionProof({requestKey:key,dispositionId:disposition,
   proofKind:'DISPOSAL_CERTIFICATE',evidenceReference:'DIS-2026-001',
   documentSha256:'a'.repeat(64),explanation:'Recorded disposal paperwork metadata'})
  const post=calls.find(x=>x.options.method==='POST')
  expect(post?.options.headers).toMatchObject({
   'X-CSRF-TOKEN':'valid-disposition-csrf-token'})
  expect(JSON.parse(String(post?.options.body))).toMatchObject({
   requestKey:key,dispositionId:disposition,documentSha256:'a'.repeat(64)})
 })
 it('internal review does not imply a warehouse debit or external certificate verification',async()=>{
  const calls=fake(()=>proof('METADATA_REVIEWED'))
  const reviewed=await decideDispositionProof(id,{
   decision:'REVIEW',reason:'Internal fields checked'})
  expect(reviewed.status).toBe('METADATA_REVIEWED')
  expect(JSON.parse(String(calls.find(x=>x.options.method==='POST')?.options.body)))
   .toEqual({decision:'REVIEW',reason:'Internal fields checked'})
 })
})
