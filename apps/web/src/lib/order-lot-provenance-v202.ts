import {z} from 'zod'

const sku=z.object({
 internalSku:z.string(),
 priorLocalReceiptCount:z.number().int().nonnegative(),
 legacyNoQcBatchCount:z.number().int().nonnegative(),
 pendingQcBatchCount:z.number().int().nonnegative(),
 inspectedUnreleasedBatchCount:z.number().int().nonnegative(),
 releasedBatchCount:z.number().int().nonnegative(),
 receiptIdentityConflictCount:z.number().int().nonnegative(),
 qcIdentityConflictCount:z.number().int().nonnegative(),
 releaseLedgerConflictCount:z.number().int().nonnegative(),
 observedReleasedLocalUnits:z.number().int().nonnegative(),
 distinctPurchaseCurrencyCount:z.number().int().nonnegative(),
 distinctPurchaseUnitCostCount:z.number().int().nonnegative(),
 singlePurchaseCurrency:z.string().nullable(),
 singlePurchaseUnitCost:z.string().nullable(),
 status:z.enum([
  'NO_PRIOR_LOCAL_RECEIPT','LOCAL_RECEIPT_OR_QC_IDENTITY_CONFLICT',
  'LEGACY_LOCAL_RECEIPT_NO_QC_BATCH',
  'MULTIPLE_LOCAL_PO_COST_REFERENCES','NO_RELEASED_LOCAL_BATCH',
  'LOCAL_BATCHES_NOT_LINKED_TO_SALES'
 ]),
 saleLotAttributed:z.literal(false),
 realizedBatchCogs:z.null()
})
const schema=z.object({
 orderId:z.string().uuid(),marketplaceId:z.string().uuid(),
 orderCostReadiness:z.string(),
 sourceScope:z.literal('LOCAL_PURCHASE_RECEIPTS_NOT_AMAZON'),
 skuEvidence:z.array(sku).max(1000),
 blockerCodes:z.array(z.string()),
 saleLotAttributed:z.literal(false),actualBatchCogsVerified:z.literal(false),
 realizedBatchCogs:z.null()
})
export type OrderLotProvenanceV202=z.infer<typeof schema>

export async function fetchOrderLotProvenanceV202(marketplace:string,orderId:string) {
 const res=await fetch('/api/v1/finance/marketplaces/'+encodeURIComponent(marketplace)+
  '/orders/'+encodeURIComponent(orderId)+'/lot-provenance',{
  method:'GET',credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}
 })
 if(!res.ok)throw Error(res.status===404?'当前订单或店铺不可用':
  res.status===403?'没有权限查看采购成本来源':
  '采购批次来源核对接口 HTTP '+res.status)
 return schema.parse(await res.json())
}
