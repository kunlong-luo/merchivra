import {useEffect,useState,type FormEvent} from 'react'
import CursorPageControls from './CursorPageControls'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {PurchaseLine} from './lib/purchasing'
import {fetchSupplierAsns} from './lib/supplier-asns'
import {
 fetchPurchaseVariancesPage,reportPurchaseVariance,actPurchaseVariance,
 fetchSupplierClaimsPage,createSupplierClaim,actSupplierClaim,
 fetchSupplierPerformance,fetchSupplierCaseEventsPage,
 fetchSupplierClaimSourcesPage,
 type ReportVariance,type CreateSupplierClaim,
 type CaseFilter,type PurchaseVariance
} from './lib/supplier-disputes'

const varianceKind:Record<string,string>={
 PO_SHORTAGE:'采购单尚未收足（人工观察）',
 ASN_SHORTAGE:'供应商申报已发货，关联实收未足',
 OVERAGE_UNPOSTED:'仓库报告超额实物（尚未入账）'
}
const claimStates:Record<string,string>={
 DRAFT:'索赔草稿',SUBMITTED:'待第二位 Owner 审批',
 APPROVED:'内部已审批（未对外提交）',
 CLOSED:'人工关闭（不代表到账）',CANCELLED:'已取消'
}
const varianceStates:Record<string,string>={
 OPEN:'待另一位 Owner 复核',REVIEWED:'已复核',CLOSED:'已关闭'
}
export default function SupplierDisputesPanel({tenantId,orderId,lines,currentUserId}:{
 tenantId:string;orderId:string;lines:PurchaseLine[];currentUserId:string
}){
 const client=useQueryClient()
 const root=['purchasing',tenantId]
 const [varianceFilter,setVarianceFilter]=useState<CaseFilter>('ACTIONABLE')
 const [claimFilter,setClaimFilter]=useState<CaseFilter>('ACTIONABLE')
 const [varianceCursors,setVarianceCursors]=useState<(string|null)[]>([null])
 const [claimCursors,setClaimCursors]=useState<(string|null)[]>([null])
 const [sourceCursors,setSourceCursors]=useState<(string|null)[]>([null])
 const [eventCursors,setEventCursors]=useState<(string|null)[]>([null])
 const varianceCursor=varianceCursors.at(-1)??null
 const claimCursor=claimCursors.at(-1)??null
 const sourceCursor=sourceCursors.at(-1)??null
 const eventCursor=eventCursors.at(-1)??null
 const [lineId,setLineId]=useState('')
 const [kind,setKind]=useState<PurchaseVariance['kind']>('PO_SHORTAGE')
 const [asnId,setAsnId]=useState('')
 const [excess,setExcess]=useState(1)
 const [reason,setReason]=useState('')
 const [reportKey,setReportKey]=useState(()=>crypto.randomUUID())
 const [claimSource,setClaimSource]=useState('')
 const [claimQty,setClaimQty]=useState(1)
 const [remedy,setRemedy]=useState<'REPLACEMENT'|'CREDIT_REQUEST'>('REPLACEMENT')
 const [claimReason,setClaimReason]=useState('')
 const [claimKey,setClaimKey]=useState(()=>crypto.randomUUID())
 const [closureNotes,setClosureNotes]=useState<Record<string,string>>({})
 const [viewEvent,setViewEvent]=useState<{id:string;type:'variances'|'claims'}|null>(null)
 const [message,setMessage]=useState(''),[error,setError]=useState('')
 useEffect(()=>{
  setVarianceCursors([null]);setClaimCursors([null]);setSourceCursors([null])
  setEventCursors([null]);setViewEvent(null);setClaimSource('')
 },[orderId])

 const variances=useQuery({
  queryKey:[...root,'disputes',orderId,'variances-page',varianceFilter,varianceCursor],
  queryFn:()=>fetchPurchaseVariancesPage(orderId,varianceFilter,varianceCursor),
  enabled:!!orderId,retry:false})
 const claims=useQuery({
  queryKey:[...root,'disputes',orderId,'claims-page',claimFilter,claimCursor],
  queryFn:()=>fetchSupplierClaimsPage(orderId,claimFilter,claimCursor),
  enabled:!!orderId,retry:false})
 const sources=useQuery({
  queryKey:[...root,'disputes',orderId,'claim-sources-page',sourceCursor],
  queryFn:()=>fetchSupplierClaimSourcesPage(orderId,sourceCursor),
  enabled:!!orderId,retry:false})
 const asns=useQuery({queryKey:[...root,'disputes',orderId,'asns'],
  queryFn:()=>fetchSupplierAsns(orderId),enabled:!!orderId,retry:false})
 const metrics=useQuery({queryKey:[...root,'supplier-performance'],
  queryFn:fetchSupplierPerformance,retry:false})
 const events=useQuery({queryKey:[...root,'case-events-page',viewEvent?.type,viewEvent?.id,eventCursor],
  queryFn:()=>fetchSupplierCaseEventsPage(viewEvent!.id,viewEvent!.type,eventCursor),
  enabled:viewEvent!==null,retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const reported=useMutation({mutationFn:(body:ReportVariance)=>
  reportPurchaseVariance(orderId,body),onSuccess:()=>{setMessage('已记录采购数量差异；未修改库存或任何财务流水');
   setError('');setReason('');setReportKey(crypto.randomUUID());
   setVarianceCursors([null]);setSourceCursors([null]);refresh()},
  onError:(e:Error)=>setError(e.message)})
 const varianceAction=useMutation({
  mutationFn:({id,action}:{id:string;action:'review'|'close'})=>
   actPurchaseVariance(id,action),
  onSuccess:()=>{setMessage('采购差异审阅状态已更新');setError('');
   setVarianceCursors([null]);setSourceCursors([null]);refresh()},
  onError:(e:Error)=>setError(e.message)})
 const claimCreated=useMutation({mutationFn:(body:CreateSupplierClaim)=>
  createSupplierClaim(body),onSuccess:()=>{setMessage('仅本地建立供应商索赔草稿');
   setError('');setClaimReason('');setClaimKey(crypto.randomUUID());
   setClaimCursors([null]);setSourceCursors([null]);setClaimSource('');refresh()},
  onError:(e:Error)=>setError(e.message)})
 const claimAction=useMutation({
  mutationFn:({id,action,note}:{id:string;
   action:'submit'|'approve'|'close'|'cancel';note?:string})=>
   actSupplierClaim(id,action,note),
  onSuccess:()=>{setMessage('索赔状态已更新，未向供应商发起真实系统请求或退款');
   setError('');setClaimCursors([null]);setSourceCursors([null]);refresh()},
  onError:(e:Error)=>setError(e.message)})

 const selectedLine=lines.find(l=>l.id===lineId)
 const eligibleAsns=asns.data?.filter(a=>a.lineId===lineId&&
  a.status==='SHIPPED'&&a.receivedQuantity<a.quantity)??[]
 const canReport=!!selectedLine&&reason.trim().length>=5&&reason.trim().length<=300&&
  !reported.isPending&&
  (kind!=='ASN_SHORTAGE'||eligibleAsns.some(a=>a.id===asnId))&&
  (kind!=='OVERAGE_UNPOSTED'||(Number.isSafeInteger(excess)&&excess>0&&excess<=1000000))
 function report(e:FormEvent){
  e.preventDefault();if(!canReport)return
  if(!window.confirm('保存当前人工观察的短装或溢装差异？不会自动修改入库、ASN 或财务。'))return
  reported.mutate({requestKey:reportKey,lineId,kind,
   asnId:kind==='ASN_SHORTAGE'?asnId:null,
   reportedExcessQuantity:kind==='OVERAGE_UNPOSTED'?excess:0,reason:reason.trim()})
 }
 // Server computes eligibility across every reviewed variance and QC record,
 // excluding claims across ALL statuses, not just this visible page.
 const sourceOptions=(sources.data?.items??[]).map(v=>({
  value:(v.sourceType==='VARIANCE'?'v:':'q:')+v.sourceId,max:v.maxQuantity,
  label:(v.sourceType==='VARIANCE'?'已复核数量差异 ':'已确认质检不合格 ')+
   v.sourceId.slice(0,8)+' · '+v.maxQuantity+' 件'
 }))
 const chosen=sourceOptions.find(s=>s.value===claimSource)
 const canClaim=!!chosen&&Number.isSafeInteger(claimQty)&&claimQty>=1&&
  claimQty<=chosen.max&&claimReason.trim().length>=5&&claimReason.trim().length<=300&&
  !sources.isFetching&&!sources.isError&&!claimCreated.isPending
 function claim(e:FormEvent){
  e.preventDefault();if(!canClaim)return
  if(!window.confirm('仅建立本地索赔草稿，随后需提交并由另一位 Owner 审批；不会自动对外发送。'))return
  claimCreated.mutate({requestKey:claimKey,varianceId:claimSource.startsWith('v:')?
   claimSource.slice(2):null,inspectionId:claimSource.startsWith('q:')?
   claimSource.slice(2):null,requestedQuantity:claimQty,remedy,
   reason:claimReason.trim()})
 }
 return <section className="panel full-panel" aria-label="采购差异与供应商索赔">
  <div className="panel-heading"><div><h2>收货差异、供应商索赔与交付绩效</h2>
   <p>短装只代表当前观测点尚未收足，非最终损失；溢装报告为未入账的实物，
    绝不绕过采购单的收货上限。索赔审批是内部管理状态，不代表供应商接受、补发或退款到账。</p>
  </div><button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <h3>供应商实际经营数据（全部当前租户）</h3>
  <p className="setup-hint">统计已审批、正在收货及已收货采购单的订货和实收件数；
   是累计收货进度，不是准时交货率。仅已完成质检的收货才贡献不合格件数。</p>
  {metrics.isPending?<p className="empty-state">读取供应商运营统计…</p>:
   metrics.isError?<p className="setup-error" role="alert">{String(metrics.error)}</p>:
   !metrics.data?.length?<p className="empty-state">暂无供应商。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="供应商交付运营统计">
    <table><thead><tr><th scope="col">供应商</th><th scope="col">有效采购单</th>
     <th scope="col">订购 / 实收</th><th scope="col">质检不合格</th>
     <th scope="col">未关差异</th><th scope="col">未完索赔</th>
     <th scope="col">逾期 ASN</th></tr></thead><tbody>
     {metrics.data.map(p=><tr key={p.supplierId}>
      <td>{p.supplierName}</td><td>{p.orderCount}</td>
      <td>{p.orderedUnits} / {p.receivedUnits}</td><td>{p.rejectedUnits}</td>
      <td>{p.openDiscrepancies}</td><td>{p.openClaims}</td><td>{p.overdueAsns}</td>
     </tr>)}
    </tbody></table>
   </div>}
  {!orderId?<p className="setup-hint">
    在上方采购单列表打开一张采购单，记录人工短装/溢装证据、复核差异并创建索赔。</p>:<>
   <h3>人工记录采购单差异</h3>
   <form className="team-invite-form" onSubmit={report}>
    <label htmlFor="dispute-line">采购单行</label>
    <select id="dispute-line" value={lineId}
     onChange={e=>{setLineId(e.target.value);setAsnId('');setReportKey(crypto.randomUUID())}}>
     <option value="">选择采购明细</option>
     {lines.map(l=><option key={l.id} value={l.id}>
      {l.productSkuId.slice(0,8)} · 当前已收 {l.receivedQuantity} / {l.orderedQuantity}</option>)}
    </select>
    <label htmlFor="dispute-type">差异类型</label>
    <select id="dispute-type" value={kind}
     onChange={e=>{setKind(e.target.value as PurchaseVariance['kind']);
      setAsnId('');setReportKey(crypto.randomUUID())}}>
     <option value="PO_SHORTAGE">采购单当前未收足</option>
     <option value="ASN_SHORTAGE">供应商 ASN 申报货量尚未全部到仓</option>
     <option value="OVERAGE_UNPOSTED">仓库发现额外实物（未入库）</option>
    </select>
    {kind==='ASN_SHORTAGE'&&<>
     <label htmlFor="dispute-asn">已申报发货的 ASN</label>
     <select id="dispute-asn" value={asnId}
      onChange={e=>{setAsnId(e.target.value);setReportKey(crypto.randomUUID())}}>
      <option value="">选择来源 ASN</option>
      {eligibleAsns.map(a=><option key={a.id} value={a.id}>
       {a.trackingReference??a.id.slice(0,8)} · 申报 {a.quantity} / 实收 {a.receivedQuantity}
      </option>)}
     </select>
    </>}
    {kind==='OVERAGE_UNPOSTED'&&<>
     <label htmlFor="dispute-overage">发现的额外实物件数（未入库）</label>
     <input id="dispute-overage" type="number" min={1} max={1000000}
      value={excess} onChange={e=>{setExcess(Number(e.target.value));
       setReportKey(crypto.randomUUID())}}/>
    </>}
    <label htmlFor="dispute-reason">差异发现说明</label>
    <input id="dispute-reason" value={reason} minLength={5} maxLength={300}
     onChange={e=>{setReason(e.target.value);setReportKey(crypto.randomUUID())}}
     placeholder="记录清点时间、方式、供应商沟通信息"/>
    <button type="submit" className="primary-button" disabled={!canReport}>
     保存差异证据（不修改库存）</button>
   </form>
   <h3>采购差异复核清单</h3>
   <label htmlFor="dispute-variance-filter">差异记录范围</label>
   <select id="dispute-variance-filter" value={varianceFilter}
    onChange={e=>{setVarianceFilter(e.target.value as CaseFilter);
     setVarianceCursors([null])}}>
    <option value="ACTIONABLE">待复核及待关闭</option>
    <option value="ALL">全部差异历史</option>
   </select>
   {variances.isPending?<p className="empty-state">读取差异…</p>:
    variances.isError?<p className="setup-error" role="alert">{String(variances.error)}</p>:
    !variances.data?.items.length?<p className="empty-state">当前采购单没有人工登记的差异。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="差异复核清单">
     <table><thead><tr><th scope="col">来源</th><th scope="col">应有 / 观察</th>
      <th scope="col">差异</th><th scope="col">状态</th><th scope="col">操作</th>
     </tr></thead><tbody>
     {variances.data.items.map(v=><tr key={v.id}>
      <td>{varianceKind[v.kind]}
       <div className="secondary-cell">{v.reason}</div></td>
      <td>{v.expectedQuantity} / {v.observedQuantity}</td>
      <td>{v.varianceQuantity}</td><td>{varianceStates[v.state]}</td>
      <td><div className="sandbox-actions">
       {v.state==='OPEN'&&v.createdBy!==currentUserId&&
        <button type="button" className="primary-button"
         disabled={varianceAction.isPending}
         onClick={()=>{if(window.confirm('确认复核此人工差异观测？不自动认定供应商责任。'))
          varianceAction.mutate({id:v.id,action:'review'})}}>另一 Owner 复核</button>}
       {v.state==='REVIEWED'&&
        <button type="button" className="refresh-button"
         disabled={varianceAction.isPending}
         onClick={()=>{if(window.confirm('确认关闭差异记录？不代表供应商已赔偿。'))
          varianceAction.mutate({id:v.id,action:'close'})}}>关闭差异</button>}
       <button type="button" className="text-button"
        onClick={()=>{setEventCursors([null]);setViewEvent({id:v.id,type:'variances'})}}>审计记录</button>
      </div></td>
     </tr>)}</tbody></table>
    </div>}
   {!!variances.data&&<CursorPageControls label="采购差异复核清单"
    page={varianceCursors.length} hasMore={variances.data.hasMore}
    nextCursor={variances.data.nextCursor} busy={variances.isFetching}
    onPrev={()=>setVarianceCursors(v=>v.length>1?v.slice(0,-1):v)}
    onNext={v=>setVarianceCursors(h=>[...h,v])}/>}
   <h3>供应商索赔草稿</h3>
   <p className="setup-hint">可追溯索赔来源由服务器全量查询，排除所有已有索赔的来源，
    包括历史已取消记录；翻页后只能选择当前页真实可用的来源，防止部分列表误判。</p>
   {sources.isError&&<p className="setup-error" role="alert">
    无法加载可索赔来源：{String(sources.error)}</p>}
   <form className="team-invite-form" onSubmit={claim}>
    <label htmlFor="claim-source">可追溯索赔依据</label>
    <select id="claim-source" value={claimSource}
     onChange={e=>{setClaimSource(e.target.value);setClaimKey(crypto.randomUUID())}}>
     <option value="">选择已复核差异或已确认质检不合格</option>
     {sourceOptions.map(s=><option key={s.value} value={s.value}>{s.label}</option>)}
    </select>
    {!!sources.data&&<CursorPageControls label="可追溯索赔来源"
     page={sourceCursors.length} hasMore={sources.data.hasMore}
     nextCursor={sources.data.nextCursor} busy={sources.isFetching}
     onPrev={()=>{setClaimSource('');setSourceCursors(v=>v.length>1?v.slice(0,-1):v)}}
     onNext={v=>{setClaimSource('');setSourceCursors(h=>[...h,v])}}/>}
    <label htmlFor="claim-quantity">索赔件数</label>
    <input id="claim-quantity" type="number" min={1}
     max={chosen?.max??1} value={claimQty}
     onChange={e=>{setClaimQty(Number(e.target.value));setClaimKey(crypto.randomUUID())}}/>
    <label htmlFor="claim-remedy">希望的处理方式</label>
    <select id="claim-remedy" value={remedy}
     onChange={e=>{setRemedy(e.target.value as typeof remedy);setClaimKey(crypto.randomUUID())}}>
     <option value="REPLACEMENT">申请补发</option>
     <option value="CREDIT_REQUEST">申请信用折让（非实际退款）</option>
    </select>
    <label htmlFor="claim-reason">索赔说明</label>
    <input id="claim-reason" minLength={5} maxLength={300}
     value={claimReason} placeholder="描述证据、沟通和预期处理"
     onChange={e=>{setClaimReason(e.target.value);setClaimKey(crypto.randomUUID())}}/>
    <button type="submit" className="primary-button" disabled={!canClaim}>
     创建本地索赔草稿</button>
   </form>
   <h3>内部索赔审批与处置</h3>
   <label htmlFor="dispute-claim-filter">索赔记录范围</label>
   <select id="dispute-claim-filter" value={claimFilter}
    onChange={e=>{setClaimFilter(e.target.value as CaseFilter);
     setClaimCursors([null])}}>
    <option value="ACTIONABLE">草稿、待审批及待关闭</option>
    <option value="ALL">全部索赔历史</option>
   </select>
   {claims.isPending?<p className="empty-state">读取索赔…</p>:
    claims.isError?<p className="setup-error" role="alert">{String(claims.error)}</p>:
    !claims.data?.items.length?<p className="empty-state">当前采购单暂无索赔草稿。</p>:
    <div className="table-scroll" role="region" tabIndex={0} aria-label="供应商索赔流程">
     <table><thead><tr><th scope="col">来源与方案</th><th scope="col">数量</th>
      <th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
      {claims.data.items.map(c=><tr key={c.id}>
       <td>{c.varianceId?'数量差异':'质检不合格'} ·
        {c.remedy==='REPLACEMENT'?'申请补发':'申请信用折让'}
        <div className="secondary-cell">{c.reason}</div></td>
       <td>{c.requestedQuantity}</td><td>{claimStates[c.status]}</td>
       <td><div className="sandbox-actions">
        {c.status==='DRAFT'&&
         <button type="button" className="primary-button" disabled={claimAction.isPending}
          onClick={()=>claimAction.mutate({id:c.id,action:'submit'})}>内部提交</button>}
        {c.status==='SUBMITTED'&&c.createdBy!==currentUserId&&
         <button type="button" className="primary-button" disabled={claimAction.isPending}
          onClick={()=>{if(window.confirm('由第二位 Owner 内部审批？不自动发送供应商。'))
           claimAction.mutate({id:c.id,action:'approve'})}}>第二 Owner 审批</button>}
        {['DRAFT','SUBMITTED'].includes(c.status)&&
         <button type="button" className="text-button" disabled={claimAction.isPending}
          onClick={()=>claimAction.mutate({id:c.id,action:'cancel'})}>取消</button>}
        {c.status==='APPROVED'&&<div className="team-invite-form">
         <label htmlFor={'claim-close-'+c.id}>内部关闭说明</label>
         <input id={'claim-close-'+c.id} maxLength={300}
          value={closureNotes[c.id]??''} placeholder="记录处理结果，不能冒充已退款"
          onChange={e=>setClosureNotes(s=>({...s,[c.id]:e.target.value}))}/>
         <button type="button" className="refresh-button" disabled={claimAction.isPending||
          (closureNotes[c.id]??'').trim().length<5}
          onClick={()=>{if(window.confirm('仅关闭内部索赔记录，不能代表供应商承认或款项到账。'))
           claimAction.mutate({id:c.id,action:'close',note:closureNotes[c.id].trim()})}}>
          人工关闭</button>
        </div>}
        <button type="button" className="text-button"
         onClick={()=>{setEventCursors([null]);setViewEvent({id:c.id,type:'claims'})}}>审计记录</button>
       </div></td>
      </tr>)}</tbody></table>
    </div>}
   {!!claims.data&&<CursorPageControls label="供应商索赔审批记录"
    page={claimCursors.length} hasMore={claims.data.hasMore}
    nextCursor={claims.data.nextCursor} busy={claims.isFetching}
    onPrev={()=>setClaimCursors(v=>v.length>1?v.slice(0,-1):v)}
    onNext={v=>setClaimCursors(h=>[...h,v])}/>}
   {!!viewEvent&&<div aria-label="供应商差异与索赔审计">
    <h3>不可覆盖的 {viewEvent.type==='claims'?'索赔':'差异'} 审计记录</h3>
    {events.isPending?<p className="empty-state">读取审计…</p>:
     events.isError?<p className="setup-error" role="alert">{String(events.error)}</p>:
     !events.data?.items.length?<p className="empty-state">尚无审计。</p>:
     <div className="table-scroll" role="region" tabIndex={0}>
      <table><thead><tr><th scope="col">时间</th><th scope="col">事件</th>
       <th scope="col">操作者</th><th scope="col">说明</th></tr></thead><tbody>
       {events.data.items.map(e=><tr key={e.id}>
        <td>{new Date(e.createdAt).toLocaleString('zh-CN')}</td>
        <td>{e.eventType}</td><td>{e.actorUserId.slice(0,8)}</td>
        <td>{e.note??'—'}</td>
       </tr>)}
      </tbody></table>
     </div>}
    {!!events.data&&<CursorPageControls label="差异与索赔审计历史"
     page={eventCursors.length} hasMore={events.data.hasMore}
     nextCursor={events.data.nextCursor} busy={events.isFetching}
     onPrev={()=>setEventCursors(v=>v.length>1?v.slice(0,-1):v)}
     onNext={v=>setEventCursors(h=>[...h,v])}/>}
   </div>}
  </>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
 </section>
}
