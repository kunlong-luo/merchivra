import type {PeriodProfitReport} from './period-profit'

/**
 * Presentation-only V180 audit summaries. These facts are read exclusively
 * from the authenticated V64 snapshots returned by the backend. They are
 * never bank reconciliation, complete cost coverage or posted net profit.
 */
type Order=PeriodProfitReport['orders'][number]
export function summarizeCandidatePage(orders:readonly Order[]){
 const blocked=new Map<string,number>()
 let pending=0,eligible=0,blockedCount=0
 for(const order of orders){
  if(order.state==='BLOCKED'){
   blockedCount++
   const reason=order.readiness?.trim()||'BLOCK_REASON_UNSPECIFIED'
   blocked.set(reason,(blocked.get(reason)??0)+1)
  } else if(order.state==='ELIGIBLE')eligible++
  else pending++
 }
 return {
  loaded:orders.length,pending,eligible,blocked:blockedCount,
  reasons:[...blocked.entries()].map(([code,count])=>({code,count}))
   .sort((a,b)=>b.count-a.count||a.code.localeCompare(b.code))
 }
}

/** Exact decimal subtraction: neither IEEE-754 cents nor currency FX. */
export function decimalDifference(current:string,baseline:string){
 const pattern=/^-?\d+(?:\.\d+)?$/
 if(!pattern.test(current)||!pattern.test(baseline))throw Error('无效原币金额')
 const parts=(x:string)=>x.replace(/^-/, '').split('.')
 const scale=Math.max(parts(current)[1]?.length??0,parts(baseline)[1]?.length??0)
 const integer=(x:string)=>{
  const [whole,dec='']=parts(x)
  const value=BigInt(whole+dec.padEnd(scale,'0'))
  return x.startsWith('-')?-value:value
 }
 const delta=integer(current)-integer(baseline)
 if(delta===0n)return '0'
 const minus=delta<0n
 const digits=(minus?-delta:delta).toString().padStart(scale+1,'0')
 if(!scale)return (minus?'-':'+')+digits
 const head=digits.slice(0,-scale)
 const fraction=digits.slice(-scale).replace(/0+$/,'')
 return (minus?'-':'+')+head+(fraction?'.'+fraction:'')
}

export const snapshotFields=[
 {key:'signedSales',label:'审核商品销售'},
 {key:'signedAmazonFees',label:'Amazon 有符号费用'},
 {key:'signedFbaFees',label:'FBA 有符号费用'},
 {key:'provisionalCogs',label:'历史商品成本（暂定）'},
 {key:'contribution',label:'来源贡献估算（非净利润）'}
] as const

export function compareFrozenReports(current:PeriodProfitReport,baseline:PeriodProfitReport){
 const a=current.job,b=baseline.job
 const reason=a.id===b.id?'不能与同一报表版本比较':
  a.state!=='SUCCEEDED'||b.state!=='SUCCEEDED'?'两个报表都必须冻结完成':
  a.currency!==b.currency||a.currency!==current.totals.currency||
   b.currency!==baseline.totals.currency?'不同原币：禁止跨币种计算差额':
  a.after!==b.after||a.before!==b.before?'不同 UTC 期间：禁止将不同月份当作版本差额':
  a.totalOrders!==b.totalOrders||a.eligibleOrders!==b.eligibleOrders||
   a.blockedOrders!==b.blockedOrders?
   '候选/合格/阻断订单数量不同：版本覆盖范围不具备可比性':
  snapshotFields.some(field=>current.totals[field.key]===null||
   baseline.totals[field.key]===null)?
   'V194 冻结捕获未经独立校验：金额未知，禁止计算版本差额':''
 if(reason)return {comparable:false as const,reason}
 return {
  comparable:true as const,reason:'',
  currency:a.currency,
  // Even equal counts cannot prove identical order IDs. Exact amount delta
  // is an arithmetic snapshot difference, not a proof of like-for-like gain.
  rows:snapshotFields.map(field=>({
   label:field.label,current:current.totals[field.key],
   baseline:baseline.totals[field.key],
   delta:decimalDifference(current.totals[field.key]!,baseline.totals[field.key]!)
  }))
 }
}
