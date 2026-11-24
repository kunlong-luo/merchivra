import {z} from 'zod'

/**
 * V217 consumes the actual V216 Springdoc GET contract.
 * Original-currency V112 manual proposals are NOT verified landed costs.
 * This typed boundary rejects any real COGS / profit promotion by the server.
 */
const id=z.string().uuid()
const count=z.number().int().nonnegative()
const amount=z.string().regex(/^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?$/)
const planState=z.enum(['PENDING_REVIEW','APPROVED_UNVERIFIED','REJECTED'])
const integrity=z.enum([
 'LOCAL_RECEIPT_ALLOCATION_INTACT_UNVERIFIED',
 'POTENTIAL_EXTERNAL_CHARGE_IDENTITY_COLLISION_HOLD',
 'LOCAL_RECEIPT_EVIDENCE_STALE_OR_UNAUDITED',
 'PENDING_LOCAL_REVIEW','REJECTED_LOCAL_PROPOSAL'
])

const receiptPlan=z.object({
 planId:id,sourceId:id,state:planState,
 originalCurrency:z.string().regex(/^[A-Z]{3}$/),
 localReceiptCandidateAmount:amount,
 localAllReceiptsCandidateAmount:amount,
 globalReceiptCount:count,
 crossSourceIdentitySignalCount:count,
 sharedSettlementLineCount:count,
 integrityState:integrity,
 blockerCodes:z.array(z.string()),
 independentlyVerifiedLandedCost:z.literal(false),
 actualLandedCost:z.null()
}).superRefine((p,ctx)=>{
 // Compare six-decimal original amounts exactly: never JS floating-point FX.
 const units=(v:string)=>{
  const [whole,fraction='']=v.split('.')
  return BigInt(whole)*1_000_000n+BigInt(fraction.padEnd(6,'0')||'0')
 }
 if(units(p.localReceiptCandidateAmount)>units(p.localAllReceiptsCandidateAmount))
  ctx.addIssue({code:'custom',message:'当前收货费用超出来源计划原币总额'})
})

export const receiptCostIntegrityV217=z.object({
 marketplaceId:id,purchaseReceiptId:id,
 ruleVersion:z.literal('V216_RECEIPT_V112_V91_V92_V165_READ_GATE_V1'),
 sourceBasis:z.literal(
  'V112_RECEIPT_EXACT_V91_V92_SOURCE_AND_V165_LOCAL_COLLISION_UNVERIFIED'),
 localPlanCount:count,plans:z.array(receiptPlan).max(50),
 blockerCodes:z.array(z.string()).min(4),
 completeExternalCostCoverage:z.literal(false),
 receiptLandedCostVerified:z.literal(false),
 actualBatchCogsVerified:z.literal(false),
 actualBatchCogs:z.null(),
 postingAllowed:z.literal(false),
 realizedNetProfit:z.null()
}).superRefine((p,ctx)=>{
 if(p.localPlanCount!==p.plans.length)
  ctx.addIssue({code:'custom',message:'采购收货费用计划未完整返回'})
 if(new Set(p.plans.map(x=>x.planId)).size!==p.plans.length
    ||new Set(p.plans.map(x=>x.sourceId)).size!==p.plans.length)
  ctx.addIssue({code:'custom',message:'费用归属来源存在重复，禁止重复统计'})
 if(p.localPlanCount===0&&!p.blockerCodes.includes(
  'NO_LOCAL_RECEIPT_COST_PROPOSAL_UNKNOWN_NOT_ZERO'))
  ctx.addIssue({code:'custom',message:'缺乏费用证据不能当作成本为零'})
})

export type ReceiptCostIntegrityV217=z.infer<typeof receiptCostIntegrityV217>
export const validPurchaseReceiptId=(value:string)=>id.safeParse(value.trim()).success

export async function getReceiptCostIntegrityV217(marketplaceId:string,receiptId:string,
 signal?:AbortSignal):
 Promise<ReceiptCostIntegrityV217> {
 const mp=id.parse(marketplaceId),receipt=id.parse(receiptId)
 const res=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
  '/purchase-receipts/'+encodeURIComponent(receipt)+'/manual-cost-integrity',{
  method:'GET',credentials:'same-origin',cache:'no-store',signal,
  headers:{Accept:'application/json'}
 })
 if(!res.ok){
  if(res.status===401)throw Error('登录已过期，无法读取采购费用来源')
  if(res.status===403)throw Error('当前账号没有 Owner 费用审计权限')
  if(res.status===404)throw Error('采购收货或所选费用来源 Marketplace 不可访问')
  if(res.status===409)throw Error(
   '费用计划超出全量上限，或来源、币种、分配、审计证据发生冲突；不可展示部分计算')
  throw Error('采购收货费用审计暂不可用（HTTP '+res.status+'）')
 }
 const parsed=receiptCostIntegrityV217.parse(await res.json())
 if(parsed.marketplaceId!==mp||parsed.purchaseReceiptId!==receipt)
  throw Error('服务端费用证据与当前 Marketplace/收货单不一致')
 return parsed
}
