import {z} from 'zod'
import {csrfHeader} from './auth'
const root='/api/v1/purchasing/lot-reconciliation'
const id=z.string().uuid()
const count=z.object({
 id,observationActionId:id,positionId:id,warehouseId:id,productSkuId:id,
 expectedBinId:id,expectedPositionQuantity:z.number().int().nonnegative(),
 observedQuantity:z.number().int().nonnegative(),expectedOnHand:z.number().int().nonnegative(),
 reason:z.string(),status:z.enum([
  'PENDING_REVIEW','APPLIED','CONFIRMED_NO_CHANGE','EVIDENCE_REQUIRED','REJECTED']),
 submittedBy:id,reviewedBy:id.nullable(),operationId:id.nullable(),
 reviewReason:z.string().nullable()
})
const transfer=z.object({actionId:id,destinationWarehouseId:id,
 sourcePositionId:id,destinationPositionId:id,
 expectedQuantity:z.number().int().positive(),
 receivedQuantity:z.number().int().nonnegative(),
 status:z.enum(['POSTED_AWAITING_DESTINATION','CONFIRMED','MISMATCH']),
 transferredBy:id,acknowledgedBy:id.nullable(),reference:z.string().nullable()})
const disposition=z.object({
 id,quarantineActionId:id,warehouseId:id,quantity:z.number().int().positive(),
 method:z.enum(['DISPOSAL','SUPPLIER_RETURN']),
 status:z.enum(['PENDING_APPROVAL','APPROVED','REJECTED','EXECUTED_REPORTED']),
 reason:z.string(),proposedBy:id,reviewedBy:id.nullable(),
 completedBy:id.nullable(),completionReference:z.string().nullable()
})
export type LotCountCase=z.infer<typeof count>
export type LotTransferReceipt=z.infer<typeof transfer>
export type LotDisposition=z.infer<typeof disposition>
async function call<T>(url:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const res=await fetch(url,{method,cache:'no-store',credentials:'same-origin',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!res.ok) {
  if(res.status===403)throw Error('需要当前租户 Owner 权限及有效的登录会话')
  if(res.status===404)throw Error('该批次或证据不存在，或不属于当前租户')
  if(res.status===409)throw Error('证据已被处理，或库存/来源发生变化，请刷新重新核对')
  throw Error('V50 业务审核 API HTTP '+res.status)
 }
 return schema.parse(await res.json())
}
export const fetchLotCountCases=(warehouseId:string)=>call(
 root+'/warehouses/'+encodeURIComponent(warehouseId)+'/counts',z.array(count))
export const submitLotCount=(body:{requestKey:string;observationActionId:string;reason:string})=>
 call(root+'/counts',count,'POST',body)
export const decideLotCount=(id:string,body:{decision:'APPROVE'|'REJECT';reason:string})=>
 call(root+'/counts/'+encodeURIComponent(id)+'/decision',count,'POST',body)
export const fetchDestinationTransfers=(warehouseId:string)=>call(
 root+'/warehouses/'+encodeURIComponent(warehouseId)+'/transfers',z.array(transfer))
export const acknowledgeDestinationTransfer=(id:string,body:{
 requestKey:string;receivedQuantity:number;reference:string;reason:string
})=>call(root+'/transfers/'+encodeURIComponent(id)+'/acknowledge',transfer,'POST',body)
export const fetchLotDispositions=(warehouseId:string)=>call(
 root+'/warehouses/'+encodeURIComponent(warehouseId)+'/dispositions',
 z.array(disposition))
export const fetchUnplannedQuarantines=(warehouseId:string)=>call(
 root+'/warehouses/'+encodeURIComponent(warehouseId)+'/unplanned-quarantines',
 z.array(id))
export const proposeLotDisposition=(body:{
 requestKey:string;quarantineActionId:string;
 method:'DISPOSAL'|'SUPPLIER_RETURN';reason:string
})=>call(root+'/dispositions',disposition,'POST',body)
export const decideLotDisposition=(id:string,body:{
 decision:'APPROVE'|'REJECT';reason:string
})=>call(root+'/dispositions/'+encodeURIComponent(id)+'/decision',
 disposition,'POST',body)
export const reportLotDispositionCompletion=(id:string,body:{
 reference:string;reason:string
})=>call(root+'/dispositions/'+encodeURIComponent(id)+'/completion',
 disposition,'POST',body)

const pageOf=<T extends z.ZodTypeAny>(schema:T)=>z.object({
 items:z.array(schema),nextCursor:z.string().nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().positive()
})
function pageUrl(warehouseId:string,kind:string,cursor:string|null,limit=50){
 const query=new URLSearchParams({limit:String(limit)})
 if(cursor)query.set('cursor',cursor)
 return root+'/warehouses/'+encodeURIComponent(id.parse(warehouseId))+'/'+kind+
  '/page?'+query.toString()
}
export const fetchLotCountCasesPage=(warehouseId:string,cursor:string|null=null)=>
 call(pageUrl(warehouseId,'counts',cursor),pageOf(count))
export const fetchDestinationTransfersPage=(warehouseId:string,cursor:string|null=null)=>
 call(pageUrl(warehouseId,'transfers',cursor),pageOf(transfer))
export const fetchLotDispositionsPage=(warehouseId:string,cursor:string|null=null)=>
 call(pageUrl(warehouseId,'dispositions',cursor),pageOf(disposition))
