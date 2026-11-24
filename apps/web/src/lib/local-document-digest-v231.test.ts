import {describe,expect,it,vi} from 'vitest'
import {
 compareLocalDocumentDigestV231,MAX_LOCAL_DIGEST_BYTES_V231
} from './local-document-digest-v231'

// No remote network or real source bytes are used in these synthetic tests.
const claim='ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
describe('V231 locally rehash raw bytes; never certify issuer or costs',()=>{
 it('matches exact SHA256 of the UTF-8 binary fixture but not external truth',async()=>{
  const result=await compareLocalDocumentDigestV231(
   new Blob([new Uint8Array([97,98,99])]),claim)
  expect(result).toEqual({
   status:'LOCAL_BYTES_MATCH_USER_CLAIM_ONLY',
   independentlyIssued:false,externalChargeVerified:false,
   actualCogsVerified:false,realizedNetProfit:null
  })
 })
 it('different bytes fail closed even if user supplied valid-format claimed hash',async()=>{
  const result=await compareLocalDocumentDigestV231(
   new Blob([new Uint8Array([97,98,100])]),claim)
  expect(result.status).toBe('LOCAL_BYTES_MISMATCH_HOLD')
  expect(result.independentlyIssued).toBe(false)
  expect(result.realizedNetProfit).toBeNull()
 })
 it('rejects blank, uppercase, truncated, null and oversized evidence',async()=>{
  for(const invalid of ['0'.repeat(64),claim.toUpperCase(),claim.slice(1),'']){
   await expect(compareLocalDocumentDigestV231(new Blob(['abc']),invalid))
    .rejects.toThrow('SHA-256')
  }
  await expect(compareLocalDocumentDigestV231(new Blob([]),claim))
   .rejects.toThrow('1 B')
  const oversized={size:MAX_LOCAL_DIGEST_BYTES_V231+1,
   arrayBuffer:vi.fn().mockResolvedValue(new ArrayBuffer(0))}
  await expect(compareLocalDocumentDigestV231(oversized,claim))
   .rejects.toThrow('1 B')
  expect(oversized.arrayBuffer).not.toHaveBeenCalled()
 })
 it('a short/changed read is rejected, and selected bytes are cleared',async()=>{
  const bytes=new Uint8Array([97,98,99])
  await expect(compareLocalDocumentDigestV231({size:4,
   arrayBuffer:async()=>bytes.buffer},claim))
   .rejects.toThrow('长度变化')
  expect(Array.from(bytes)).toEqual([0,0,0])
 })
 it('never posts document bytes through browser network functions',async()=>{
  const fetchSpy=vi.spyOn(globalThis,'fetch')
  try{
   await compareLocalDocumentDigestV231(new Blob(['abc']),claim)
   expect(fetchSpy).not.toHaveBeenCalled()
  }finally{fetchSpy.mockRestore()}
 })
})
