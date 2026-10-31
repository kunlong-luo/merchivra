import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing/quality'
const receipt=z.object({id:z.string().uuid(),orderId:z.string().uuid(),
 lineId:z.string().uuid(),warehouseId:z.string().uuid().nullable(),
 productSkuId:z.string().uuid(),receivedQuantity:z.number().int().positive(),
 receivedAt:z.string(),inspectionId:z.string().uuid().nullable(),
 acceptedQuantity:z.number().int().nonnegative(),rejectedQuantity:z.number().int().nonnegative(),
 heldQuantity:z.number().int().nonnegative()})
const inspection=z.object({id:z.string().uuid(),receiptId:z.string().uuid(),
 warehouseId:z.string().uuid(),productSkuId:z.string().uuid(),
 inspectedQuantity:z.number().int().positive(),acceptedQuantity:z.number().int().nonnegative(),
 rejectedQuantity:z.number().int().nonnegative(),reason:z.string(),
 inspectedBy:z.string().uuid(),createdAt:z.string()})
const supplierReturn=z.object({id:z.string().uuid(),inspectionId:z.string().uuid(),
 supplierId:z.string().uuid(),quantity:z.number().int().positive(),
 reference:z.string().nullable(),
 status:z.enum(['DRAFT','DISPATCHED','CANCELLED']),
 createdBy:z.string().uuid(),createdAt:z.string(),dispatchedAt:z.string().nullable()})
const finding=z.object({id:z.string().uuid(),inspectionId:z.string().uuid(),
 receiptId:z.string().uuid(),ruleCode:z.enum(['QC_REJECTED_PENDING','QC_HOLD_AGED']),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),lastSeenAt:z.string()})
const scan=z.object({id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()})
export type QualityReceipt=z.infer<typeof receipt>
export type QualityInspection=z.infer<typeof inspection>
export type QualitySupplierReturn=z.infer<typeof supplierReturn>
export type QualityFinding=z.infer<typeof finding>
export type QualityFindingState='OPEN'|'ACKNOWLEDGED'|'DONE'

async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('仅 Owner 可以记录本地采购质检与供应商退货')
  if(response.status===404)throw Error('收货、质检或退货记录不存在或不属于当前租户')
  if(response.status===409)throw Error('库存、质检状态或可退数量已变化，请刷新核对')
  throw Error('本地采购质检 API HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export const fetchQualityReceipts=(orderId:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/receipts',z.array(receipt))
export const inspectQualityReceipt=(receiptId:string,body:{
 requestKey:string;acceptedQuantity:number;rejectedQuantity:number;reason:string})=>api(
 base+'/receipts/'+encodeURIComponent(receiptId)+'/inspect',inspection,'POST',body)
export const fetchQualityReturns=(inspectionId:string)=>api(
 base+'/inspections/'+encodeURIComponent(inspectionId)+'/returns',
 z.array(supplierReturn))
export const createQualityReturn=(inspectionId:string,body:{
 requestKey:string;quantity:number;reference:string|null})=>api(
 base+'/inspections/'+encodeURIComponent(inspectionId)+'/returns',
 supplierReturn,'POST',body)
export const actQualityReturn=(id:string,kind:'dispatch'|'cancel')=>api(
 base+'/returns/'+encodeURIComponent(id)+'/'+kind,supplierReturn,'POST')
export const fetchQualityFindings=(filter:'ACTIVE'|'ALL')=>api(
 base+'/findings?filter='+filter,z.array(finding))
export const scanQualityFindings=()=>api(base+'/scan',scan,'POST')
export const changeQualityFinding=(id:string,state:QualityFindingState)=>api(
 base+'/findings/'+encodeURIComponent(id)+'/state',
 z.object({id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])}),
 'POST',{state})
