import {z} from 'zod'
import {
 listLotExceptionResolutionsLegacy,listLotExceptionResolutionsPage,
 proposeLotExceptionResolution,decideLotExceptionResolution
} from '../generated/purchasing-v114'
const id=z.string().uuid()
const resolution=z.object({
 id,resolutionType:z.enum(['RELEASED_SURPLUS_BIND','TRANSFER_SHORTAGE']),
 countReviewId:id.nullable(),transferActionId:id.nullable(),
 sourceReceiptId:id.nullable(),positionId:id,warehouseId:id,productSkuId:id,
 expectedBinId:id,expectedPositionQuantity:z.number().int().nonnegative(),
 expectedOnHand:z.number().int().nonnegative(),
 adjustmentQuantity:z.number().int().positive(),
 evidenceReference:z.string(),reason:z.string(),
 status:z.enum(['PENDING_REVIEW','APPLIED','REJECTED']),
 proposedBy:id,reviewedBy:id.nullable(),reviewReason:z.string().nullable(),
 operationId:id.nullable()
})
export type LotExceptionResolution=z.infer<typeof resolution>
export type NewLotException={
 requestKey:string;resolutionType:LotExceptionResolution['resolutionType'];
 sourceId:string;sourceReceiptId:string|null;evidenceReference:string;reason:string
}
// The business state machine remains server-side; generated Orval paths
// use the shared Cookie/CSRF/no-store transport and Zod checks the real DTO.
const pathId=(value:string)=>id.parse(value)
const decode=<T>(response:Promise<{data:unknown}>,schema:z.ZodType<T>):Promise<T>=>
 response.then(r=>schema.parse(r.data))
const pageSchema=z.object({
 items:z.array(resolution).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(p=>p.hasMore===(p.nextCursor!==null),{message:'Invalid lot resolution cursor'})
export type LotExceptionFilter='PENDING'|'ALL'
export const fetchLotExceptions=(warehouseId:string)=>
 decode(listLotExceptionResolutionsLegacy(pathId(warehouseId)),z.array(resolution))
export const fetchLotExceptionsPage=(warehouseId:string,
 filter:LotExceptionFilter='PENDING',cursor:string|null=null)=>
 decode(listLotExceptionResolutionsPage(pathId(warehouseId),
  {filter,limit:50,...(cursor?{cursor}:{})}),pageSchema)
export const proposeLotException=(body:NewLotException)=>
 decode(proposeLotExceptionResolution(body),resolution)
export const decideLotException=(id:string,body:{
 decision:'APPROVE'|'REJECT';reason:string
})=>decode(decideLotExceptionResolution(pathId(id),body),resolution)
