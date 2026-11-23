import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchOrderLotProvenanceV202} from './order-lot-provenance-v202'

const mp='11111111-1111-4111-8111-111111111111'
const order='22222222-2222-4222-8222-222222222222'
const fixture={
 orderId:order,marketplaceId:mp,orderCostReadiness:'COGS_READY_PREVIEW',
 sourceScope:'LOCAL_PURCHASE_RECEIPTS_NOT_AMAZON',
 skuEvidence:[{
  internalSku:'SKU-1',priorLocalReceiptCount:1,
  legacyNoQcBatchCount:0,pendingQcBatchCount:0,
  inspectedUnreleasedBatchCount:0,releasedBatchCount:1,
  receiptIdentityConflictCount:0,qcIdentityConflictCount:0,
  releaseLedgerConflictCount:0,observedReleasedLocalUnits:3,
  distinctPurchaseCurrencyCount:1,distinctPurchaseUnitCostCount:1,
  singlePurchaseCurrency:'USD',singlePurchaseUnitCost:'4.250000',
  status:'LOCAL_BATCHES_NOT_LINKED_TO_SALES',
  saleLotAttributed:false,realizedBatchCogs:null
 }],
 blockerCodes:['SALE_TO_LOCAL_RECEIPT_LOT_LINK_NOT_RECORDED',
  'FBA_FBM_FULFILLMENT_AND_EXTERNAL_WMS_UNVERIFIED',
  'G1_ACTUAL_BATCH_COGS_POSTING_NOT_AUTHORIZED'],
 saleLotAttributed:false,actualBatchCogsVerified:false,
 realizedBatchCogs:null
}
describe('V202 local receipts cannot masquerade as Amazon sale-lot COGS',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('shows local purchase reference but no verified batch cost',async()=>{
  const mock=vi.fn().mockResolvedValue({ok:true,json:async()=>fixture})
  vi.stubGlobal('fetch',mock)
  const result=await fetchOrderLotProvenanceV202(mp,order)
  expect(result.skuEvidence[0].singlePurchaseUnitCost).toBe('4.250000')
  expect(result.skuEvidence[0].saleLotAttributed).toBe(false)
  expect(result.realizedBatchCogs).toBeNull()
  expect(mock.mock.calls[0][1]).toMatchObject({
   method:'GET',credentials:'same-origin',cache:'no-store'
  })
 })
 it('rejects backend pretending local QC receipt proves Amazon batch COGS',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,
   json:async()=>({...fixture,saleLotAttributed:true,realizedBatchCogs:'12.75'})}))
  await expect(fetchOrderLotProvenanceV202(mp,order)).rejects.toThrow()
 })
 it('rejects price aggregation across mixed purchase currencies',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,
   json:async()=>({...fixture,skuEvidence:[{
    ...fixture.skuEvidence[0],
    distinctPurchaseCurrencyCount:2,
    singlePurchaseCurrency:null,singlePurchaseUnitCost:null,
    status:'MULTIPLE_LOCAL_PO_COST_REFERENCES'
   }]})}))
  const result=await fetchOrderLotProvenanceV202(mp,order)
  expect(result.skuEvidence[0].singlePurchaseUnitCost).toBeNull()
  expect(result.skuEvidence[0].status).toBe('MULTIPLE_LOCAL_PO_COST_REFERENCES')
 })
})
