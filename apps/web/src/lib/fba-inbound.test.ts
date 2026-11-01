import {describe,it,expect} from 'vitest'
import {inboundDetail} from './fba-inbound'

describe('local FBA inbound evidence boundary',()=>{
 const valid={
  shipment:{id:'11111111-1111-4111-8111-111111111111',reference:'LOCAL-1',
   origin:'MOCK',state:'RECONCILED',createdAt:'2026-11-01T09:00:00Z',
   dispatchedAt:'2026-11-01T09:01:00Z',reviewStartedAt:'2026-11-01T09:02:00Z',
   reconciledAt:'2026-11-01T09:03:00Z'},
  lines:[{id:'22222222-2222-4222-8222-222222222222',sellerSku:'SKU-1',
   plannedQuantity:10,reportedReceived:8,estimatedInTransit:null,delta:-2,
   decision:'ACCEPT_VARIANCE',reviewedBy:'33333333-3333-4333-8333-333333333333',
   reviewNote:'Accepted after independent physical count',reviewedAt:'2026-11-01T09:03:00Z'}],
  receipts:[],amazonVerified:false,fbaStockCredited:false
 }
 it('accepts an explicitly unverified reconciled shortage without inventing transit stock',()=>{
  const parsed=inboundDetail.parse(valid)
  expect(parsed.lines[0]?.estimatedInTransit).toBeNull()
  expect(parsed.lines[0]?.delta).toBe(-2)
 })
 it('rejects a response that claims Amazon verification or stock posting',()=>{
  expect(()=>inboundDetail.parse({...valid,amazonVerified:true})).toThrow()
  expect(()=>inboundDetail.parse({...valid,fbaStockCredited:true})).toThrow()
 })
 it('rejects unknown provenance and negative reported receipts',()=>{
  expect(()=>inboundDetail.parse({...valid,shipment:{...valid.shipment,origin:'LIVE'}})).toThrow()
  expect(()=>inboundDetail.parse({...valid,lines:[{...valid.lines[0],reportedReceived:-1}]})).toThrow()
 })
})
