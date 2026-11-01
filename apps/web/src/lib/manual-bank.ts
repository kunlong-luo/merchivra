import {z} from 'zod'
import {csrfHeader} from './auth'

export const manualBankBatch=z.object({
 id:z.string().uuid(),marketplaceId:z.string().uuid(),
 statementRef:z.string(),fileSha256:z.string().regex(/^[0-9a-f]{64}$/),
 lineCount:z.number().int().min(1).max(50),
 sourceOrigin:z.literal('MANUAL_UNVERIFIED'),
 importedBy:z.string().uuid(),importedAt:z.string()
})
export const manualBankEntry=z.object({
 id:z.string().uuid(),entryRef:z.string(),settlementRef:z.string(),
 valueDate:z.string(),bankCurrency:z.string(),creditAmount:z.string(),
 withheldFee:z.string(),declaredFxRate:z.string().nullable(),
 sourceOrigin:z.literal('MANUAL_UNVERIFIED')
})
export const manualBankComparison=z.object({
 settlementRef:z.string(),settlementCurrency:z.string(),
 declaredNet:z.string().nullable(),bankCurrency:z.string().nullable(),
 observedCredit:z.string().nullable(),withheldFee:z.string().nullable(),
 declaredFxRate:z.string().nullable(),expectedGross:z.string().nullable(),
 sourceDifference:z.string().nullable(),
 state:z.enum(['DUPLICATE_REPORT_SETTLEMENT_ID','NO_MANUAL_BANK_ENTRY',
   'AMBIGUOUS_BANK_ENTRY','SOURCE_REVIEW_REQUIRED','REPORT_NET_MISSING',
   'BANK_AMOUNT_INCOMPLETE','SAME_CURRENCY_RATE_CONFLICT',
   'SAME_CURRENCY_AMOUNT_DIFFERENCE','MANUAL_AMOUNT_MATCH_UNVERIFIED',
   'FX_RATE_REQUIRED','MANUAL_FX_AMOUNT_DIFFERENCE','MANUAL_FX_MATCH_UNVERIFIED']),
 candidateCount:z.number().int().nonnegative(),
 sourceReviewApproved:z.boolean(),bankVerified:z.literal(false),
 ledgerPosted:z.literal(false),candidates:z.array(z.object({
  entryRef:z.string(),valueDate:z.string(),currency:z.string(),
  credit:z.string(),withheldFee:z.string(),declaredFxRate:z.string().nullable()
 })).max(3)
})
export type ManualBankComparison=z.infer<typeof manualBankComparison>
const root=(mp:string)=>'/api/v1/finance/marketplaces/'+encodeURIComponent(mp)
async function get<T>(url:string,schema:z.ZodType<T>) {
 const res=await fetch(url,{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!res.ok)throw new Error(res.status===403?'无当前站点 Owner 权限':
  res.status===404?'找不到当前站点银行或结算来源':
  '银行来源读取失败（HTTP '+res.status+'）')
 return schema.parse(await res.json())
}
export const listManualBankBatches=(mp:string)=>
 get(root(mp)+'/bank-payout-imports',z.array(manualBankBatch).max(50))
export const listManualBankEntries=(mp:string,batch:string)=>
 get(root(mp)+'/bank-payout-imports/'+encodeURIComponent(batch)+'/entries',
  z.array(manualBankEntry).max(50))
export const compareManualBank=(mp:string,report:string)=>
 get(root(mp)+'/reports/'+encodeURIComponent(report)+'/bank-comparison',manualBankComparison)

export async function importManualBankCsv(mp:string,statementRef:string,file:File) {
 if(!/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(statementRef))
  throw new Error('来源批次编号格式不符合要求（8–160 位）')
 if(file.size===0||file.size>65536)
  throw new Error('银行参考 CSV 文件须在 1–64 KiB 以内')
 const response=await fetch(root(mp)+'/bank-payout-imports/'+
  encodeURIComponent(statementRef),{
  method:'POST',credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),'Content-Type':'text/plain; charset=utf-8',
   Accept:'application/json'},
  body:file
 })
 if(!response.ok)throw new Error(
  response.status===409?'批次／流水条目已存在且内容不同，不允许覆盖':
  response.status===400?'CSV 列、数值、来源 ID 或日期格式无效':
  response.status===403?'无当前站点 Owner 权限':
  '导入银行人工参考数据失败（HTTP '+response.status+'）')
 return manualBankBatch.parse(await response.json())
}
