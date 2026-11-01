import {z} from 'zod'
import {csrfHeader} from './auth'

const amount=z.string().regex(/^-?[0-9]+(?:\.[0-9]+)?$/)
export const refundRecovery=z.object({
 eventId:z.string().uuid(),orderId:z.string().uuid(),
 state:z.enum(['NOT_REQUESTED','PENDING','REVIEWED','REJECTED','STALE']),
 revision:z.number().int().nonnegative(),
 evidenceOrigin:z.literal('MANUAL_UNVERIFIED'),
 requestedBy:z.string().uuid().nullable(),decidedBy:z.string().uuid().nullable(),
 evidenceCurrent:z.boolean(),inventoryReceiptVerified:z.literal(false),
 accountingPosted:z.literal(false),
 exposure:z.object({
  status:z.enum(['MANUAL_EVIDENCE_BALANCED_UNVERIFIED',
   'REFUND_ITEMS_OR_AMOUNT_INCOMPLETE']),
  currency:z.string().regex(/^[A-Z]{3}$/),
  signedRefundTotal:amount,allocatedRefund:amount,remainingRefund:amount,
  historicalCostExposure:amount,claimedRestockCostSensitivity:amount,
  actualCogsReversal:z.null(),inventoryReceiptVerified:z.literal(false),
  ledgerPosted:z.literal(false),
  items:z.array(z.object({
   itemId:z.string(),sku:z.string(),refundedQuantity:z.number().int().positive(),
   disposition:z.enum(['RESTOCKED_CLAIMED','NOT_RESTOCKED','UNKNOWN']),
   signedRefund:amount,historicalCostExposure:amount,
   claimedRestockCostSensitivity:amount
  })).max(100)
 })
})
export type RefundRecovery=z.infer<typeof refundRecovery>
export const refundRecoveryAudit=z.object({
 action:z.enum(['REQUESTED','REOPENED','REVIEWED','REJECTED']),
 revision:z.number().int().positive(),actorId:z.string().uuid(),
 reason:z.string(),occurredAt:z.string()
})
const endpoint=(mp:string,event:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
 '/events/'+encodeURIComponent(event)+'/refund-recovery'
const failure=(status:number)=>status===409?
 '请先完善 V34 退款数量、V35 精确金额分配与历史成本，然后重新审核':
 status===403?'需另一位未参与数量/金额编制的 Owner 独立审批':
 status===404?'当前 Marketplace 未找到关联的退款来源':
 '退款成本风险核对失败（HTTP '+status+'）'
async function read<T>(url:string,schema:z.ZodType<T>):Promise<T>{
 const r=await fetch(url,{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!r.ok)throw new Error(failure(r.status))
 return schema.parse(await r.json())
}
export const getRefundRecovery=(mp:string,event:string)=>
 read(endpoint(mp,event),refundRecovery)
export const getRefundRecoveryAudit=(mp:string,event:string)=>
 read(endpoint(mp,event)+'/audit',z.array(refundRecoveryAudit).max(100))
export async function decideRefundRecovery(mp:string,event:string,
 action:'request'|'approve'|'reject',reason:string){
 if(reason.trim().length<10||reason.length>500||/[\x00-\x1F\x7F]/.test(reason))
  throw new Error('原因必须是 10–500 字的可打印文字')
 const r=await fetch(endpoint(mp,event)+'/'+action,{
  method:'POST',credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),Accept:'application/json',
   'Content-Type':'application/json'},body:JSON.stringify({reason})
 })
 if(!r.ok)throw new Error(failure(r.status))
 return refundRecovery.parse(await r.json())
}
