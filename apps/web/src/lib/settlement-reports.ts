import {z} from 'zod'

const imported=z.object({
  id:z.string().uuid(),marketplaceId:z.string().uuid(),reportId:z.string(),
  documentId:z.string(),documentSha256:z.string().regex(/^[0-9a-f]{64}$/),
  settlementReference:z.string(),statementCurrency:z.string(),
  declaredNet:z.string().nullable(),lineCount:z.number().int().min(1),
  importedAt:z.string()
})
const line=z.object({
  lineNumber:z.number().int().positive(),transactionType:z.string().nullable(),
  amountType:z.string().nullable(),amountDescription:z.string().nullable(),
  orderId:z.string().nullable(),signedAmount:z.string(),currency:z.string()
})
const review=z.object({
  rowCount:z.number().int().nonnegative(),signedLineTotal:z.string(),
  currency:z.string(),distinctOrderReferences:z.number().int().nonnegative(),
  bankVerified:z.literal(false),reconciledToFinances:z.literal(false)
})
export type SettlementReport=z.infer<typeof imported>
export type SettlementReportLine=z.infer<typeof line>

async function get<T>(path:string,schema:z.ZodType<T>):Promise<T>{
  const res=await fetch(path,{
    credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}
  })
  if(!res.ok)throw new Error(res.status===403?'无当前站点 Owner 权限':
    res.status===404?'报告不存在或不属于当前站点':
    'Amazon 结算来源读取失败（HTTP '+res.status+'）')
  return schema.parse(await res.json())
}
const root=(mp:string)=>'/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+'/reports'
const item=(mp:string,id:string)=>root(mp)+'/'+encodeURIComponent(id)
export const listSettlementReports=(mp:string)=>get(root(mp),z.array(imported))
export const listSettlementReportLines=(mp:string,id:string)=>get(item(mp,id)+'/lines',z.array(line).max(100))
export const reviewSettlementReport=(mp:string,id:string)=>get(item(mp,id)+'/review',review)
