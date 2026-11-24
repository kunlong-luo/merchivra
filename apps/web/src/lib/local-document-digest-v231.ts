/** V231: OPTIONAL local-only original-byte SHA-256 comparison.
 *
 * This matches the *user's own claimed* V91 digest, not a platform signature,
 * issuing authority, original bank debit, approved COGS or real net profit.
 * Do not send, persist, upload, log or return file content or file name.
 */
export const MAX_LOCAL_DIGEST_BYTES_V231=8*1024*1024
export type LocalDigestResultV231={
 status:'LOCAL_BYTES_MATCH_USER_CLAIM_ONLY'|'LOCAL_BYTES_MISMATCH_HOLD'
 /** The file remains externally UNVERIFIED even when the bytes match. */
 independentlyIssued:false
 externalChargeVerified:false
 actualCogsVerified:false
 realizedNetProfit:null
}

export async function compareLocalDocumentDigestV231(
 file:Pick<Blob,'size'|'arrayBuffer'>,claimedSha256:string
):Promise<LocalDigestResultV231>{
 if(!/^[0-9a-f]{64}$/.test(claimedSha256)||
  claimedSha256==='0'.repeat(64))
  throw new Error('费用来源的声明 SHA-256 无效，禁止比较')
 if(!Number.isSafeInteger(file.size)||file.size<1||
  file.size>MAX_LOCAL_DIGEST_BYTES_V231)
  throw new Error('仅可本地比较 1 B–8 MiB 的原始文件，不读取超限或空文件')
 if(!globalThis.crypto?.subtle?.digest)
  throw new Error('当前浏览器不支持安全 Web Crypto；未比较任何文件')
 // The only sensitive input is the caller's in-memory browser File/Blob.
 // No fetch(), no server side job and no mutation of the V91 audit history.
 const bytes=await file.arrayBuffer()
 try{
  if(bytes.byteLength!==file.size)
   throw new Error('文件读取时长度变化，无法确认内容字节完整')
  const sha=await globalThis.crypto.subtle.digest('SHA-256',bytes)
  const computed=Array.from(new Uint8Array(sha),x=>x.toString(16).padStart(2,'0'))
   .join('')
  return {
   status:computed===claimedSha256
    ?'LOCAL_BYTES_MATCH_USER_CLAIM_ONLY':'LOCAL_BYTES_MISMATCH_HOLD',
   independentlyIssued:false,
   externalChargeVerified:false,
   actualCogsVerified:false,
   realizedNetProfit:null
  }
 }finally{
  // Best-effort buffer clearing. JS runtimes may retain internal copies.
  new Uint8Array(bytes).fill(0)
 }
}
