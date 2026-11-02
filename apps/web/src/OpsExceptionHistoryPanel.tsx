import {useQuery} from '@tanstack/react-query'
import {getExceptionAuditHistory} from './lib/exception-history-v81'

export default function OpsExceptionHistoryPanel({tenantId,marketplaceId,onOpenFbaException}:{
 tenantId:string;marketplaceId:string;
 onOpenFbaException:(marketplaceId:string,lineId:string)=>void
}){
 const history=useQuery({queryKey:['operations',tenantId,'audit-history-v81',marketplaceId],
  queryFn:()=>getExceptionAuditHistory(marketplaceId),enabled:!!marketplaceId,
  staleTime:30000,retry:false})
 const data=history.data
 const peak=data?Math.max(1,...data.daily.map(day=>
  Math.max(day.registered,day.resolvedActions,day.reopenActions))):1
 return <section className="core-tools" aria-label="V81 本地异常审计趋势和复发">
  <div className="panel-heading"><div>
   <h3>V81 · 近 30 天处理趋势、人工动作与复发</h3>
   <p>只分析本地不可变审计记录，不推测 Amazon 入仓结果，也不生成员工绩效排名。</p>
  </div><button type="button" className="refresh-button"
   onClick={()=>void history.refetch()} disabled={history.isFetching}>
    {history.isFetching?'读取中…':'刷新 30 天审计'}
   </button></div>
  {history.isPending?<p className="empty-state">读取最近 30 天的本地审计…</p>:
   history.isError?<p className="setup-error" role="alert">
    审计趋势暂不可用：{String(history.error)}；缺失数据不应当作零。</p>:
   data&&<>
    <p className="setup-hint">{data.fromDay} 至 {data.throughDay}（UTC，自然日，含今日）。
     仅包括本地人工工作台已登记的处理单及审计动作；今天可能尚未结束。</p>
    <div className="ops-summary" role="status" aria-label="30 天人工异常审计统计">
     {([
      {name:'新登记处理单',count:data.summary.registered},
      {name:'完成动作次数',count:data.summary.resolvedActions},
      {name:'重新打开次数',count:data.summary.reopenActions},
      {name:'被重新打开的不同处理单',count:data.summary.affectedByReopen},
      {name:'证据指纹更新次数',count:data.summary.evidenceRefreshActions}
     ]).map(x=><div key={x.name}><span>{x.name}</span><strong>{x.count}</strong></div>)}
    </div>
    <section aria-label="UTC 每日处理趋势">
     <h4>30 天每日趋势</h4>
     <div style={{display:'flex',alignItems:'flex-end',height:112,
       gap:3,overflowX:'auto',padding:'8px 0'}} role="img"
       aria-label="每天三组条形高度代表新登记、完成动作和重新打开次数；具体数字请查看下方每日审计明细表">
      {data.daily.map(day=><div key={day.day} title={day.day+
       ' 登记 '+day.registered+' / 完成 '+day.resolvedActions+
       ' / 重开 '+day.reopenActions} style={{
        display:'flex',alignItems:'flex-end',justifyContent:'center',
        gap:1,minWidth:15,height:'100%',flex:1,borderBottom:'1px solid #777'
       }}>
       <div style={{height:(day.registered/peak*90)+'%',background:'#3b82a0',
        flex:1,minHeight:day.registered?2:0}}/>
       <div style={{height:(day.resolvedActions/peak*90)+'%',background:'#398568',
        flex:1,minHeight:day.resolvedActions?2:0}}/>
       <div style={{height:(day.reopenActions/peak*90)+'%',background:'#c48645',
        flex:1,minHeight:day.reopenActions?2:0}}/>
      </div>)}
     </div>
     <p className="setup-hint">蓝：新登记　绿：本地完成动作　橙：人工重新打开。
      高度以窗口内最大每日事件数为基准；不会推断未记录事件。</p>
     <details className="core-tools"><summary>查看 30 天每日审计明细</summary>
      <div className="table-scroll" role="region" tabIndex={0} aria-label="按 UTC 日期的异常审计事件计数">
       <table><thead><tr>{['UTC 日期','新登记','完成动作','重新打开',
        '分派动作','确认动作','证据更新'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
        <tbody>{data.daily.map(x=><tr key={x.day}>
         <td>{x.day}</td><td>{x.registered}</td>
         <td>{x.resolvedActions}</td><td>{x.reopenActions}</td>
         <td>{x.assignedActions}</td><td>{x.acknowledgementActions}</td>
         <td>{x.evidenceRefreshActions}</td>
        </tr>)}</tbody></table>
      </div></details>
    </section>
    <section className="core-tools" aria-label="实际动作执行者与首次完成耗时">
     <h4>Owner / 操作人审计活动</h4>
     <p className="setup-hint">仅按审计中的实际执行人归属，不按当前分派负责人推算。
      平均首次完成用时 = 创建本地处理单到第一次 RESOLVE 的小时数，
      只统计第一次 RESOLVE 发生在本窗口的样本；无样本显示“不可计算”。
      并非员工效率评分、工作能力比较或 Amazon 处理时间。</p>
     {!data.actors.length?<p className="empty-state">本窗口没有人工操作审计。</p>:
      <div className="table-scroll" role="region" tabIndex={0} aria-label="人工处理动作执行者统计">
       <table><thead><tr>{['操作人','完成次数','重开次数','分派次数',
        '确认次数','平均首次完成小时'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
        <tbody>{data.actors.map(x=><tr key={x.userId}>
         <td>{x.displayName}</td><td>{x.resolvedActions}</td>
         <td>{x.reopenedActions}</td><td>{x.assignedActions}</td>
         <td>{x.acknowledgedActions}</td>
         <td>{x.meanFirstResolutionHours===null?'不可计算':x.meanFirstResolutionHours.toFixed(1)}
         </td></tr>)}</tbody></table>
      </div>}
     {data.actorsTruncated&&<p className="setup-hint">操作人超过 100 名，
      仅展示按完成动作排序的前 100 名，不能将本页当作全员排名。</p>}
    </section>
    <section className="core-tools" aria-label="复发异常来源处理单">
     <h4>复发及重新确认来源</h4>
     <p className="setup-hint">仅统计 30 天内真实存在的 REOPEN 审计或
      已被人工动作写入审计的证据指纹变更；还没人工确认的来源变化只在 V80
      即时建议中出现，不冒充历史复发。</p>
     {!data.recurrences.length?<p className="empty-state">
      本窗口没有记录到重复打开或人工复核后的来源更新。</p>:
      <div className="table-scroll" role="region" tabIndex={0} aria-label="有审计证据的重复异常列表">
       <table><thead><tr>{['货件 / SKU','当前本地状态','重开次数',
        '人工证据更新次数','原处理单'].map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
        <tbody>{data.recurrences.map(item=><tr key={item.lineId}>
         <td>{item.shipmentReference} · {item.sellerSku}</td>
         <td>{item.state==='RESOLVED'?'本地已完成':
              item.state==='ACKNOWLEDGED'?'已确认跟进':'待处理'}</td>
         <td>{item.reopenActions}</td><td>{item.evidenceRefreshActions}</td>
         <td><button type="button" className="text-button"
          onClick={()=>onOpenFbaException(marketplaceId,item.lineId)}>
          查看源处理单</button></td>
        </tr>)}</tbody></table>
      </div>}
     {data.recurrencesTruncated&&<p className="setup-hint">
      复发来源超过 20 条，仅展示按重开及证据更新次数排序的前 20 条。</p>}
    </section>
   </>}
 </section>
}
