import {afterEach,describe,expect,it,vi} from 'vitest'
import {purchaseLinkSchema,getPurchaseLinks,getPurchaseCandidates,
 proposePurchaseLink,confirmPurchaseLink,revokePurchaseLink} from './fba-purchase-links'

afterEach(()=>vi.unstubAllGlobals())
describe('V75 explicit local FBA-to-PO/ASN identity proof',()=>{
 const mp='11111111-1111-4111-8111-111111111111'
 const shipment='22222222-2222-4222-8222-222222222222'
 const line='33333333-3333-4333-8333-333333333333'
 const po='44444444-4444-4444-8444-444444444444'
 const link={
  id:'55555555-5555-4555-8555-555555555555',shipmentId:shipment,
  inboundLineId:line,sellerSku:'V75-SKU',shipmentState:'DRAFT',shipmentOrigin:'MOCK',
  inboundPlannedQuantity:12,inboundReportedReceived:0,
  purchaseOrderId:po,purchaseOrderLineId:po,orderNumber:'PO-V75',
  purchaseState:'APPROVED',poOrderedQuantity:20,localPoReceived:4,
  asnId:null,asnStatus:null,asnQuantity:null,asnLocalReceived:null,
  state:'PROPOSED',proofReference:'V75 document reference',
  proposedBy:po,confirmedBy:null,createdAt:'2026-11-02T00:00:00Z',confirmedAt:null
 }
 it('keeps identity proof explicitly unconfirmed, and null ASN really unknown',()=>{
  const value=purchaseLinkSchema.parse(link)
  expect(value.state).toBe('PROPOSED')
  expect(value.asnLocalReceived).toBeNull()
  expect(purchaseLinkSchema.safeParse({...link,state:'AMAZON_VERIFIED'}).success).toBe(false)
  expect(purchaseLinkSchema.safeParse({...link,localPoReceived:-1}).success).toBe(false)
 })
 it('reads only current marketplace & shipment on same-origin shared transport',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,opts?:RequestInit)=>{
   urls.push(url);expect(opts).toMatchObject({method:'GET',
    credentials:'same-origin',cache:'no-store'})
   return new Response(JSON.stringify([link]),{status:200,headers:{'Content-Type':'application/json'}})
  }))
  expect((await getPurchaseLinks(mp,shipment))[0].state).toBe('PROPOSED')
  expect(urls[0]).toBe('/api/v1/fba/marketplaces/'+mp+'/shipments/'+shipment+'/purchase-links')
  expect(urls[0]).not.toContain('tenantId')
 })
 it('has explicit candidates endpoint; caller never guesses marketplace by SKU',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify([{
    purchaseOrderId:po,purchaseOrderLineId:po,orderNumber:'PO-1',
    supplierName:'Synthetic supplier',orderedQuantity:20,localReceivedQuantity:4,
    asnId:null,asnStatus:null,asnQuantity:null,asnReceivedQuantity:0
  }]),{status:200,headers:{'Content-Type':'application/json'}})))
  expect((await getPurchaseCandidates(mp,shipment,line))[0].asnId).toBeNull()
 })
 it('uses shared CSRF transport for proposal, and sends no tenant data',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.includes('/auth/csrf'))return new Response(JSON.stringify({
    headerName:'X-CSRF-TOKEN',token:'v75-disposable-csrf-1234567890'
   }),{status:200,headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify(link),{status:200,
    headers:{'Content-Type':'application/json'}})
  }))
  await proposePurchaseLink(mp,shipment,{
   requestKey:mp,inboundLineId:line,purchaseOrderLineId:po,
   asnId:null,proofReference:'V75 document reference'})
  const posted=requests.find(x=>x.url.endsWith('/purchase-links'))
  expect(posted?.options?.method).toBe('POST')
  expect(new Headers(posted?.options?.headers).get('X-CSRF-TOKEN'))
    .toBe('v75-disposable-csrf-1234567890')
  expect(String(posted?.options?.body)).not.toContain('tenantId')
 })
 it('uses explicit independent Owner confirmation route with reason',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.includes('/auth/csrf'))return new Response(JSON.stringify({
    headerName:'X-CSRF-TOKEN',token:'v75-disposable-csrf-1234567890'
   }),{status:200,headers:{'Content-Type':'application/json'}})
   return new Response(JSON.stringify({...link,state:'CONFIRMED',confirmedBy:mp}),
    {status:200,headers:{'Content-Type':'application/json'}})
  }))
  await confirmPurchaseLink(mp,shipment,link.id,'V75 second Owner examined identities')
  const posted=requests.find(x=>x.url.endsWith('/confirm'))
  expect(posted?.url).toContain(link.id)
  expect(JSON.parse(String(posted?.options?.body))).toEqual({
   reason:'V75 second Owner examined identities'})
 })
 it('revokes using the generated route, same-origin Session and CSRF',async()=>{
  const requests:Array<{url:string;options?:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{
   requests.push({url,options})
   if(url.includes('/auth/csrf'))return Response.json({
    headerName:'X-CSRF-TOKEN',token:'v145-fictional-csrf'})
   return Response.json({...link,state:'REVOKED'})
  }))
  expect((await revokePurchaseLink(mp,shipment,link.id,'Source identity withdrawn')).state)
   .toBe('REVOKED')
  const posted=requests.find(x=>x.url.endsWith('/revoke'))
  expect(posted?.url).toBe('/api/v1/fba/marketplaces/'+mp+'/shipments/'+shipment+
   '/purchase-links/'+link.id+'/revoke')
  expect(posted?.options).toMatchObject({method:'POST',credentials:'same-origin',cache:'no-store'})
  expect(new Headers(posted?.options?.headers).get('X-CSRF-TOKEN')).toBe('v145-fictional-csrf')
  expect(JSON.parse(String(posted?.options?.body))).toEqual({reason:'Source identity withdrawn'})
 })
 it('rejects every malformed dynamic path before requesting CSRF or business data',async()=>{
  const fetch=vi.fn()
  vi.stubGlobal('fetch',fetch)
  const body={requestKey:mp,inboundLineId:line,purchaseOrderLineId:po,
   asnId:null,proofReference:'V145 local identity reference'}
  for(const invalid of ['../other','not-a-uuid',mp+'?tenantId='+po]){
   await expect(getPurchaseLinks(invalid,shipment)).rejects.toThrow()
   await expect(getPurchaseLinks(mp,invalid)).rejects.toThrow()
   await expect(getPurchaseCandidates(mp,shipment,invalid)).rejects.toThrow()
   await expect(proposePurchaseLink(mp,invalid,body)).rejects.toThrow()
   await expect(confirmPurchaseLink(mp,shipment,invalid,'Independent local review')).rejects.toThrow()
   await expect(revokePurchaseLink(invalid,shipment,link.id,'Local evidence withdrawn')).rejects.toThrow()
  }
  expect(fetch).not.toHaveBeenCalled()
 })
 it.each([401,403,409])('preserves HTTP %i failure and sends no retry or second write',async(status)=>{
  const fetch=vi.fn(async(url:string)=>url.includes('/auth/csrf')?
   Response.json({headerName:'X-CSRF-TOKEN',token:'v145-fictional-csrf'}):
   Response.json({state:'CONFIRMED'},{status}))
  vi.stubGlobal('fetch',fetch)
  await expect(confirmPurchaseLink(mp,shipment,link.id,'Independent local review'))
   .rejects.toThrow(status===401?'未登录':status===403?'没有操作权限':'业务状态变化')
  expect(fetch.mock.calls.filter(([url])=>url.endsWith('/confirm'))).toHaveLength(1)
 })
 it('rejects truncated-contract overflow and invalid provenance after generated reads',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>Response.json(Array.from({length:201},()=>link))))
  await expect(getPurchaseLinks(mp,shipment)).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>Response.json([{...link,shipmentOrigin:'LIVE'}])))
  await expect(getPurchaseLinks(mp,shipment)).rejects.toThrow()
  vi.stubGlobal('fetch',vi.fn(async()=>Response.json([{
   purchaseOrderId:po,purchaseOrderLineId:po,orderNumber:'PO-V145',
   supplierName:'Fictional supplier',orderedQuantity:20,localReceivedQuantity:-1,
   asnId:null,asnStatus:null,asnQuantity:null,asnReceivedQuantity:0
  }])))
  await expect(getPurchaseCandidates(mp,shipment,line)).rejects.toThrow()
 })

})
