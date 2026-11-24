import {z} from 'zod'
import {stageManualCostSourceBatch} from '../generated/manual-cost-v228'

/** Owner-only local V91 source staging. This is NOT a verified invoice,
 * accounting approval or actual landed/return COGS. No browser-sent tenant.
 * Orval supplies the request + shared secure Session/CSRF mutator.
 */
export const manualCostCategories=[
 'ADS_SPEND','INBOUND_FREIGHT','FBA_STORAGE','FBM_SHIPPING',
 'RETURN_HANDLING','IMPORT_DUTY','PAYMENT_AND_FX','OTHER_OPERATING'
] as const
export const manualCostKinds=[
 'MANUAL_INVOICE','MANUAL_CARRIER','MANUAL_OTHER'
] as const
const id=z.string().uuid()
const key=z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}$/)
const positiveMoney=z.string().regex(/^[0-9]{1,14}(?:\.[0-9]{1,6})?$/)
 .refine(s=>!/^0+(?:\.0+)?$/.test(s),'金额必须大于零')
const day=z.string().regex(/^\d{4}-\d{2}-\d{2}$/)
 .refine(v=>{
  const d=new Date(v+'T00:00:00.000Z')
  return !Number.isNaN(d.getTime())&&d.toISOString().slice(0,10)===v
 },'业务日期无效')
export const manualDraft=z.strictObject({
 sourceKind:z.enum(manualCostKinds),
 sourceKey:key,
 category:z.enum(manualCostCategories),
 documentSha256:z.string().regex(/^[0-9a-f]{64}$/)
  .refine(v=>v!=='0'.repeat(64),'不能使用空白摘要'),
 businessDate:day,
 amount:positiveMoney,
 currency:z.string().regex(/^[A-Z]{3}$/)
})
export type ManualCostDraft=z.infer<typeof manualDraft>
export const manualSource=z.object({
 id,marketplaceId:id,category:z.enum(manualCostCategories),
 sourceKind:z.enum(manualCostKinds),sourceKey:key,
 documentSha256:z.string().regex(/^[0-9a-f]{64}$/),
 businessDate:day,amount:z.string(),currency:z.string().regex(/^[A-Z]{3}$/),
 origin:z.literal('MANUAL_UNVERIFIED'),
 state:z.enum(['STAGED','REVIEWED_UNVERIFIED','REJECTED']),
 createdBy:id,reviewedBy:id.nullable(),reviewedAt:z.string().nullable()
})
export type ManualCostSource=z.infer<typeof manualSource>
export type BatchPreview={rows:ManualCostDraft[];errors:string[]}
const headings='类别\t来源类型\t来源编号\tSHA-256\t业务日期\t金额'

/** Spreadsheet TSV: six columns, no FX or file upload. Labels not sent.
 * Client validation is a convenience; server remains the authority.
 */
export function previewManualCostBatch(input:string,currency:string):BatchPreview{
 const entries=input.split(/\r?\n/).filter(s=>s.trim().length>0)
 if(entries.length===0)return {rows:[],errors:['粘贴 1–20 行费用数据；当前没有任何候选行']}
 if(entries.length>20)return {rows:[],errors:['每批最多 20 行，不能自动拆单或部分提交']}
 const rows:ManualCostDraft[]=[],errors:string[]=[]
 const seen=new Set<string>()
 if(!/^[A-Z]{3}$/.test(currency))
  return {rows:[],errors:['当前 Marketplace 原币不可用；禁止猜测币种']}
 entries.forEach((line,i)=>{
  const columns=line.split('\t').map(v=>v.trim())
  if(columns.length!==6){
   errors.push('第 '+(i+1)+' 行：需 6 列（类别、来源类型、来源编号、SHA-256、业务日期、金额）')
   return
  }
  const [category,sourceKind,sourceKey,documentSha256,businessDate,amount]=columns
  const parsed=manualDraft.safeParse({
   category,sourceKind,sourceKey,documentSha256,businessDate,amount,currency
  })
  if(!parsed.success){
   errors.push('第 '+(i+1)+' 行：'+
    parsed.error.issues.map(issue=>issue.path.join('.')+' '+issue.message)
     .slice(0,3).join('；'))
   return
  }
  const unique=sourceKind+'|'+sourceKey
  if(seen.has(unique)){
   errors.push('第 '+(i+1)+' 行：同一来源类型与编号在本批重复')
   return
  }
  seen.add(unique)
  rows.push(parsed.data)
 })
 // All-or-nothing: no partial preview can be submitted as a valid batch.
 return {rows:errors.length===0?rows:[],errors}
}
export const manualCostTsvHeading=headings

function units(value:string):bigint{
 const [whole,fraction='']=value.split('.')
 return BigInt(whole)*1_000_000n+BigInt(fraction.padEnd(6,'0')||'0')
}

/** Orval generated operation, with additional fail-closed response validation.
 * Exact replays can already be REVIEWED_UNVERIFIED / REJECTED, never promote
 * them to an external verification. 409 has NO partial-success row mapping.
 */
export async function stageManualCostBatchV228(
 marketplaceId:string,currency:string,drafts:ManualCostDraft[]
):Promise<ManualCostSource[]>{
 id.parse(marketplaceId)
 if(drafts.length<1||drafts.length>20)
  throw Error('每次只能完整提交 1–20 条手工来源')
 const valid=drafts.map(x=>manualDraft.parse(x))
 if(valid.some(x=>x.currency!==currency))
  throw Error('禁止不同原币的费用来源混入当前 Marketplace')
 const identities=valid.map(x=>x.sourceKind+'|'+x.sourceKey)
 if(new Set(identities).size!==identities.length)
  throw Error('批次内来源编号重复，禁止重复暂存')
 const response=await stageManualCostSourceBatch(marketplaceId,valid)
 if(response.status!==200||!response.headers.get('Cache-Control')?.includes('no-store'))
  throw Error('服务端未返回可验证的安全缓存/批量成功响应')
 const sources=z.array(manualSource).length(valid.length).parse(response.data)
 if(new Set(sources.map(s=>s.id)).size!==sources.length)
  throw Error('服务端费用来源身份不唯一')
 for(let i=0;i<valid.length;i++){
  const a=valid[i],b=sources[i]
  if(b.marketplaceId!==marketplaceId||b.currency!==currency
   ||b.category!==a.category||b.sourceKind!==a.sourceKind
   ||b.sourceKey!==a.sourceKey||b.documentSha256!==a.documentSha256
   ||b.businessDate!==a.businessDate||units(b.amount)!==units(a.amount))
   throw Error('服务端费用记录与输入的原始证据不一致，禁止展示成功')
 }
 return sources
}
