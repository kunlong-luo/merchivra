import {z} from 'zod'
import {csrfHeader} from './auth'

const base='/api/v1/purchasing/supplier-asns'
const asn=z.object({id:z.string().uuid(),orderId:z.string().uuid(),lineId:z.string().uuid(),
 warehouseId:z.string().uuid(),quantity:z.number().int().positive(),
 receivedQuantity:z.number().int().nonnegative(),expectedArrivalDate:z.string(),
 trackingReference:z.string().nullable(),
 status:z.enum(['DRAFT','SHIPPED','CANCELLED']),
 dispatchReportedAt:z.string().nullable(),createdAt:z.string()})
const finding=z.object({id:z.string().uuid(),asnId:z.string().uuid(),
 orderId:z.string().uuid(),trackingReference:z.string().nullable(),
 ruleCode:z.enum(['SUPPLIER_ASN_OVERDUE','SUPPLIER_ASN_DUE_SOON']),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),
 expectedArrivalDate:z.string(),lastSeenAt:z.string()})
const scan=z.object({id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()})
export type SupplierAsn=z.infer<typeof asn>
export type SupplierAsnFinding=z.infer<typeof finding>
export type SupplierAsnFindingState='OPEN'|'ACKNOWLEDGED'|'DONE'
export type NewSupplierAsn={requestKey:string;lineId:string;warehouseId:string;
 quantity:number;expectedArrivalDate:string;trackingReference:string|null}

async function api<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(url,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===403)throw Error('当前账号无供应商预到货操作权限')
  if(response.status===404)throw Error('ASN、采购单或仓库不属于当前租户或不可用')
  if(response.status===409)throw Error('ASN 状态、收货数量或预计日期已变化，请刷新后确认')
  throw Error('供应商 ASN 操作失败：HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export const fetchSupplierAsns=(orderId:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId),z.array(asn))
export const createSupplierAsn=(orderId:string,body:NewSupplierAsn)=>api(
 base+'/orders/'+encodeURIComponent(orderId),asn,'POST',body)
export const supplierAsnAction=(orderId:string,asnId:string,kind:'ship'|'cancel')=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/'+encodeURIComponent(asnId)+'/'+kind,
 asn,'POST')
export const updateSupplierAsnEta=(orderId:string,asnId:string,expectedArrivalDate:string,
 expectedPreviousEta:string,reason:string)=>api(
 base+'/orders/'+encodeURIComponent(orderId)+'/'+encodeURIComponent(asnId)+'/eta',
 asn,'POST',{expectedArrivalDate,expectedPreviousEta,reason})
export const fetchSupplierAsnFindings=(filter:'ACTIVE'|'ALL')=>api(
 base+'/findings?filter='+filter,z.array(finding))
export const scanSupplierAsns=()=>api(base+'/scan',scan,'POST')
export const supplierAsnFindingState=(id:string,state:SupplierAsnFindingState)=>api(
 base+'/findings/'+encodeURIComponent(id)+'/state',
 z.object({id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])}),
 'POST',{state})
