import {afterEach,describe,expect,it,vi} from 'vitest'
import {getRefundSnapshot} from './refund-review'

const mp='11111111-1111-4111-8111-111111111111'
const event='22222222-2222-4222-8222-222222222222'
const snapshot={
 eventId:event,orderId:'33333333-3333-4333-8333-333333333333',
 externalOrderId:'ORDER-201',refundSourceAmount:'-25.00',currency:'USD',
 status:'REFUND_QUANTITY_ACROSS_EVENTS_CONFLICT',
 costReadiness:'COGS_READY_PREVIEW',inventoryReceiptVerified:false,
 items:[{
  itemId:'ITEM-1',sellerSku:'SKU-A',orderedQuantity:2,
  internalSku:'SKU-A',unitCost:'5.00',unitCurrency:'USD',
  reviewedQuantity:2,disposition:'RESTOCKED_CLAIMED',
  evidenceReference:'RETURN-201',revision:1,
  restockedCostSensitivity:null
 }]
}
describe('V201 refund cross-event quantity conflict is a visible blocker',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('keeps distinct cross-event status and no restock cost sensitivity',async()=>{
  const fetcher=vi.fn().mockResolvedValue({ok:true,json:async()=>snapshot})
  vi.stubGlobal('fetch',fetcher)
  const result=await getRefundSnapshot(mp,event)
  expect(result.status).toBe('REFUND_QUANTITY_ACROSS_EVENTS_CONFLICT')
  expect(result.items[0].restockedCostSensitivity).toBeNull()
  const [path,options]=fetcher.mock.calls[0]
  expect(path).toContain('/marketplaces/'+mp+'/events/'+event+'/refund-items')
  expect(options.cache).toBe('no-store')
  expect(options.credentials).toBe('same-origin')
 })
 it('cannot fabricate a verified physical return receipt',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,
   json:async()=>({...snapshot,inventoryReceiptVerified:true})}))
  await expect(getRefundSnapshot(mp,event)).rejects.toThrow()
 })
})
