import {z} from 'zod'
import {getAdsAuthorizationCapability,listAdsProfiles,registerAdsProfile,
 listAdsReports,importAdsReport,getAdsReport,reviewAdsReport,listReviewedAdsCosts}
 from '../generated/ads-reports'

const id=z.string().uuid()
const amount=z.string().regex(/^[0-9]+(?:\.[0-9]{1,6})?$/)
const origin=z.enum(['MOCK','MANUAL_UNVERIFIED'])
export const adsCapability=z.object({
 status:z.literal('DISABLED_PENDING_LOCAL_OWNER_AUTHORIZATION'),
 sellerOauthReused:z.literal(false),adsTokenStored:z.literal(false),
 adsApiVerified:z.literal(false),profitPosted:z.literal(false)
})
export const adsProfile=z.object({
 id,profileReference:z.string(),origin,authorizationStatus:z.literal('NOT_AUTHORIZED'),
 createdAt:z.string()
})
export const adsBatch=z.object({
 id,profileId:id,reportReference:z.string(),origin,
 reportType:z.literal('SP_CAMPAIGN_DAILY'),currency:z.string().length(3),
 fromDate:z.string(),throughDate:z.string(),rowCount:z.number().int().min(1).max(200),
 state:z.enum(['STAGED','REVIEWED_UNVERIFIED','REJECTED']),
 importedBy:id,reviewedBy:id.nullable(),reviewNote:z.string().nullable(),
 reportSha256:z.string().regex(/^[0-9a-f]{64}$/),
 importedAt:z.string(),reviewedAt:z.string().nullable(),
 adsApiVerified:z.literal(false),profitPosted:z.literal(false)
})
const cost=z.object({rowNumber:z.number().int().positive(),businessDate:z.string(),
 campaignReference:z.string(),spendAmount:amount,currency:z.string().length(3)})
export const adsReportDetail=z.object({batch:adsBatch,rows:z.array(cost).max(200),reportTotal:amount})
export const adsReviewedCost=z.object({
 profileId:id,profileReference:z.string(),businessDate:z.string(),
 campaignReference:z.string(),spendAmount:amount,currency:z.string().length(3),
 evidenceOrigin:origin,reportReference:z.string(),
 adsApiVerified:z.literal(false),profitPosted:z.literal(false)
})
export type AdsProfile=z.infer<typeof adsProfile>
export type AdsBatch=z.infer<typeof adsBatch>
export type AdsDetail=z.infer<typeof adsReportDetail>
export type AdsOrigin=z.infer<typeof origin>
export type AdsCostInput={businessDate:string;campaignReference:string;spendAmount:string}
export type AdsImportInput={profileId:string;reportReference:string;origin:AdsOrigin;
 fromDate:string;throughDate:string;currency:string;rows:AdsCostInput[]}
export const getCapability=async(mp:string)=>
 adsCapability.parse((await getAdsAuthorizationCapability(mp)).data)
export const getProfiles=async(mp:string)=>
 z.array(adsProfile).max(50).parse((await listAdsProfiles(mp)).data)
export const addProfile=async(mp:string,profileReference:string,value:AdsOrigin)=>
 adsProfile.parse((await registerAdsProfile(mp,{profileReference,origin:value})).data)
export const getReports=async(mp:string)=>
 z.array(adsBatch).max(100).parse((await listAdsReports(mp)).data)
export const getReport=async(mp:string,id:string)=>
 adsReportDetail.parse((await getAdsReport(mp,id)).data)
export const addReport=async(mp:string,value:AdsImportInput)=>
 adsReportDetail.parse((await importAdsReport(mp,value)).data)
export const decideReport=async(mp:string,id:string,decision:'APPROVE'|'REJECT',note:string)=>
 adsReportDetail.parse((await reviewAdsReport(mp,id,{decision,note})).data)
export const getReviewed=async(mp:string)=>
 z.array(adsReviewedCost).max(500).parse((await listReviewedAdsCosts(mp)).data)
