import {z} from 'zod'
import {getPurchaseReceiptCostCandidateV252} from '../generated/manual-cost-v228'

const id=z.string().uuid()
const amount=z.string().regex(/^[0-9]{1,16}\.[0-9]{6}$/)
const currency=z.string().regex(/^[A-Z]{3}$/)
const qty=z.number().int().nonnegative().max(2147483647)
const source=z.object({
 sourceId:id.nullable(),category:z.enum(['PURCHASE_PO_REFERENCE','INBOUND_FREIGHT','IMPORT_DUTY']),
 originalCurrency:currency,basisQuantity:qty,allocatedQuantity:qty,unallocatedQuantity:qty,
 receiptCandidateAmount:amount,allocatedCandidateAmount:amount,unallocatedCandidateAmount:amount
})
// Exact decimal micro-units; no floating-point, FX or inferred zero.
const units=(v:string)=>BigInt(v.replace('.',''))
export const receiptCostCandidateV253=z.object({
 marketplaceId:id,purchaseReceiptId:id,
 state:z.enum(['LOCAL_COST_COMPOSITION_HOLD','LOCAL_PO_FREIGHT_DUTY_CANDIDATE_UNVERIFIED']),
 originalCurrency:currency.nullable(),receiptCandidateCost:amount.nullable(),
 allocatedCandidateCost:amount.nullable(),unallocatedCandidateCost:amount.nullable(),
 components:z.array(source).max(51),
 saleShares:z.array(z.object({claimId:id,candidateCost:amount})).max(1000),
 blockerCodes:z.array(z.string().min(1)).min(1),
 completeExternalCostCoverage:z.literal(false),actualBatchCogsVerified:z.literal(false),
 actualBatchCogs:z.null(),postingAllowed:z.literal(false),realizedNetProfit:z.null()
}).superRefine((d,ctx)=>{
 const fail=(message:string)=>ctx.addIssue({code:'custom',message})
 if(d.state==='LOCAL_COST_COMPOSITION_HOLD'){
  if(d.originalCurrency!==null||d.receiptCandidateCost!==null||
    d.allocatedCandidateCost!==null||d.unallocatedCandidateCost!==null||
    d.components.length||d.saleShares.length)
   fail('阻断状态不能携带部分金额')
  return
 }
 if(d.originalCurrency===null||d.receiptCandidateCost===null||
   d.allocatedCandidateCost===null||d.unallocatedCandidateCost===null||
   d.components.length<3||!d.saleShares.length){
  fail('候选成本组成不完整');return
 }
 // superRefine may run after a nonfatal regex error. Guard before BigInt.
 const values=[d.receiptCandidateCost,d.allocatedCandidateCost,d.unallocatedCandidateCost,
  ...d.components.flatMap(c=>[c.receiptCandidateAmount,c.allocatedCandidateAmount,c.unallocatedCandidateAmount]),
  ...d.saleShares.map(s=>s.candidateCost)]
 if(values.some(v=>!amount.safeParse(v).success)){fail('金额格式无效');return}
 const po=d.components.filter(c=>c.category==='PURCHASE_PO_REFERENCE')
 const others=d.components.filter(c=>c.category!=='PURCHASE_PO_REFERENCE')
 const first=d.components[0]
 if(po.length!==1||po[0]?.sourceId!==null||others.some(c=>c.sourceId===null)||
   new Set(others.map(c=>c.sourceId)).size!==others.length||
   !others.some(c=>c.category==='INBOUND_FREIGHT')||!others.some(c=>c.category==='IMPORT_DUTY'))
  fail('来源重复或缺失')
 if(new Set(d.saleShares.map(s=>s.claimId)).size!==d.saleShares.length)
  fail('销售认领重复')
 if(d.components.some(c=>c.originalCurrency!==d.originalCurrency||
   c.basisQuantity<=0||c.basisQuantity!==first.basisQuantity||
   c.allocatedQuantity!==first.allocatedQuantity||c.unallocatedQuantity!==first.unallocatedQuantity||
   c.allocatedQuantity+c.unallocatedQuantity!==c.basisQuantity||
   units(c.receiptCandidateAmount)!==units(c.allocatedCandidateAmount)+units(c.unallocatedCandidateAmount)))
  fail('原币、数量或单来源金额不守恒')
 const sum=(key:'receiptCandidateAmount'|'allocatedCandidateAmount'|'unallocatedCandidateAmount')=>
  d.components.reduce((n,c)=>n+units(c[key]),0n)
 if(sum('receiptCandidateAmount')!==units(d.receiptCandidateCost)||
   sum('allocatedCandidateAmount')!==units(d.allocatedCandidateCost)||
   sum('unallocatedCandidateAmount')!==units(d.unallocatedCandidateCost)||
   units(d.receiptCandidateCost)!==units(d.allocatedCandidateCost)+units(d.unallocatedCandidateCost)||
   d.saleShares.reduce((n,s)=>n+units(s.candidateCost),0n)!==units(d.allocatedCandidateCost))
  fail('批次或销售金额不守恒')
})
export type ReceiptCostCandidateV253=z.infer<typeof receiptCostCandidateV253>
export async function fetchReceiptCostCandidateV253(marketplaceId:string,receiptId:string,signal?:AbortSignal){
 const mp=id.parse(marketplaceId),receipt=id.parse(receiptId)
 const response=await getPurchaseReceiptCostCandidateV252(mp,receipt,{signal})
 if(response.status!==200||!response.headers.get('Cache-Control')?.includes('no-store'))
  throw Error('采购成本响应没有安全 no-store 契约')
 const result=receiptCostCandidateV253.parse(response.data)
 if(result.marketplaceId!==mp||result.purchaseReceiptId!==receipt)
  throw Error('成本响应不属于当前收货单或 Marketplace')
 return result
}
