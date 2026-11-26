import {z} from 'zod'
import {csrfHeader} from './auth'
import type {SettlementReportLine} from './settlement-reports'

const hash=z.string().regex(/^[0-9a-f]{64}$/)
export const categories=['PRODUCT_SALES','PRODUCT_REFUND','AMAZON_FEE','FBA_FEE','ADJUSTMENT'] as const
export const categoryLabels:Record<typeof categories[number],string>={
 PRODUCT_SALES:'商品销售收入',PRODUCT_REFUND:'商品退款',AMAZON_FEE:'Amazon 平台费用',
 FBA_FEE:'FBA 费用',ADJUSTMENT:'平台调整'
}
const category=z.enum(categories)
const rawField=(max:number)=>z.string().min(1).max(max).regex(/^[A-Za-z0-9 _./+()\-]+$/)
const reportType=z.object({transactionType:rawField(100),amountType:rawField(100).nullable(),
 amountDescription:rawField(160).nullable()})
const mapping=z.object({reportType,category})
export const mappingArray=z.array(mapping).min(1).max(200).refine(
 rows=>new Set(rows.map(row=>JSON.stringify(row.reportType))).size===rows.length,
 '原始类型组合不能重复')
export type Mapping=z.infer<typeof mapping>
export type EditorRow={transactionType:string;amountType:string;amountDescription:string;category:string}
export function rowsFromVisibleLines(lines:readonly SettlementReportLine[]):EditorRow[]{
 const rows=new Map<string,EditorRow>()
 for(const l of lines){
  const row={transactionType:l.transactionType??'',amountType:l.amountType??'',
   amountDescription:l.amountDescription??'',category:''}
  rows.set(JSON.stringify([l.transactionType,l.amountType,l.amountDescription]),row)
 }
 return [...rows.values()]
}
export function editorMappings(rows:readonly EditorRow[]){
 return mappingArray.parse(rows.map(row=>({reportType:{transactionType:row.transactionType,
  amountType:row.amountType===''?null:row.amountType,
  amountDescription:row.amountDescription===''?null:row.amountDescription},category:row.category})))
}
const blocked=z.object({mappingApproved:z.literal(false),bankVerified:z.literal(false),
 ledgerPosted:z.literal(false),realizedNetProfit:z.null()})
export const mappingDraft=blocked.extend({reportId:z.string().uuid(),mappingSha256:hash,
 state:z.literal('LOCAL_IMMUTABLE_MAPPING_DRAFT'),mappings:mappingArray})
const count=z.number().int().nonnegative()
const decimal=z.string().regex(/^-?\d+(\.\d+)?$/)
export const categoryPreview=blocked.extend({
 state:z.enum(['SOURCE_COMPARISON_HOLD','CATEGORY_POPULATION_UNBOUNDED','CATEGORY_POPULATION_INCOMPLETE',
  'CATEGORY_MAPPING_INCOMPLETE','ORDER_CATEGORY_AMOUNT_DIFFERENCE','LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED']),
 sourceState:z.string(),currency:z.string(),unmappedReportRows:count,unmappedFinanceLeaves:count,
 unusedMappings:count,mismatchedBuckets:count,categoryTotals:z.array(z.object({
  category,direction:z.union([z.literal(-1),z.literal(0),z.literal(1)]),
  reports:decimal.nullable(),finances:decimal.nullable()})).max(15)
})
export const savedPreview=z.object({mappingSha256:hash,preview:categoryPreview,evidenceSha256:hash.nullable(),
 mappingApproved:z.literal(false)}).refine(x=>!!x.evidenceSha256===
 (x.preview.state==='LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED'),'证据状态不一致')
export const categoryReview=z.object({mappingSha256:hash,evidenceSha256:hash,
 state:z.enum(['LOCAL_CATEGORY_REVIEW_REQUESTED','LOCAL_CATEGORY_REVIEW_RECORDED_NOT_ACTIVATED']),
 requestedBy:z.string().uuid(),approvedBy:z.string().uuid().nullable(),mappingActivated:z.literal(false),
 bankVerified:z.literal(false),ledgerPosted:z.literal(false),realizedNetProfit:z.null()}).refine(x=>
 x.state==='LOCAL_CATEGORY_REVIEW_REQUESTED'?x.approvedBy===null:
 x.approvedBy!==null&&x.approvedBy!==x.requestedBy,'审核角色不一致')
export type Draft=z.infer<typeof mappingDraft>
export type Preview=z.infer<typeof savedPreview>
export type Review=z.infer<typeof categoryReview>
export class CategoryHttpError extends Error{
 constructor(public readonly status:number){
  super(status===409?'当前没有有效审核记录，或证据/权限已变化；请重新核算，不要复用旧审批。':
   status===403?'当前操作需要有效 Owner 权限及独立审批人。':
   status===404?'报告或版本不存在，或不属于当前站点。':
   status===400?'输入的分类、摘要或审核理由不符合要求。':
   '分类操作失败 HTTP '+status)
 }
}
const root=(mp:string,report:string)=>'/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
 '/reports/'+encodeURIComponent(report)+'/category-mapping-drafts'
const draftUrl=(mp:string,report:string,sha:string)=>root(mp,report)+'/'+hash.parse(sha)
const reviewUrl=(mp:string,report:string,sha:string,evidence:string)=>
 draftUrl(mp,report,sha)+'/reviews/'+hash.parse(evidence)
async function call<T>(url:string,schema:z.ZodType<T>,body?:unknown):Promise<T>{
 const write=body!==undefined
 const response=await fetch(url,{method:write?'POST':'GET',credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json',...(write?{...await csrfHeader(),'Content-Type':'application/json'}:{})},
  ...(write?{body:JSON.stringify(body)}:{})})
 if(!response.ok)throw new CategoryHttpError(response.status)
 return schema.parse(await response.json())
}
function requireIdentity(ok:boolean){if(!ok)throw new Error('返回的报告或证据身份不一致，请刷新。')}
export async function saveMapping(mp:string,report:string,rows:Mapping[]){
 const result=await call(root(mp,report),mappingDraft,mappingArray.parse(rows))
 requireIdentity(result.reportId===report)
 return result
}
export async function loadMapping(mp:string,report:string,sha:string){
 const result=await call(draftUrl(mp,report,sha),mappingDraft)
 requireIdentity(result.reportId===report&&result.mappingSha256===sha)
 return result
}
export async function previewMapping(mp:string,report:string,sha:string){
 const result=await call(draftUrl(mp,report,sha)+'/preview',savedPreview)
 requireIdentity(result.mappingSha256===sha)
 return result
}
export async function readCategoryReview(mp:string,report:string,sha:string,evidence:string){
 const result=await call(reviewUrl(mp,report,sha,evidence),categoryReview)
 requireIdentity(result.mappingSha256===sha&&result.evidenceSha256===evidence)
 return result
}
export async function writeCategoryReview(mp:string,report:string,sha:string,evidence:string,
 action:'request'|'approve',reason:string){
 const note=z.string().trim().min(10).max(500).regex(/^[^\x00-\x1f\x7f]+$/).parse(reason)
 const result=await call(reviewUrl(mp,report,sha,evidence)+'/'+action,categoryReview,{reason:note})
 requireIdentity(result.mappingSha256===sha&&result.evidenceSha256===evidence)
 return result
}
