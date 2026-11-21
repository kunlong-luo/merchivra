import {useQuery} from '@tanstack/react-query'
import {getPeriodProfitWaterfallV188} from './lib/period-profit-waterfall-v188'

const costNames:Record<string,string>={
 ADS_SPEND:'广告',INBOUND_FREIGHT:'头程物流',
 FBA_STORAGE:'FBA 仓储',FBM_SHIPPING:'FBM 运费',
 RETURN_HANDLING:'退货处理',IMPORT_DUTY:'进口税费',
 PAYMENT_AND_FX:'支付与汇兑',OTHER_OPERATING:'其他经营费用'
}
const stateNames:Record<string,string>={
 ORDER_FINANCIAL_BASELINE_INCOMPLETE:'V64 订单贡献未全部就绪，无法形成全期费用敏感度',
 ADDITIONAL_COST_SOURCES_UNKNOWN:'本期无 V91 人工来源，不代表额外成本为零',
 MIXED_ORIGINAL_CURRENCY_BLOCKED:'发现其他原币费用，缺 G1/DEC-08 汇率规则，禁止合并',
 CROSS_SOURCE_COLLISION_BLOCKED:'跨来源身份或共用结算行存在冲突，禁止再次扣款',
 V92_UNVERIFIED_COST_DISTRIBUTION_INCOMPLETE:'成本方案待审、未分配或审计不完整',
 UNVERIFIED_COST_SENSITIVITY_ONLY:'仅原币、全部本地审核通过的费用分配算术敏感度'
}
/** Embedded directly in the existing immutable V64 period profit workspace. */
export default function PeriodProfitWaterfallPanelV188({
 tenantId,marketplaceId,reportId
}:{
 tenantId:string;marketplaceId:string;reportId:string
}){
 const info=useQuery({
  queryKey:['finance','v188-period-profit-waterfall',tenantId,marketplaceId,reportId],
  queryFn:()=>getPeriodProfitWaterfallV188(marketplaceId,reportId),
  enabled:!!marketplaceId&&!!reportId,
  retry:false,refetchInterval:15000
 })
 const p=info.data
 return <section className="v180-version-panel"
  aria-label="V188 从销售到成本的原币期间贡献瀑布">
  <div className="v180-version-heading"><div>
   <span className="v180-section-kicker">V188 / SOURCE-AWARE COST WATERFALL</span>
   <h3>从销售到成本 · 全期利润核算预览</h3>
   <p>同一份 V64 冻结订单贡献与 V186/V187 原币费用来源、冲突风险联合核算。
    订单按 UTC 订单时间、人工费用按来源业务日期选期，二者并非财务确认期间。
    历史订单批次已冻结，费用证据仍实时变化，本预览不写回原报表。</p>
  </div></div>
  {info.isPending?<p className="setup-hint" role="status">
   正在核对全期订单金额、唯一费用来源与冲突风险…</p>:
  info.isError?<p role="alert" className="setup-error">
   {String(info.error)}。费用不可按零处理，停止展示成本调整。</p>:
  p&&<>
   <div className="ops-summary">
    {[
     ['已复核订单销售',p.eligibleSignedSales],
     ['Amazon 有符号费用（已在贡献中）',p.eligibleSignedAmazonFees],
     ['FBA 有符号费用（已在贡献中）',p.eligibleSignedFbaFees],
     ['历史商品成本 / COGS',p.eligibleProvisionalCogs],
     ['合格订单贡献（局部，不是净利润）',p.eligibleContribution],
     ['V92 已批但未外部核验的额外费用',p.approvedUnverifiedLocalCostExposure],
     ['上述费用后的原币算术敏感度',p.unverifiedCostSensitivityContribution]
    ].map(([label,value])=><div key={label}>
     <span>{label} · {p.currency}</span>
     <strong>{value??'证据不足 · 不计算'}</strong>
    </div>)}
   </div>
   <p className="v180-compare-hold" role="status">
    <strong>敏感度状态：</strong>{stateNames[p.sensitivityState]??p.sensitivityState}。
    候选订单 {p.totalOrders}，可计算 {p.eligibleOrders}，
    阻断 {p.blockedOrders}；本期人工来源 {p.reviewedCostSourceCount}，
    疑似跨来源冲突 {p.potentialCollisionSourceCount}，
    共用结算行来源 {p.sharedSettlementLineSourceCount}。
   </p>
   {p.costLines.length>0&&
    <div className="table-scroll" tabIndex={0} role="region"
     aria-label="V188 按原币与唯一主分摊维度显示八类人工费用">
     <table><thead><tr>
      {['成本类别','原币','唯一主分摊','来源数','申报金额（非已核验成本）',
       '已批未核验的分配','尚未分配'].map(t=>
       <th scope="col" key={t}>{t}</th>)}
     </tr></thead><tbody>{p.costLines.map(c=><tr
      key={c.category+'/'+c.currency+'/'+c.primaryDimension}>
      <td>{costNames[c.category]??c.category}</td>
      <td>{c.currency}</td><td>{c.primaryDimension}</td>
      <td>{c.sourceCount}</td><td>{c.claimedAmount}</td>
      <td>{c.approvedUnverifiedAllocatedAmount??'未知'}</td>
      <td>{c.approvedUnverifiedRemainderAmount??'未知'}</td>
     </tr>)}</tbody></table>
    </div>}
   <p className="v179-evidence-warning" role="status">
    <strong>已核验真实净利润：不可计算。</strong>
    本预览绝不将人工 V91/V92 费用升级为 V162 `REVIEWED_COST`，
    不再次扣已包含在销售贡献中的 Amazon/FBA 平台费用，
    不自动冲回退款 COGS，也不进行未经批准的汇率兑换。
    尚未有来源的费用类别：
    {p.categoriesWithoutSourceClaims.length?
      p.categoriesWithoutSourceClaims.map(c=>costNames[c]??c).join('、'):
      '无类别缺失记录，但不代表外部费用完整'}。
    证据阻断：{p.blockerCodes.join('、')}。
   </p>
  </>}
 </section>
}
