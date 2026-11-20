import {z} from 'zod'

const link=z.object({
 id:z.string().uuid(),sourceId:z.string().uuid(),reportId:z.string().uuid(),
 lineNumber:z.number().int().positive(),sourceAmount:z.string(),
 sourceCurrency:z.string(),signedLineAmount:z.string(),
 lineCurrency:z.string(),similarity:z.string(),state:z.string(),
 evidenceOrigin:z.string(),recordedBy:z.string().uuid(),
 recordedAt:z.string(),reviewedBy:z.string().nullable(),
 reviewedAt:z.string().nullable(),confirmedDuplicate:z.literal(false),
 financialHoldCleared:z.literal(false),netProfitVerified:z.literal(false)
})
const composite=z.object({
 linkId:z.string().uuid(),
 captureStatus:z.enum(['FROZEN_IMPORTED_LINE_METADATA_UNVERIFIED','CAPTURE_NOT_AVAILABLE']),
 reportId:z.string().uuid(),lineNumber:z.number().int().positive(),
 amazonReportId:z.string().nullable(),reportDocumentId:z.string().nullable(),
 reportDocumentSha256:z.string().nullable(),
 transactionType:z.string().nullable(),amountType:z.string().nullable(),
 amountDescription:z.string().nullable(),externalOrderId:z.string().nullable(),
 lineSignedAmount:z.string().nullable(),currency:z.string().nullable(),
 recordedBy:z.string().nullable(),capturedAt:z.string().nullable(),
 externalChargeVerified:z.literal(false),duplicateDebitVerified:z.literal(false),
 bankVerified:z.literal(false),netProfitEligible:z.literal(false)
})
export type CostLineIdentity=z.infer<typeof composite>
const endpoint=(marketplaceId:string,sourceId:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(marketplaceId)+
 '/cost-sources/'+encodeURIComponent(sourceId)+'/settlement-line-links'
async function get<T>(url:string,schema:z.ZodType<T>):Promise<T>{
 const response=await fetch(url,{
  method:'GET',credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}
 })
 if(!response.ok)throw new Error(response.status===403?'无财务来源权限':
  response.status===404?'当前站点证据不存在':'证据接口 HTTP '+response.status)
 return schema.parse(await response.json())
}
export function fetchCostLineLinks(marketplaceId:string,sourceId:string){
 return get(endpoint(marketplaceId,sourceId),z.array(link).max(100))
}
export function fetchCostLineIdentity(marketplaceId:string,sourceId:string,
 linkId:string){
 return get(endpoint(marketplaceId,sourceId)+'/'+encodeURIComponent(linkId)+
  '/composite-identity',composite)
}
