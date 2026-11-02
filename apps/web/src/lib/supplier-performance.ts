import {z} from 'zod'
import {csrfHeader} from './auth'

const root='/api/v1/purchasing/supplier-performance'
const percent=z.string().regex(/^\d{1,3}\.\d$/).nullable()
const scorecard=z.object({
 supplierId:z.string().uuid(),supplierName:z.string(),
 completeAsnSampleCount:z.number().int().nonnegative(),
 observedOnTimeAsnCount:z.number().int().nonnegative(),
 inspectedReceiptSampleCount:z.number().int().nonnegative(),
 acceptedQcUnits:z.number().int().nonnegative(),inspectedQcUnits:z.number().int().nonnegative(),
 activeVarianceCount:z.number().int().nonnegative(),
 observedOnTimePercent:percent,inspectedAcceptancePercent:percent,
 evidenceScore:percent,scoringStatus:z.enum(['INSUFFICIENT_EVIDENCE','PROVISIONAL'])
})
const followup=z.object({
 id:z.string().uuid(),orderId:z.string().uuid(),orderNumber:z.string(),
 lineId:z.string().uuid(),internalSku:z.string(),supplierName:z.string(),
 ruleCode:z.enum(['ASN_COVERAGE_GAP','ASN_NOT_DISPATCHED']),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE','CLEARED']),approvedAt:z.string(),
 orderedQuantity:z.number().int().nonnegative(),
 receivedQuantity:z.number().int().nonnegative(),
 activeAsnQuantity:z.number().int().nonnegative(),
 uncoveredQuantity:z.number().int().nonnegative(),lastSeenAt:z.string()
})
const scan=z.object({id:z.string().uuid(),triggerType:z.literal('MANUAL'),
 matchedCount:z.number().int().nonnegative(),clearedCount:z.number().int().nonnegative()})
const change=z.object({id:z.string().uuid(),
 state:z.enum(['OPEN','ACKNOWLEDGED','DONE'])})
export type SupplierScorecard=z.infer<typeof scorecard>
export type PurchaseFollowup=z.infer<typeof followup>
export type FollowupState='OPEN'|'ACKNOWLEDGED'|'DONE'

async function request<T>(path:string,schema:z.ZodType<T>,method='GET',body?:unknown):Promise<T>{
 const response=await fetch(root+path,{method,credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(method==='GET'?{}:await csrfHeader()),
   ...(body===undefined?{}:{'Content-Type':'application/json'})},
  ...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(!response.ok){
  if(response.status===401)throw Error('会话已过期，请重新登录')
  if(response.status===403)throw Error('仅 Owner 可管理采购跟进')
  if(response.status===409)throw Error('待办已变化或已解决，请刷新后重试')
  if(response.status===404)throw Error('待办不存在或不在当前租户')
  throw Error('供应商履约管理失败：HTTP '+response.status)
 }
 return schema.parse(await response.json())
}
export const fetchSupplierScorecards=()=>request('/scorecards',z.array(scorecard).max(200))
export const fetchPurchaseFollowups=(filter:'ACTIVE'|'ALL'='ACTIVE')=>
 request('/followups?filter='+filter,z.array(followup).max(100))
export const scanPurchaseFollowups=()=>request('/scan',scan,'POST')
export const setPurchaseFollowupState=(id:string,state:FollowupState)=>
 request('/followups/'+encodeURIComponent(id)+'/state',change,'POST',{state})
