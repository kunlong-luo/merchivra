import {z} from 'zod'

export const settlementComparison=z.object({
  reportId:z.string(),settlementId:z.string(),currency:z.string(),
  reportedNet:z.string().nullable(),reportLinesNet:z.string().nullable(),
  financesNet:z.string().nullable(),
  financeTransactionCount:z.number().int().nonnegative(),
  otherCurrencyTransactionCount:z.number().int().nonnegative(),
  state:z.enum(['REPORT_DETAIL_INCOMPLETE','REPORT_INTERNAL_DIFFERENCE',
    'CURRENCY_CONFLICT','NO_FINANCES_SETTLEMENT_REFERENCE',
    'SOURCE_TOTAL_DIFFERENCE','SOURCE_AMOUNTS_AGREE_UNVERIFIED']),
  reconciled:z.literal(false),bankVerified:z.literal(false)
})
export async function compareSettlementReport(mp:string,id:string){
  const path='/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
    '/reports/'+encodeURIComponent(id)+'/finance-comparison'
  const res=await fetch(path,{credentials:'same-origin',cache:'no-store',
    headers:{Accept:'application/json'}})
  if(!res.ok)throw new Error(res.status===404?'结算来源不存在或无权访问':
    '财务来源核对失败 HTTP '+res.status)
  return settlementComparison.parse(await res.json())
}
