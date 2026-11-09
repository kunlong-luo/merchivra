import {z} from 'zod'
import {
 listLocalSupplierReturnTracesLegacy,listLocalSupplierReturnTracesPage,
 dispatchLocalSupplierReturn,reportLocalSupplierReturnReceipt
} from '../generated/purchasing-v114'

/** V120: V52 return traces are LOCAL reports from already executed dispositions,
 * not carrier-confirmed or supplier-verified deliveries and not stock ledger writes. */
const id=z.string().uuid()
const trace=z.object({
 id,dispositionId:id,sourceReceiptId:id,supplierId:id,
 quantity:z.number().int().positive(),carrierReference:z.string(),reason:z.string(),
 status:z.enum(['DISPATCH_REPORTED','SUPPLIER_RECEIPT_REPORTED','RECEIPT_MISMATCH']),
 dispatchedBy:id,receivedQuantity:z.number().int().nonnegative().nullable(),
 receiptReference:z.string().nullable(),receivedReportedBy:id.nullable()
})
const page=z.object({
 items:z.array(trace).max(100),nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100)
}).refine(v=>v.hasMore===(v.nextCursor!==null),{
 message:'供应商退货追踪分页游标不一致'
})
export type LocalSupplierReturnTrace=z.infer<typeof trace>
export const fetchSupplierReturnTraces=(warehouseId:string)=>
 listLocalSupplierReturnTracesLegacy(id.parse(warehouseId))
  .then(r=>z.array(trace).max(200).parse(r.data))
export const fetchSupplierReturnTracesPage=(warehouseId:string,cursor:string|null=null)=>
 listLocalSupplierReturnTracesPage(id.parse(warehouseId),
  {limit:50,...(cursor?{cursor}:{})}).then(r=>page.parse(r.data))
export const reportSupplierReturnDispatch=(body:{
 requestKey:string;dispositionId:string;carrierReference:string;reason:string
})=>dispatchLocalSupplierReturn(body).then(r=>trace.parse(r.data))
export const reportSupplierReturnReceipt=(traceId:string,body:{
 receivedQuantity:number;receiptReference:string;reason:string
})=>reportLocalSupplierReturnReceipt(id.parse(traceId),body)
 .then(r=>trace.parse(r.data))
