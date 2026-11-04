import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {businessQualityCsvUrl,fetchBusinessOverview,
 type BusinessSources} from './lib/business-overview-v89'
import {exportPeriodProfitUrl} from './lib/period-profit'
import {formatSourceTime} from './lib/core-view'

function SourceBreakdown({sources}:{sources:BusinessSources}){
 return <span>模拟 {sources.mock} · 未核验 {sources.unverified} · LIVE 来源 {sources.live}</span>
}
export default function BusinessOverviewWorkspace({tenantId,onOpenFinance}:{
 tenantId:string;onOpenFinance:()=>void
}){
 const [selectedId,setSelectedId]=useState('')
 const data=useQuery({queryKey:['business-overview-v89',tenantId],
  queryFn:fetchBusinessOverview,staleTime:15000,retry:1})
 const list=data.data?.marketplaces??[]
 const selected=list.find(m=>m.id===selectedId)??list[0]
 const report=selected?.latestReport

 return <div className="core-workspace">
  <div className="core-intro"><strong>PostgreSQL 核心来源 · V89 数据质量总览</strong>
   <p>逐 Marketplace 汇总所有已入库 Listing、订单和每 SKU 最新 FBA 观测，
   不依赖 Demo Gateway，也不把浏览器第一页当作全量数据。</p>
   <p>这些是当前数据库读取结果，而非同一时点的 Amazon 上游快照、实盘库存或银行核对结果。</p>
  </div>
  <div className="core-controls">
   <button type="button" className="refresh-button"
    onClick={()=>void data.refetch()} disabled={data.isFetching}>
    {data.isFetching?'读取中…':'刷新经营质量'}</button>
   {data.data&&<a href={businessQualityCsvUrl}
    className="text-button" download="merchivra-business-source-quality.csv">
    导出当前数据质量 CSV</a>}
  </div>
  {data.isPending?<p className="empty-state" role="status">正在读取租户全部 Marketplace 来源质量…</p>:
   data.isError?<section className="state-panel error-panel" role="alert">
    <h2>经营来源质量读取失败</h2>
    <p>{data.error instanceof Error?data.error.message:'请检查会话与 PostgreSQL 状态'}</p>
    <button type="button" className="refresh-button"
     onClick={()=>void data.refetch()}>重新读取</button>
   </section>:
   data.data?< >
    <p role="status" className="panel-caption">
     本次查询时间 {formatSourceTime(data.data.capturedAt)} ·
     当前可访问的 Owner 租户共 {list.length} 个 Marketplace；
     没有全局资金快照、银行认证或会计过账。
     {data.data.mixedCurrencies?'检测到不同原始币种：不得直接合并金额。':
      '所有金额仅允许按各自 Marketplace 的 V64 已冻结报告分别查看。'}
    </p>
    {!list.length?<section className="panel full-panel">
     <h2>当前租户尚未建立 Marketplace</h2>
     <p>配置站点后再读取来源质量；不会自动开启 Amazon 授权。</p>
    </section>:<>
     <section className="panel full-panel" aria-label="各 Marketplace 全库数据质量">
      <div className="panel-heading"><div>
       <h2>站点覆盖和来源风险</h2>
       <p>数量按各站点数据库事实独立计算，不包含任意本地演示预估销售额。</p>
      </div></div>
      <div className="table-scroll" role="region" tabIndex={0}
       aria-label="各 Marketplace 数据质量比较表">
       <table><thead><tr>
        {['Marketplace','原始币种','Listing','待映射','冲突','订单',
          '财务未观测订单','最新 FBA SKU','库存未知','库存过期','报告状态'].map(label=>
          <th key={label} scope="col">{label}</th>)}
       </tr></thead><tbody>
        {list.map(m=><tr key={m.id}>
         <td className="strong-cell">{m.marketplaceId}</td>
         <td>{m.currency}</td>
         <td>{m.listings.total}</td><td>{m.listings.unmapped}</td>
         <td>{m.listings.conflicts}</td><td>{m.orders.total}</td>
         <td>{m.orders.financeNotObserved}</td><td>{m.inventory.skus}</td>
         <td>{m.inventory.unknown}</td><td>{m.inventory.stale}</td>
         <td>{m.latestReport?.state??'尚无 V64 报告'}</td>
        </tr>)}
       </tbody></table>
      </div>
      <p className="panel-caption">没有跨店铺金额合计；库存列是最新每 SKU 质量数量，
       不是将多期观测或不同仓库库存相加。</p>
     </section>

     <div className="core-controls">
      <label className="shop-filter" htmlFor="business-marketplace-select">
       <span>查看单站点来源详情</span>
       <select id="business-marketplace-select" value={selected?.id??''}
        onChange={e=>setSelectedId(e.target.value)}>
        {list.map(m=><option key={m.id} value={m.id}>
         {m.marketplaceId} · {m.region} · {m.currency}</option>)}
       </select>
      </label>
     </div>
     {selected&&<>
      <section className="metric-grid" aria-label="当前站点来源质量摘要">
       {[
        ['Listing',selected.listings.total+' 条','当前站点已入库总量'],
        ['订单',selected.orders.total+' 笔','不推断预计收入或净利润'],
        ['最新 FBA',selected.inventory.skus+' 个 SKU','每 SKU 只选择最新一条来源观测'],
        ['未知库存',selected.inventory.unknown+' 个 SKU','UNKNOWN 不是 0，可售数缺失另计']
       ].map(([label,value,foot])=>
        <div className="metric-card" key={label}>
         <div className="metric-label">{label}</div>
         <div className="metric-value">{value}</div>
         <div className="metric-foot">{foot}</div>
        </div>)}
      </section>
      <section className="two-column">
       <div className="panel"><div className="panel-heading"><div>
        <h2>Listing 映射与来源</h2>
        <p>全部 Listing，未映射/冲突不被自动丢弃或转换为可售 SKU。</p>
       </div></div>
       <div className="insight-row"><div>
        <strong>待映射 {selected.listings.unmapped} · 冲突 {selected.listings.conflicts}</strong>
        <small>缺失来源更新时间 {selected.listings.unknownSourceTime}</small>
       </div></div>
       <p className="panel-caption"><SourceBreakdown sources={selected.listings.sources}/></p>
       </div>
       <div className="panel"><div className="panel-heading"><div>
        <h2>订单来源完整度</h2>
        <p>仅反映入库订单的来源状态，不将订单商品小计当作净收入。</p>
       </div></div>
       <div className="insight-row"><div>
        <strong>订单项未观测 {selected.orders.itemsNotObserved}</strong>
        <small>财务未观测 {selected.orders.financeNotObserved} ·
         退款状态未知 {selected.orders.unknownRefund}</small>
        <small>缺少来源更新时间 {selected.orders.unknownSourceTime}</small>
       </div></div>
       <p className="panel-caption"><SourceBreakdown sources={selected.orders.sources}/></p>
       </div>
      </section>
      <section className="panel full-panel">
       <div className="panel-heading"><div>
        <h2>FBA 最新观测质量</h2>
        <p>严格复用原始观测排序：来源观测时间优先，
         再用 ERP 入库时间与 UUID 打破并列；不凭 UNKNOWN 补写 0。</p>
       </div></div>
       <div className="ops-summary">
        {[
         ['OBSERVED（已观测）',selected.inventory.observed],
         ['PARTIAL（不完整）',selected.inventory.partial],
         ['UNKNOWN（未知）',selected.inventory.unknown],
         ['STALE（原始过期标记）',selected.inventory.stale],
         ['可售数量为 NULL',selected.inventory.unknownFulfillable],
         ['已观测可售确切为 0',selected.inventory.observedZeroFulfillable],
         ['无来源观测时间',selected.inventory.unknownSourceTime]
        ].map(([title,count])=><div key={title}>
         <span>{title}</span><strong>{count}</strong>
        </div>)}
       </div>
       <p className="panel-caption"><SourceBreakdown sources={selected.inventory.sources}/></p>
       <p className="setup-hint">OBSERVED 为 0 不代表该站点所有 SKU 缺货；
       STALE 正数不能当作当前可售，质量计数不表示库存单位总和。</p>
      </section>
      <section className="panel full-panel">
       <div className="panel-heading"><div>
        <h2>V64 已持久化来源贡献报告</h2>
        <p>只引用当前 Marketplace 的最新已有报告，不在看板中建立第二套财务计算口径。</p>
       </div></div>
       {!report?<p className="empty-state">
        暂无已创建的 V64 报告。没有可信财务来源时，不展示利润数字。</p>:<>
        <p>报告期间（UTC）{report.after} 至 {report.before}（不含） ·
         原始币种 {report.currency} · 状态 {report.state}</p>
        <p>候选订单 {report.totalOrders} · 已提取 {report.processedOrders} ·
         已合格 {report.eligibleOrders} · 阻断 {report.blockedOrders}</p>
        <p className="metric-value">
         {report.state==='SUCCEEDED'&&report.contribution!==null?
          report.currency+' '+report.contribution:'贡献金额暂不可计算'}
        </p>
        <p className="setup-hint">即便 SUCCEEDED，也仅表示候选订单处理完成：
         银行未核验、税费/真实广告/汇兑可能未覆盖，
         不表示全球同一时点冻结、正式净利润或已会计过账。</p>
        {report.state==='SUCCEEDED'&&
         <a className="text-button"
          href={exportPeriodProfitUrl(selected.id,report.id)}
          download="merchivra-period-contribution-snapshot.csv">
          使用原 V64 接口导出已冻结明细 CSV
         </a>}
       </>}
       <button className="refresh-button" type="button"
        onClick={onOpenFinance}>到财务与对账查看报告及原始来源</button>
      </section>
     </>}
    </>}
   </>:null}
 </div>
}
