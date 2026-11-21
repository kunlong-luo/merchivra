import {describe,expect,it} from 'vitest'
import type {PeriodProfitReport} from './period-profit'
import {compareFrozenReports,decimalDifference,summarizeCandidatePage}
 from './profit-report-insights'

const order=(state:'BLOCKED'|'ELIGIBLE'|'PENDING',readiness:string|null)=>
 ({orderId:'00000000-0000-4000-8000-000000000000',
   state,readiness}) as PeriodProfitReport['orders'][number]
const make=(changes:{
 state?:'READY'|'RETRY'|'SUCCEEDED'|'PAUSED',id?:string,
 currency?:string,after?:string,before?:string,
 totalOrders?:number,eligibleOrders?:number,blockedOrders?:number,
 totalsCurrency?:string,sales?:string,fees?:string,cogs?:string,
 contribution?:string
}={})=>({
 job:{id:changes.id??'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  state:changes.state??'SUCCEEDED',
  currency:changes.currency??'USD',
  after:changes.after??'2026-09-01',before:changes.before??'2026-10-01',
  totalOrders:changes.totalOrders??4,eligibleOrders:changes.eligibleOrders??3,
  blockedOrders:changes.blockedOrders??1},
 totals:{currency:changes.totalsCurrency??changes.currency??'USD',
  signedSales:changes.sales??'100.00',signedAmazonFees:changes.fees??'-20.00',
  signedFbaFees:'-5.00',provisionalCogs:changes.cogs??'-40.00',
  contribution:changes.contribution??'35.00'}
}) as PeriodProfitReport

describe('V180 profit evidence audit comparisons',()=>{
 it('is exact for currency decimals and big values; never JS Number cents',()=>{
  expect(decimalDifference('0.30','0.10')).toBe('+0.2')
  expect(decimalDifference('0.10','0.30')).toBe('-0.2')
  expect(decimalDifference('100.00','100')).toBe('0')
  expect(decimalDifference('100000000000000000000.01','99999999999999999999.99')).toBe('+0.02')
  expect(decimalDifference('-10.00','-15.15')).toBe('+5.15')
  expect(decimalDifference('-20.00','-12.20')).toBe('-7.8')
  expect(()=>decimalDifference('NaN','1')).toThrow('无效')
  expect(()=>decimalDifference('1e9','1')).toThrow('无效')
 })
 it('blocks cross-period, cross-currency, incomplete and unmatched coverage',()=>{
  const a=make()
  const another={id:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'}
  expect(compareFrozenReports(a,a).comparable).toBe(false)
  expect(compareFrozenReports(a,make({...another,currency:'EUR'})).reason).toMatch(/原币/)
  expect(compareFrozenReports(a,make({...another,after:'2026-08-01'})).reason).toMatch(/期间/)
  expect(compareFrozenReports(a,make({...another,state:'READY'})).reason).toMatch(/冻结/)
  expect(compareFrozenReports(a,make({...another,totalOrders:5})).reason).toMatch(/订单数量/)
  expect(compareFrozenReports(a,make({...another,eligibleOrders:2})).reason).toMatch(/订单数量/)
  expect(compareFrozenReports(a,make({...another,blockedOrders:2})).reason).toMatch(/订单数量/)
  expect(compareFrozenReports(a,make({...another,totalsCurrency:'EUR'})).reason).toMatch(/原币/)
 })
 it('returns original-currency snapshot differences without net profit claims',()=>{
  const a=make({sales:'101.25',contribution:'36.25',fees:'-20.00'})
  const b=make({id:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',sales:'100.00',contribution:'35.00'})
  const result=compareFrozenReports(a,b)
  expect(result.comparable).toBe(true)
  if(!result.comparable)throw Error(result.reason)
  expect(result.currency).toBe('USD')
  expect(result.rows.find(x=>x.label.includes('贡献'))?.delta).toBe('+1.25')
  expect(result.rows.find(x=>x.label.includes('有符号费用'))?.delta).toBe('0')
 })
 it('counts only the loaded page, not all 5,000 frozen candidates',()=>{
  const p=summarizeCandidatePage([
   order('BLOCKED','REFUND_COST_UNVERIFIED'),
   order('BLOCKED','REFUND_COST_UNVERIFIED'),
   order('BLOCKED',null),
   order('ELIGIBLE',null),
   order('PENDING',null)
  ])
  expect(p).toEqual({
   loaded:5,pending:1,eligible:1,blocked:3,
   reasons:[{code:'REFUND_COST_UNVERIFIED',count:2},
    {code:'BLOCK_REASON_UNSPECIFIED',count:1}]
  })
  expect(summarizeCandidatePage([]).loaded).toBe(0)
 })
})
