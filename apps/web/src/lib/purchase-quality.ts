import {z} from 'zod'
import {
 listQualityReceiptsLegacy,listQualityReceiptsPage,inspectQualityReceipt as requestInspect,
 listQualityReturnsLegacy,listQualityReturnsPage,getQualityReturnSummary,
 createQualitySupplierReturn,dispatchQualitySupplierReturn,cancelQualitySupplierReturn,
 listQualityFindingsLegacy,listQualityFindingsPage,
 scanQualityFindings as requestScan,changeQualityFindingState
} from '../generated/purchasing-v114'

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

// Orval V116 owns transport (same-origin Session, CSRF, no-store).
// Keep Zod DTO validation and keyset cursor invariants.
// Orval path templates interpolate raw strings: require canonical UUIDs first.
const pathId=(id:string)=>z.string().uuid().parse(id)
const decode=<T>(promise:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 promise.then(r=>schema.parse(r.data))
const cursorPage=<T extends z.ZodTypeAny>(item:T)=>z.object({
 items:z.array(item).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid QC cursor'})
const returnSummary=z.object({rejectedUnits:z.number().int().nonnegative(),
 draftedUnits:z.number().int().nonnegative(),dispatchedUnits:z.number().int().nonnegative(),
 unallocatedUnits:z.number().int().nonnegative()})
export const fetchQualityReceiptsPage=(orderId:string,cursor:string|null=null)=>
 decode(listQualityReceiptsPage(pathId(orderId),{limit:50,...(cursor?{cursor}:{})}),
  cursorPage(receipt))
export const fetchQualityReceipts=(orderId:string)=>
 decode(listQualityReceiptsLegacy(pathId(orderId)),z.array(receipt))
export const inspectQualityReceipt=(receiptId:string,body:{
 requestKey:string;acceptedQuantity:number;rejectedQuantity:number;reason:string})=>
 decode(requestInspect(pathId(receiptId),body),inspection)
export const fetchQualityReturnsPage=(inspectionId:string,cursor:string|null=null)=>
 decode(listQualityReturnsPage(pathId(inspectionId),{limit:50,...(cursor?{cursor}:{})}),
  cursorPage(supplierReturn))
export const fetchQualityReturnSummary=(inspectionId:string)=>
 decode(getQualityReturnSummary(pathId(inspectionId)),returnSummary)
export const fetchQualityFindingsPage=(filter:'ACTIVE'|'ALL',cursor:string|null=null)=>
 decode(listQualityFindingsPage({limit:50,filter,...(cursor?{cursor}:{})}),
  cursorPage(finding))
export const fetchQualityReturns=(inspectionId:string)=>
 decode(listQualityReturnsLegacy(pathId(inspectionId)),z.array(supplierReturn))
export const createQualityReturn=(inspectionId:string,body:{
 requestKey:string;quantity:number;reference:string|null})=>
 decode(createQualitySupplierReturn(pathId(inspectionId),body),supplierReturn)
export const actQualityReturn=(id:string,kind:'dispatch'|'cancel')=>
 decode(kind==='dispatch'?dispatchQualitySupplierReturn(pathId(id)):
  cancelQualitySupplierReturn(pathId(id)),supplierReturn)
export const fetchQualityFindings=(filter:'ACTIVE'|'ALL')=>
 decode(listQualityFindingsLegacy({filter}),z.array(finding))
export const scanQualityFindings=()=>decode(requestScan(),scan)
export const changeQualityFinding=(id:string,state:QualityFindingState)=>
 decode(changeQualityFindingState(pathId(id),{state}),
  z.object({id:z.string().uuid(),state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])}))
