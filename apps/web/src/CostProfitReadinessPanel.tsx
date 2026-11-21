import {useEffect,useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import SourceEvidenceDrawer from './components/SourceEvidenceDrawer'
import CostLineIdentityPanel from './CostLineIdentityPanel'
import PeriodCostPreviewPanelV185 from './PeriodCostPreviewPanelV185'
import CostOwnershipBridgePanelV186 from './CostOwnershipBridgePanelV186'
import CostCrossSourceRiskPanelV187 from './CostCrossSourceRiskPanelV187'
import {fetchCostProfitReadiness,type CostReadinessCursor}
 from './lib/cost-profit-readiness'

const attributionLabels:Record<string,string>={
 CONFLICT_EVIDENCE:'本地归属冲突 · 待证明',
 SUPPORTING_LOCAL_METADATA_UNVERIFIED:'本地证据支持 · 未认证',
 UNPROVEN:'归属尚未证明'
}
const labels:Record<string,string>={
 ADS_SPEND:'广告',INBOUND_FREIGHT:'头程运输',
 FBA_STORAGE:'FBA 仓储',FBM_SHIPPING:'FBM 配送',
 RETURN_HANDLING:'退货处理',IMPORT_DUTY:'进口税费',
 PAYMENT_AND_FX:'银行支付/汇兑',OTHER_OPERATING:'其他经营成本'
}
function monthNow(){
 const d=new Date()
 return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')
}
function endOfMonthExclusive(month:string){
 const [year,m]=month.split('-').map(Number)
 return new Date(Date.UTC(year,m,1)).toISOString().slice(0,10)
}
/**
 * An original-currency, local-claim workbench shared by Next and Vite.
 * Deliberately NOT named a net-profit widget: V91/92/94 remain unverified.
 */
export default function CostProfitReadinessPanel({tenantId,marketplaceId}:{
 tenantId:string;marketplaceId:string
}){
 const [month,setMonth]=useState(monthNow)
 const [inspectedSourceId,setInspectedSourceId]=useState('')
 const [history,setHistory]=useState<Array<CostReadinessCursor|undefined>>([undefined])
 const cursor=history.at(-1)
 const start=month+'-01'
 const end=endOfMonthExclusive(month)
 const readiness=useQuery({
  queryKey:['finance','cost-readiness',tenantId,marketplaceId,start,end,cursor],
  queryFn:()=>fetchCostProfitReadiness(marketplaceId,start,end,cursor),
  enabled:!!marketplaceId,retry:false
 })
 useEffect(()=>setHistory([undefined]),[marketplaceId,month])
 useEffect(()=>setInspectedSourceId(''),[marketplaceId,month,cursor])
 const data=readiness.data
 const inspected=data?.sources.find(s=>s.sourceId===inspectedSourceId)
 const remaining=Object.keys(labels).filter(cat=>
  !data?.buckets.some(b=>b.category===cat))
 return <section className="panel full-panel" aria-label="八类成本核算准备度">
  <div className="panel-heading"><div>
   <h2>全成本费用准备度 · 原币（未核验）</h2>
   <p>从 PostgreSQL 的人工费用和分摊事实读取，展示原币申报、已审元数据、获批但未核验的分配，以及冲突。没有真实费用认证，不能扣入净利润。</p>
  </div><button type="button" className="refresh-button"
   disabled={readiness.isFetching} onClick={()=>void readiness.refetch()}>
   {readiness.isFetching?'读取中…':'刷新'}</button></div>
  <div className="core-controls">
   <label htmlFor="cost-readiness-month">业务月份</label>
   <input id="cost-readiness-month" type="month" value={month}
    min="2020-01" max="2100-12" onChange={e=>{
      if(/^\d{4}-\d{2}$/.test(e.target.value))setMonth(e.target.value)
    }}/>
  </div>
  <CostOwnershipBridgePanelV186 tenantId={tenantId}
   marketplaceId={marketplaceId} start={start} end={end}/>
  <CostCrossSourceRiskPanelV187 tenantId={tenantId}
   marketplaceId={marketplaceId} start={start} end={end}/>
  {readiness.isError?<p role="alert" className="setup-error">
   {readiness.error instanceof Error?readiness.error.message:'费用准备度读取失败'}
  </p>:readiness.isPending?<p className="empty-state">正在读取当前月份成本来源…</p>:
  data&&<>
   <p className="setup-hint" role="status">
    当前月份 {data.periodStart} 至 {data.periodEndExclusive}（不含结束日）。
    完整净利润：<strong>不可计算</strong>；外部来源认证：未完成；
    平台到账核对：未完成。以下所有金额都是人工主张，不能计入已核验利润。
   </p>
   <p className="setup-hint" role="status">
    全期间来源 {data.evidenceBarriers.sourceCount} 条（不受当前 50 条分页影响）；
    元数据待复核 {data.evidenceBarriers.metadataReviewPendingSourceCount} 条，
    未关联结算行 {data.evidenceBarriers.noSettlementAssociationSourceCount} 条，
    历史原始快照缺失 {data.evidenceBarriers.missingFrozenCaptureSourceCount} 条，
    共用结算行 {data.evidenceBarriers.sharedSettlementLineSourceCount} 条，
    结算行审核待完成 {data.evidenceBarriers.pendingLineReviewSourceCount} 条，
    审核审计缺失 {data.evidenceBarriers.missingLineAuditSourceCount} 条，
    一笔费用关联多行 {data.evidenceBarriers.multipleLineClaimsSourceCount} 条。
    <strong> 未获外部费用真实性认证 {data.evidenceBarriers.externallyUnverifiedSourceCount} 条；
    V162/V64 真实利润核算仍被阻断。</strong>
    未关联 Amazon 结算行只代表本地关联缺口，不等于非 Amazon 费用不合法。
   </p>
   {data.buckets.length===0?
    <p className="empty-state">本期间暂无人工费用来源。八类成本仍为未知，不代表金额为零。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="人工费用按类别及原币分组">
     <table><thead><tr>
      {['费用类别','原币','来源数','人工申报金额','元数据已审但未核验金额',
       '获批但未核验分配','获批未分配余额','共用账单行的来源数'].map(h=>
        <th scope="col" key={h}>{h}</th>)}
     </tr></thead><tbody>{data.buckets.map(b=>
      <tr key={b.category+'/'+b.currency}>
       <td>{labels[b.category]??b.category}</td><td>{b.currency}</td>
       <td>{b.sourceCount}</td><td>{b.claimedAmount}</td>
       <td>{b.reviewedUnverifiedAmount}</td>
       <td>{b.approvedUnverifiedAllocationAmount}</td>
       <td>{b.approvedUnverifiedUnallocatedAmount}</td>
       <td>{b.sharedLineSourceCount}</td>
      </tr>)}</tbody></table></div>}
   <p className="setup-hint">
    仍缺外部核验证据的科目：{data.categoriesWithoutVerifiedExternalCoverage
      .map(x=>labels[x]??x).join('、')}。
    {remaining.length?'当前月份无人工费用来源的类别：'+remaining.map(x=>labels[x]).join('、')+'。':''}
    “没有来源”与“零费用”不同。
   </p>
   <div className="panel-heading"><div>
    <h3>费用来源与独立分摊状态</h3>
    <p>逐来源查看现有独立复核、账单关联和重复归属风险；最多 50 条/页。</p>
   </div></div>
   {data.sources.length===0?<p className="empty-state">当前页无费用来源。</p>:
    <div className="table-scroll" role="region" tabIndex={0}
     aria-label="人工费用来源准备度">
     <table><thead><tr>{['来源 ID','业务日期','类别','申报原币金额',
      '元数据复核','分摊','已批准未核验分配','未分配',
      '结算行关联 / 共用','归属证据判定','审计详情'].map(h=>
       <th key={h} scope="col">{h}</th>)}
     </tr></thead><tbody>{data.sources.map(s=>
      <tr key={s.sourceId}>
       <td title={s.sourceId}><code>{s.sourceId.slice(0,8)}</code></td>
       <td>{s.businessDate}</td>
       <td>{labels[s.category]??s.category}</td>
       <td>{s.currency} {s.claimedAmount}</td>
       <td>{s.metadataReviewState}</td>
       <td>{s.activeAllocationState??'尚无有效分摊'}</td>
       <td>{s.approvedUnverifiedAllocationAmount??'尚未批准'}</td>
       <td>{s.approvedUnverifiedUnallocatedAmount??'未知'}</td>
       <td>{s.linkedSettlementLineCount} / {s.sharedSettlementLineCount}</td>
       <td title={s.localAttributionReasonCodes.join('、')}>
        <strong>{attributionLabels[s.localAttributionStatus]}</strong>
        {s.approvalHeld?' · 审批阻断':''}
       </td>
       <td><button className="text-button" type="button"
        onClick={()=>setInspectedSourceId(s.sourceId)}
        aria-label={'查看费用来源审计 '+s.sourceId}>查看关联</button></td>
      </tr>)}</tbody></table></div>}
   <SourceEvidenceDrawer open={!!inspected} title="费用来源 · 账单关联线索"
    description="当前站点、当前月份、当前来源分页的本地申报与关联状态；不是外部平台核验"
    onClose={()=>setInspectedSourceId('')}>
    {inspected&&<>
     <div className="v179-evidence-tags">
      <span className="v179-source-tag">{labels[inspected.category]??inspected.category}</span>
      <span className="v179-source-tag">{inspected.currency}</span>
      <span className="v179-source-tag">{inspected.approvalHeld?'存在阻断':'未发现本地审批阻断'}</span>
     </div>
     <dl className="v179-evidence-facts">
      <div><dt>本地费用来源 UUID</dt><dd><code>{inspected.sourceId}</code></dd></div>
      <div><dt>业务日期</dt><dd>{inspected.businessDate}</dd></div>
      <div><dt>人工申报金额 · 原币</dt><dd>{inspected.currency} {inspected.claimedAmount}（未认证）</dd></div>
      <div><dt>元数据独立复核</dt><dd>{inspected.metadataReviewState}</dd></div>
      <div><dt>当前分摊状态</dt><dd>{inspected.activeAllocationState??'尚无有效分摊'}</dd></div>
      <div><dt>已批准但未核验分配</dt><dd>{inspected.approvedUnverifiedAllocationAmount??'尚未批准'}</dd></div>
      <div><dt>未分配金额</dt><dd>{inspected.approvedUnverifiedUnallocatedAmount??'未确认'}</dd></div>
      <div><dt>本地身份线索</dt><dd>{inspected.localIdentityStatus}（非外部身份认证）</dd></div>
      <div><dt>V183 归属证据判定</dt>
       <dd>{attributionLabels[inspected.localAttributionStatus]}（不得推导真实利润）</dd></div>
      <div><dt>证据原因代码</dt><dd>{inspected.localAttributionReasonCodes.join('、')}</dd></div>
      <div><dt>创建时冻结 / 历史缺证数量</dt>
       <dd>{inspected.frozenIdentityCount} / {inspected.missingCaptureCount}</dd></div>
      <div><dt>独立审核与审计是否齐备</dt>
       <dd>{inspected.independentReviewAuditComplete?'齐备（仅本地）':'不齐备或不适用'}</dd></div>
      <div><dt>关联参考账单行数量</dt><dd>{inspected.linkedSettlementLineCount}</dd></div>
      <div><dt>其中与其他来源共用的账单行数量</dt><dd>{inspected.sharedSettlementLineCount}</dd></div>
      <div><dt>外部来源认证</dt><dd>尚未完成</dd></div>
      <div><dt>允许进入真实净利润</dt><dd>否</dd></div>
     </dl>
     <CostLineIdentityPanel tenantId={tenantId}
      marketplaceId={marketplaceId} sourceId={inspected.sourceId}/>
     <PeriodCostPreviewPanelV185 tenantId={tenantId}
      marketplaceId={marketplaceId} sourceId={inspected.sourceId}/>
     <p className="v179-evidence-warning">
      共用参考账单行是重复归属风险线索，不能单凭编号相似就判定重复扣费；
      已存在本地关联也不代表 Amazon 或银行原始交易已验证。
      缺独立可信外部来源前，不得将申报金额自动扣入 V64 贡献或 V162 最终净利润。</p>
    </>}
   </SourceEvidenceDrawer>
   <div className="sandbox-actions" role="navigation" aria-label="人工费用来源分页">
    <span className="setup-hint">第 {history.length} 页 · 当前 {data.sources.length} 条</span>
    {history.length>1&&<button type="button" className="refresh-button"
      onClick={()=>setHistory(xs=>xs.slice(0,-1))}>上一页</button>}
    {data.hasMore&&data.nextBeforeDate&&data.nextBeforeId&&
     <button type="button" className="refresh-button"
      disabled={readiness.isFetching}
      onClick={()=>setHistory(xs=>[...xs,{
       beforeDate:data.nextBeforeDate!,beforeId:data.nextBeforeId!
      }])}>下一页</button>}
   </div>
  </>}
 </section>
}
