import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchCoreMarketplaces} from './lib/core'
import {getExceptionInsights} from './lib/exception-insights-v80'
import {ruleNames} from './lib/operations'
import {formatSourceTime} from './lib/core-view'

const ranks:Record<string,string>={
 P0:'立即重新核查',P1:'优先处理',P2:'需要跟进',P3:'常规跟进'
}
type Filter='ALL'|'P0'|'P1'|'UNASSIGNED'
export default function OpsExceptionInsightsPanel({tenantId,onOpenFbaException}:{
 tenantId:string;onOpenFbaException:(marketplaceId:string,lineId:string)=>void
}){
 const [chosen,setChosen]=useState('')
 const [filter,setFilter]=useState<Filter>('ALL')
 const marketplaces=useQuery({queryKey:['core','marketplaces',tenantId],
  queryFn:fetchCoreMarketplaces,staleTime:30000,retry:false})
 const marketplaceId=marketplaces.data?.some(m=>m.id===chosen)?chosen:
  marketplaces.data?.[0]?.id??''
 const insight=useQuery({queryKey:['operations',tenantId,'insights-v80',marketplaceId],
  queryFn:()=>getExceptionInsights(marketplaceId),enabled:!!marketplaceId,
  staleTime:30000,retry:false})
 const data=insight.data
 const priorities=(data?.recommendations??[]).filter(item=>
  filter==='ALL'||filter==='UNASSIGNED'&&item.assigneeId===null||
  filter===item.priority)
 const ownerName=(id:string|null)=>
  id?data?.workload.owners.find(o=>o.userId===id)?.displayName??'不在当前有效 Owner 列表':'未分派'
 const totalOps=(data?.operations??[]).reduce((sum,x)=>sum+x.open+x.acknowledged,0)
 return <section className="panel" aria-label="V80 跨模块异常处理建议看板">
  <div className="panel-heading"><div><h2>V80 · 跨模块异常处理与优先级建议</h2>
   <p>联合本地采购/FBA 处理单、Operations 巡检结果与有效 Owner 工作量。
    优先级为透明规则，不是 Amazon 官方 SLA 或 AI 预测；所有建议仍须人工确认。</p>
  </div><span className="status-chip chip-muted">只读建议 · 不自动分派</span></div>
  {marketplaces.isPending?<p className="empty-state">正在读取可访问的 Marketplace…</p>:
   marketplaces.isError?<p className="setup-error" role="alert">读取店铺失败：{String(marketplaces.error)}</p>:
   !marketplaceId?<p className="empty-state">请先创建当前租户的本地 Marketplace。</p>:
   <>
    <div className="ops-toolbar"><label className="shop-filter">选择站点
     <select aria-label="V80 异常看板 Marketplace" value={marketplaceId}
      onChange={e=>{setChosen(e.target.value);setFilter('ALL')}}>
      {marketplaces.data?.map(m=><option key={m.id} value={m.id}>
       {m.marketplaceId} · {m.region} · {m.connectionStatus}</option>)}
     </select></label>
     <button type="button" className="refresh-button"
      disabled={insight.isFetching} onClick={()=>void insight.refetch()}>
      {insight.isFetching?'正在核查…':'刷新本地证据与建议'}</button>
    </div>
    {insight.isPending?<p className="empty-state">正在比对站点内的本地证据…</p>:
     insight.isError?<p className="setup-error" role="alert">
      获取异常建议失败：{String(insight.error)}</p>:
     data&&<>
      <div className="ops-summary" role="status" aria-label="本地异常闭环与待办总览">
       {([
        {title:'已登记异常',value:data.cases.total},
        {title:'待处理',value:data.cases.open},
        {title:'确认待跟进',value:data.cases.acknowledged},
        {title:'本地完成',value:data.cases.resolved},
        {title:'本地超期',value:data.cases.overdue},
        {title:'本店 Ops 活跃待办',value:totalOps}
       ]).map(x=><div key={x.title}><span>{x.title}</span>
        <strong>{x.value}</strong></div>)}
      </div>
      <p className="panel-caption">本地处理单闭环比例：
       {data.cases.total?Math.round(data.cases.resolved/data.cases.total*100)+'%':'暂无已登记处理单，无法计算'}。
       该比例仅代表人工标记完成，不代表 Amazon 已核验入仓或库存记账。</p>
      <section className="core-tools" aria-label="跨模块运营异常分类">
       <h3>运营异常来源分类（上次巡检结果）</h3>
       {!data.operations.length?<p className="empty-state">尚无已生成的 Ops 待办；
        可在上方运营工作台主动巡检。未巡检不等于没有异常。</p>:
       <div className="table-scroll" role="region" tabIndex={0} aria-label="按模块查看运营待办">
        <table><thead><tr>{['模块来源','待处理','已确认','Ops 已标记完成','条件已消失']
         .map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
         <tbody>{data.operations.map(item=><tr key={item.ruleCode}>
          <td>{ruleNames[item.ruleCode]}</td><td>{item.open}</td>
          <td>{item.acknowledged}</td><td>{item.done}</td>
          <td>{item.cleared}</td></tr>)}</tbody>
        </table></div>}
       <p className="setup-hint">Ops DONE 不能代替源单的 RESOLVED。
        来源分类只体现最近已执行的巡检，不会在读取页面时暗中运行调度。</p>
      </section>
      <section className="core-tools" aria-label="可解释的异常优先级">
       <div className="panel-heading"><div><h3>建议处理顺序（仅本地证据）</h3>
        <p>首先提示来源变化或期限超期，其次是独立身份审核、入仓差异和临期事项；
         最后建议分派未认领处理单。按优先级、截止时间和来源行稳定排序。</p>
       </div></div>
       <p className="setup-hint">本次观察最近 {data.shipmentScope} 张货件、
        {data.evidenceLines} 行，需人工跟进 {data.actionableCount} 行，
        当前仅显示排序前 {data.recommendations.length} 行。
        {data.recommendationsTruncated?' 处理建议超过 30 行，请在源工作台继续核对。':''}
        {data.sourceTruncated?' 来源已触及 500 行上限，当前优先级不代表所有历史。':''}</p>
       <label className="shop-filter">筛选优先级
        <select aria-label="V80 建议优先级筛选" value={filter}
         onChange={e=>setFilter(e.target.value as Filter)}>
         <option value="ALL">全部已加载建议</option>
         <option value="P0">P0 · 立即复核</option>
         <option value="P1">P1 · 优先处理</option>
         <option value="UNASSIGNED">尚未分派</option>
        </select></label>
       {!priorities.length?<p className="empty-state">本页条件下暂无建议；不代表站点无异常。</p>:
       <div className="table-scroll" tabIndex={0} role="region"
        aria-label="采购 FBA 异常处理优先级及人工建议">
        <table><thead><tr>{['顺序','货件 / SKU','触发原因','处理人建议','本地期限','下一步','原单']
         .map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
         <tbody>{priorities.map(item=><tr key={item.lineId}>
          <td><span className={'status-chip '+(item.priority==='P0'?'chip-amber':'chip-muted')}>
           {item.priority} · {ranks[item.priority]}</span></td>
          <td className="strong-cell">{item.shipmentReference}
           <div className="secondary-cell">{item.sellerSku}</div></td>
          <td>{item.findingCode}<div className="secondary-cell">{item.reasonCode}</div></td>
          <td>{ownerName(item.assigneeId)}
           {item.assigneeId===null&&item.recommendedOwnerId&&
            <div className="secondary-cell">建议由 {ownerName(item.recommendedOwnerId)} 跟进
             （按已登记未完成数最少）</div>}</td>
          <td className="small-cell">{item.dueAt?formatSourceTime(item.dueAt):'尚未设人工期限'}</td>
          <td>{item.nextStep}</td>
          <td><button type="button" className="text-button"
           onClick={()=>onOpenFbaException(marketplaceId,item.lineId)}>查看原处理单</button></td>
         </tr>)}</tbody></table></div>}
       <p className="panel-caption">负载推荐仅比较当前活跃 Owner 已登记的 OPEN/ACKNOWLEDGED 处理单数，
        不等于真实可用工时，也不自动转派。未登记/窗口外工作不计入比较；
        若无有效 Owner 则不提供推荐。</p>
      </section>
     </>}
   </>}
 </section>
}
