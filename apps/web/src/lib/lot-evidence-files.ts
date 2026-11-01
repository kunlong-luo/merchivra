import {z} from 'zod'
import {csrfHeader} from './auth'

const id=z.string().uuid()
const info=z.object({id,proofId:id,mediaType:z.enum([
 'application/pdf','image/png','image/jpeg']),
 sizeBytes:z.number().int().min(1).max(2097152),
 sha256:z.string().regex(/^[a-f0-9]{64}$/),uploadedBy:id})
const trace=z.object({id,dispositionId:id,sourceReceiptId:id,
 supplierId:id,quantity:z.number().int().positive(),
 carrierReference:z.string(),reason:z.string(),
 status:z.enum(['DISPATCH_REPORTED','SUPPLIER_RECEIPT_REPORTED','RECEIPT_MISMATCH']),
 dispatchedBy:id,receivedQuantity:z.number().int().nonnegative().nullable(),
 receiptReference:z.string().nullable(),receivedReportedBy:id.nullable()})
export type LotUploadedProof=z.infer<typeof info>
export type LotSupplierReturnTrace=z.infer<typeof trace>
const fileRoot='/api/v1/purchasing/lot-proof-files'
const returnRoot='/api/v1/purchasing/lot-supplier-returns'
async function request<T>(path:string,schema:z.ZodType<T>,method='GET',
 body?:unknown):Promise<T>{
 const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',
   ...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok)throw Error('本地凭据/退货 API HTTP '+response.status)
 return schema.parse(await response.json())
}
export async function fetchProofFileInfo(proofId:string){
 const res=await fetch(fileRoot+'/'+encodeURIComponent(proofId),{
  credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
 if(res.status===404)return null
 if(!res.ok)throw Error('文件元数据 HTTP '+res.status)
 return info.parse(await res.json())
}
export async function uploadProofFile(proofId:string,requestKey:string,file:File){
 if(!['application/pdf','image/png','image/jpeg'].includes(file.type))
  throw Error('仅支持 PDF、PNG、JPEG')
 if(file.size<1||file.size>2097152)throw Error('文件必须为 1 字节至 2 MiB')
 const form=new FormData()
 form.append('file',file)
 const params=new URLSearchParams({requestKey})
 const res=await fetch(fileRoot+'/'+encodeURIComponent(proofId)+'?'+params,{
  method:'POST',credentials:'same-origin',headers:await csrfHeader(),body:form})
 if(!res.ok)throw Error(res.status===409?
  '文件与已提交的哈希不符，或已有不可覆盖的附件':'上传失败 HTTP '+res.status)
 return info.parse(await res.json())
}
export function proofDownloadHref(proofId:string){
 return fileRoot+'/'+encodeURIComponent(proofId)+'/download'
}
export const fetchSupplierReturnTraces=(warehouseId:string)=>request(
 returnRoot+'/warehouses/'+encodeURIComponent(warehouseId),z.array(trace))
export const reportSupplierReturnDispatch=(body:{
 requestKey:string;dispositionId:string;carrierReference:string;reason:string
})=>request(returnRoot,trace,'POST',body)
export const reportSupplierReturnReceipt=(id:string,body:{
 receivedQuantity:number;receiptReference:string;reason:string
})=>request(returnRoot+'/'+encodeURIComponent(id)+'/receipt',trace,'POST',body)
