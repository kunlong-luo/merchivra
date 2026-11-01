import {describe,it,expect} from 'vitest'
import {adsCapability,adsReportDetail,adsReviewedCost} from './ads-reports'
describe('V66 Ads source boundaries',()=>{
 const report={
  batch:{id:'11111111-1111-4111-8111-111111111111',
   profileId:'22222222-2222-4222-8222-222222222222',
   reportReference:'MOCK-REPORT-001',origin:'MOCK',
   reportType:'SP_CAMPAIGN_DAILY',currency:'USD',
   fromDate:'2026-10-01',throughDate:'2026-10-01',rowCount:1,
   state:'REVIEWED_UNVERIFIED',importedBy:'33333333-3333-4333-8333-333333333333',
   reviewedBy:'44444444-4444-4444-8444-444444444444',
   reviewNote:'Independent source check',reportSha256:'a'.repeat(64),
   importedAt:'2026-10-01T10:00:00Z',reviewedAt:'2026-10-02T10:00:00Z',
   adsApiVerified:false,profitPosted:false},
  rows:[{rowNumber:1,businessDate:'2026-10-01',
   campaignReference:'CAMPAIGN-A',spendAmount:'20.000000',currency:'USD'}],
  reportTotal:'20.000000'
 }
 it('accepts only non-verified offline Ads reports',()=>{
  expect(adsReportDetail.parse(report).reportTotal).toBe('20.000000')
  expect(()=>adsReportDetail.parse({...report,batch:{...report.batch,adsApiVerified:true}})).toThrow()
  expect(()=>adsReportDetail.parse({...report,batch:{...report.batch,profitPosted:true}})).toThrow()
  expect(()=>adsReportDetail.parse({...report,batch:{...report.batch,origin:'LIVE'}})).toThrow()
 })
 it('denies implicit ads auth and unverified cost promotion',()=>{
  expect(adsCapability.safeParse({status:'DISABLED_PENDING_LOCAL_OWNER_AUTHORIZATION',
   sellerOauthReused:false,adsTokenStored:false,adsApiVerified:false,profitPosted:false}).success).toBe(true)
  expect(adsCapability.safeParse({status:'CONNECTED',sellerOauthReused:true}).success).toBe(false)
  expect(adsReviewedCost.safeParse({
   profileId:report.batch.profileId,profileReference:'MOCK-PROFILE',
   businessDate:'2026-10-01',campaignReference:'CAMPAIGN-A',
   spendAmount:'20',currency:'USD',evidenceOrigin:'MANUAL_UNVERIFIED',
   reportReference:'MOCK-REPORT-001',adsApiVerified:false,profitPosted:false
  }).success).toBe(true)
 })
})
