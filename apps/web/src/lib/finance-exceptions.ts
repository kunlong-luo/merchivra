import {z} from 'zod'

const finding=z.object({
 code:z.string(),severity:z.enum(['BLOCKING','REVIEW','INFO']),
 subjectType:z.enum(['SOURCE_TRANSACTION','SETTLEMENT_REFERENCE','ORDER_COST']),
 subjectId:z.string().uuid(),sourceReference:z.string(),
 currency:z.string(),signedAmount:z.string().nullable(),nextAction:z.string()
})
const queue=z.object({
 scannedSourceTransactions:z.number().int().nonnegative(),
 scannedSettlements:z.number().int().nonnegative(),
 scannedOrders:z.number().int().nonnegative(),
 partialScan:z.boolean(),issueCount:z.number().int().nonnegative(),
 findings:z.array(finding)
})
export type FinanceException=z.infer<typeof finding>
export type FinanceExceptions=z.infer<typeof queue>

export async function getFinanceExceptions(marketplace:string,limit=50):
 Promise<FinanceExceptions> {
 if(limit<1||limit>100)throw Error('只允许检查最近 1–100 笔来源交易')
 const url='/api/v1/finance/marketplaces/'+encodeURIComponent(marketplace)+
  '/exceptions?limit='+limit
 const response=await fetch(url,{method:'GET',cache:'no-store',
  credentials:'same-origin',headers:{Accept:'application/json'}})
 if(!response.ok)throw Error(response.status===404?'当前站点不可访问':
  response.status===403?'没有财务异常检查权限':
  '异常检查接口 HTTP '+response.status)
 return queue.parse(await response.json())
}
