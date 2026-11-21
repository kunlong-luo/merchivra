import {z} from 'zod'
import {csrfHeader} from './auth'

const uuid=z.string().uuid()
const item=z.object({
 id:uuid,sourceId:uuid,warehouseId:uuid,
 nonceHex:z.string().regex(/^[a-f0-9]{64}$/),
 canonicalPayload:z.string().startsWith('MERCHIVRA_WMS_KEY_POSSESSION_V1\n'),
 payloadSha256:z.string().regex(/^[a-f0-9]{64}$/),
 expiresAtUtc:z.string(),issuedBy:uuid,
 verifiedBy:uuid.nullable(),verifiedAtUtc:z.string().nullable(),
 status:z.enum(['AWAITING_SIGNATURE','EXPIRED','SOURCE_REVOKED_HOLD',
  'KEY_POSSESSION_ONLY']),
 sourceActive:z.boolean(),signatureValid:z.boolean(),
 operatorIdentityVerified:z.literal(false),physicalReceiptVerified:z.literal(false),
 inventoryPosted:z.literal(false),cogsReversalEligible:z.literal(false),
 actualCogsReversal:z.null()
})
export type WmsKeyChallenge=z.infer<typeof item>
const root=(source:string)=>'/api/v1/finance/wms-signing-sources/'+
 encodeURIComponent(source)+'/key-possession'
const parse=async (r:Response)=>{
 if(!r.ok)throw new Error('WMS 公钥持有证明 HTTP '+r.status)
 return item.parse(await r.json())
}
export async function listWmsKeyChallenges(source:string){
 const r=await fetch(root(source),{cache:'no-store',credentials:'same-origin',
  headers:{Accept:'application/json'}})
 if(!r.ok)throw new Error('WMS 公钥挑战列表 HTTP '+r.status)
 return z.array(item).max(10).parse(await r.json())
}
export async function issueWmsKeyChallenge(source:string){
 return parse(await fetch(root(source)+'/challenges',{
  method:'POST',cache:'no-store',credentials:'same-origin',
  headers:{...(await csrfHeader()),Accept:'application/json'}}))
}
export async function verifyWmsKeyChallenge(source:string,challenge:string,
 signatureBase64:string){
 return parse(await fetch(root(source)+'/challenges/'+encodeURIComponent(challenge)+
  '/verify',{method:'POST',cache:'no-store',credentials:'same-origin',
  headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify({signatureBase64})}))
}
