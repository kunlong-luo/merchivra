import {afterEach,describe,expect,it,vi} from 'vitest'
import {fetchItemAllocation,fetchItemAllocationAudit,itemAllocation,
 reviewItemAllocation,saveItemAllocation} from './item-allocation'

const mp='22222222-2222-4222-8222-222222222222'
const event='11111111-1111-4111-8111-111111111111'
const draft={
 eventId:event,orderId:'33333333-3333-4333-8333-333333333333',
 state:'PENDING',version:4,currency:'USD',
 requestedBy:'44444444-4444-4444-8444-444444444444',
 approvedBy:null,evidenceCurrent:true,
 financialSourceComplete:false,bankVerified:false,
 amounts:{fullyAllocated:false,residuals:[{
  category:'AMAZON_FEE',currency:'USD',sourceTotal:'-10',
  allocated:'-3',remainder:'-7',exact:false}]},
 items:[{externalItemId:'order-item-1',sku:'SKU-1',
  cost:'30.00',currency:'USD',quantity:1}],
 lines:[{externalItemId:'order-item-1',category:'AMAZON_FEE',
  signedAmount:'-3',currency:'USD',evidenceReference:'REVIEW-2026-001'}]
}
describe('V62 item allocation client',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('enforces explicit source incompleteness and bank proof restrictions',()=>{
  expect(itemAllocation.parse(draft).bankVerified).toBe(false)
  expect(()=>itemAllocation.parse({...draft,bankVerified:true})).toThrow()
  expect(()=>itemAllocation.parse({...draft,financialSourceComplete:true})).toThrow()
  expect(()=>itemAllocation.parse({...draft,amounts:{fullyAllocated:true,
   residuals:[{category:'AMAZON_FEE',currency:'USD',sourceTotal:'-10',
    allocated:'-10',remainder:'0',exact:true}],sourceOrigin:'MANUAL_UNVERIFIED'}})).not.toThrow()
 })
 it('reads only server Session marketplace and event scope',async()=>{
  const mock=vi.fn().mockResolvedValue({ok:true,json:async()=>draft})
  vi.stubGlobal('fetch',mock)
  const r=await fetchItemAllocation(mp,event)
  expect(r.amounts.residuals[0].remainder).toBe('-7')
  const [url,options]=mock.mock.calls[0]
  expect(url).toContain('/finance/marketplaces/'+mp+'/item-allocations/'+event)
  expect(url).not.toContain('tenantId')
  expect(options.credentials).toBe('same-origin')
  expect(options.cache).toBe('no-store')
 })
 it('writes only exact line, reference, reason and CSRF header',async()=>{
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:draft
  }))
  vi.stubGlobal('fetch',mock)
  await saveItemAllocation(mp,event,{itemId:'order-item-1',
   category:'AMAZON_FEE',signedAmount:'-3',currency:'USD',
   evidenceReference:'REVIEW-2026-001',reason:'Different Owner reviewed line'})
  const [url,options]=mock.mock.calls[1]
  expect(url).toContain('/item-allocations/'+event+'/line')
  expect(options.method).toBe('PUT')
  expect(options.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(JSON.parse(options.body)).toEqual({
   itemId:'order-item-1',category:'AMAZON_FEE',signedAmount:'-3',
   currency:'USD',evidenceReference:'REVIEW-2026-001',
   reason:'Different Owner reviewed line'
  })
  expect(options.body).not.toContain('tenantId')
  expect(options.body).not.toContain('bankVerified')
 })
 it('submits double review without claiming bank paid',async()=>{
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:draft
  }))
  vi.stubGlobal('fetch',mock)
  await reviewItemAllocation(mp,event,'approve','Source lines conserved after review')
  const [url,req]=mock.mock.calls[1]
  expect(url.endsWith('/approve')).toBe(true)
  expect(req.method).toBe('POST')
  expect(JSON.parse(req.body)).toEqual({reason:'Source lines conserved after review'})
  expect(req.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
 })
})
