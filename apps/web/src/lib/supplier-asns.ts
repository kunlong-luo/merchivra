import {z} from 'zod'
import {
 listSupplierAsnNotices,listSupplierAsnNoticeEvents,createSupplierAsnNotice,
 shipSupplierAsnNotice,cancelSupplierAsnNotice,updateSupplierAsnNoticeEta,
 listSupplierAsnFindings,scanSupplierAsnFindings,changeSupplierAsnFindingState
} from '../generated/purchasing-v114'

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
const event=z.object({id:z.string().uuid(),eventType:z.enum([
 'CREATED','SHIPMENT_REPORTED','ETA_UPDATED','CANCELLED','RECEIPT_LINKED']),
 actorUserId:z.string().uuid(),quantity:z.number().int().nullable(),
 oldEta:z.string().nullable(),newEta:z.string().nullable(),note:z.string().nullable(),
 createdAt:z.string()})
export type SupplierAsnEvent=z.infer<typeof event>
const scan=z.object({id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()})
export type SupplierAsn=z.infer<typeof asn>
export type SupplierAsnFinding=z.infer<typeof finding>
export type SupplierAsnFindingState='OPEN'|'ACKNOWLEDGED'|'DONE'
export type NewSupplierAsn={requestKey:string;lineId:string;warehouseId:string;
 quantity:number;expectedArrivalDate:string;trackingReference:string|null}

// V116: source-backed Orval request functions (shared same-origin+Session/CSRF
// transport); Zod remains the runtime fail-closed DTO boundary.
const decode=<T>(promise:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 promise.then(r=>schema.parse(r.data))
export const fetchSupplierAsns=(orderId:string)=>
 decode(listSupplierAsnNotices(orderId),z.array(asn))
export const fetchSupplierAsnEvents=(orderId:string,asnId:string)=>
 decode(listSupplierAsnNoticeEvents(orderId,asnId),z.array(event))
export const createSupplierAsn=(orderId:string,body:NewSupplierAsn)=>
 decode(createSupplierAsnNotice(orderId,body),asn)
export const supplierAsnAction=(orderId:string,asnId:string,kind:'ship'|'cancel')=>
 decode(kind==='ship'?shipSupplierAsnNotice(orderId,asnId):
  cancelSupplierAsnNotice(orderId,asnId),asn)
export const updateSupplierAsnEta=(orderId:string,asnId:string,expectedArrivalDate:string,
 expectedPreviousEta:string,reason:string)=>
 decode(updateSupplierAsnNoticeEta(orderId,asnId,{
  expectedArrivalDate,expectedPreviousEta,reason}),asn)
export const fetchSupplierAsnFindings=(filter:'ACTIVE'|'ALL')=>
 decode(listSupplierAsnFindings({filter}),z.array(finding))
export const scanSupplierAsns=()=>decode(scanSupplierAsnFindings(),scan)
export const supplierAsnFindingState=(id:string,state:SupplierAsnFindingState)=>
 decode(changeSupplierAsnFindingState(id,{state}),
  z.object({id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])}))
