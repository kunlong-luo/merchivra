import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getCapability,getProfiles,addProfile,getReports,getReport,addReport,
 decideReport,getReviewed,type AdsOrigin,type AdsCostInput} from './lib/ads-reports'

type Row=AdsCostInput&{key:string}
const createRow=():Row=>({key:crypto.randomUUID(),businessDate:'',
 campaignReference:'',spendAmount:''})
const labels:Record<string,string>={
 STAGED:'待独立复核',REVIEWED_UNVERIFIED:'已人工复核（未授权核验）',
 REJECTED:'已拒绝（不计入日报）'
}
const refs=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/
const campaign=/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$/
const money=/^[0-9]{1,14}(?:\.[0-9]{1,6})?$/

export default function AdsReportsPanel({tenantId,marketplaceId,currency}:{
 tenantId:string;marketplaceId:string;currency:string
}){
 const client=useQueryClient(),scope=['ads-reports',tenantId,marketplaceId]
 const [profileRef,setProfileRef]=useState(''),[origin,setOrigin]=useState<AdsOrigin>('MOCK')
 const [profileId,setProfileId]=useState(''),[reportRef,setReportRef]=useState('')
 const [start,setStart]=useState(''),[end,setEnd]=useState('')
 const [rows,setRows]=useState<Row[]>(()=>[createRow()])
 const [selected,setSelected]=useState(''),[note,setNote]=useState('')
 const [message,setMessage]=useState(''),[error,setError]=useState('')
 const capability=useQuery({queryKey:[...scope,'authorization'],
  queryFn:()=>getCapability(marketplaceId),retry:false})
 const profiles=useQuery({queryKey:[...scope,'profiles'],
  queryFn:()=>getProfiles(marketplaceId),retry:false})
 const reports=useQuery({queryKey:[...scope,'reports'],
  queryFn:()=>getReports(marketplaceId),retry:false})
 const detail=useQuery({queryKey:[...scope,'report',selected],
  queryFn:()=>getReport(marketplaceId,selected),enabled:!!selected,retry:false})
 const reviewed=useQuery({queryKey:[...scope,'reviewed'],
  queryFn:()=>getReviewed(marketplaceId),retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:scope})
 const source=profiles.data?.find(x=>x.id===profileId)
 const validRows=rows.length>=1&&rows.length<=200&&rows.every(x=>
  /^\d{4}-\d{2}-\d{2}$/.test(x.businessDate)&&
  x.businessDate>=start&&x.businessDate<=end&&campaign.test(x.campaignReference)&&
  money.test(x.spendAmount))&&
  new Set(rows.map(x=>x.businessDate+'|'+x.campaignReference)).size===rows.length
 const validWindow=!!start&&!!end&&end>=start&&
   (Date.parse(end+'T00:00:00Z')-Date.parse(start+'T00:00:00Z'))/86400000<=30
 const createProfile=useMutation({
  mutationFn:()=>addProfile(marketplaceId,profileRef,origin),
  onSuccess:p=>{setProfileId(p.id);setProfileRef('');setMessage('已添加未授权的 Ads Profile 引用');
   setError('');refresh()},
  onError:(e:Error)=>{setMessage('');setError(e.message)}
 })
 const importJob=useMutation({
  mutationFn:()=>{
   if(!source)throw Error('请选择当前站点的 Ads Profile')
   return addReport(marketplaceId,{
    profileId:source.id,reportReference:reportRef,origin:source.origin,
    fromDate:start,throughDate:end,currency,
    rows:rows.map(({businessDate,campaignReference,spendAmount})=>
     ({businessDate,campaignReference,spendAmount}))
   })
  },
  onSuccess:d=>{setSelected(d.batch.id);setReportRef('');setRows([createRow()])
   setMessage('日报已保存为不可变离线证据，待另一名 Owner 审核；没有调用 Ads API')
   setError('');refresh()},
  onError:(e:Error)=>{setMessage('');setError(e.message)}
 })
 const review=useMutation({
  mutationFn:(decision:'APPROVE'|'REJECT')=>decideReport(marketplaceId,selected,decision,note),
  onSuccess:result=>{setMessage(result.batch.state==='REVIEWED_UNVERIFIED'
   ?'复核已完成（未验证 Ads API，不进行利润记账）':'报告已拒绝')
   setNote('');setError('');refresh()},
  onError:(e:Error)=>{setMessage('');setError(e.message)}
 })
 const update=(key:string,patch:Partial<Row>)=>setRows(old=>old.map(
  r=>r.key===key?{...r,...patch}:r))
 const profileSubmit=(e:FormEvent)=>{e.preventDefault();if(profileRef.length>=3)createProfile.mutate()}
 const reportSubmit=(e:FormEvent)=>{e.preventDefault();if(source&&validWindow&&validRows)importJob.mutate()}
 return <section className="panel full-panel" aria-label="Amazon Ads 离线来源与独立审核">
  <div className="panel-heading"><div><h2>Amazon Ads · 独立授权准备与离线报告</h2>
   <p>Ads OAuth 与 Seller 授权分开。本阶段只有 MOCK/手工未核验日报，不访问广告平台；不进入利润与真实广告费用。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <p className="setup-hint">Ads 授权状态：{capability.isError?'读取失败':
   capability.isPending?'读取中':'未启用（待核心开发完成后本地授权）'} ·
   不使用 Seller OAuth；无 Ads Token、无真实费用回执。</p>
  <details className="core-tools">
   <summary><strong>登记 Ads Profile（仅本地引用）</strong><span>必须归属当前 Marketplace</span></summary>
   <form className="team-invite-form" onSubmit={profileSubmit}>
    <label htmlFor="ads-v66-profile">Profile 参考编号（不可作为授权凭证）</label>
    <input id="ads-v66-profile" minLength={3} maxLength={80} required
     value={profileRef} onChange={e=>setProfileRef(e.target.value)}
     placeholder="MOCK-ADS-PROFILE-US-1"/>
    <label htmlFor="ads-v66-origin">来源分类</label>
    <select id="ads-v66-origin" value={origin}
     onChange={e=>setOrigin(e.target.value as AdsOrigin)}>
     <option value="MOCK">MOCK 模拟</option>
     <option value="MANUAL_UNVERIFIED">手工录入，未核验</option>
    </select>
    <button className="primary-button" type="submit"
     disabled={createProfile.isPending||!profileRef.trim()}>登记本地 Profile</button>
   </form>
  </details>
  <section className="core-tools">
   <h3>导入 Campaign 每日支出（离线行明细）</h3>
   <p className="setup-hint">按原币汇总每行支出。每批最多 200 行、日期跨度不超过 31 天；不能重复提交同 Profile/日期/Campaign 已审核费用。</p>
   <form className="team-invite-form" onSubmit={reportSubmit}>
    <label htmlFor="ads-v66-select">Ads Profile</label>
    <select id="ads-v66-select" value={profileId} required
     onChange={e=>setProfileId(e.target.value)}>
     <option value="">选择已登记 Profile</option>
     {(profiles.data??[]).map(p=><option key={p.id} value={p.id}>
      {p.profileReference} · {p.origin} · 未授权</option>)}
    </select>
    <label htmlFor="ads-v66-ref">不可变报告引用号</label>
    <input id="ads-v66-ref" value={reportRef} minLength={8} maxLength={160}
     required onChange={e=>setReportRef(e.target.value)} placeholder="MOCK-ADS-REPORT-2026-001"/>
    <label htmlFor="ads-v66-from">起始业务日</label>
    <input id="ads-v66-from" type="date" required value={start}
     onChange={e=>setStart(e.target.value)}/>
    <label htmlFor="ads-v66-to">结束业务日（包含该日）</label>
    <input id="ads-v66-to" type="date" required value={end}
     onChange={e=>setEnd(e.target.value)}/>
    {rows.map((r,i)=><div className="core-tools" key={r.key}>
     <h3>费用行 {i+1}</h3>
     <label htmlFor={'ads-v66-day-'+r.key}>业务日</label>
     <input id={'ads-v66-day-'+r.key} type="date" required value={r.businessDate}
      onChange={e=>update(r.key,{businessDate:e.target.value})}/>
     <label htmlFor={'ads-v66-campaign-'+r.key}>Campaign 参考编号</label>
     <input id={'ads-v66-campaign-'+r.key} required maxLength={160} value={r.campaignReference}
      onChange={e=>update(r.key,{campaignReference:e.target.value})}/>
     <label htmlFor={'ads-v66-cost-'+r.key}>支出（{currency}，非负原币）</label>
     <input id={'ads-v66-cost-'+r.key} required inputMode="decimal" value={r.spendAmount}
      onChange={e=>update(r.key,{spendAmount:e.target.value})}/>
     {rows.length>1&&<button type="button" className="text-button"
      onClick={()=>setRows(old=>old.filter(x=>x.key!==r.key))}>删除费用行</button>}
    </div>)}
    <div className="sandbox-actions">
     <button type="button" className="refresh-button" disabled={rows.length>=200}
      onClick={()=>setRows(old=>[...old,createRow()])}>添加费用行</button>
     <button type="submit" className="primary-button"
      disabled={importJob.isPending||!source||!refs.test(reportRef)||!validWindow||!validRows}>
      导入未核验日报</button>
    </div>
   </form>
   {profiles.isError&&<p role="alert" className="setup-error">{String(profiles.error)}</p>}
  </section>
  <section className="core-tools">
   <h3>批次与双 Owner 来源复核</h3>
   {reports.isPending?<p className="empty-state">读取报告…</p>:
    reports.isError?<p role="alert" className="setup-error">{String(reports.error)}</p>:
    !reports.data?.length?<p className="empty-state">尚无 Ads 报告。</p>:
    <label className="shop-filter" htmlFor="ads-v66-reports"><span>选择报告</span>
     <select id="ads-v66-reports" value={selected} onChange={e=>{setSelected(e.target.value);setNote('')}}>
      <option value="">选择报告批次</option>
      {reports.data.map(r=><option key={r.id} value={r.id}>
       {r.reportReference} · {labels[r.state]??r.state}</option>)}
     </select></label>}
   {selected&&(detail.isPending?<p className="empty-state">读取明细…</p>:
    detail.isError?<p role="alert" className="setup-error">{String(detail.error)}</p>:
    detail.data&&<div>
     <p><strong>{labels[detail.data.batch.state]}</strong> · {detail.data.batch.origin} · {detail.data.batch.currency} {detail.data.reportTotal} · {detail.data.rows.length} 行</p>
     <div className="table-scroll" tabIndex={0} role="region" aria-label="Ads 日报行证据">
      <table><thead><tr>{['日期','Campaign','金额','币种'].map(h=><th key={h}>{h}</th>)}</tr></thead>
      <tbody>{detail.data.rows.map(r=><tr key={r.rowNumber}>
       <td>{r.businessDate}</td><td>{r.campaignReference}</td>
       <td>{r.spendAmount}</td><td>{r.currency}</td></tr>)}</tbody></table></div>
     {detail.data.batch.state==='STAGED'&&<form className="team-invite-form"
      onSubmit={e=>{e.preventDefault()}}>
      <h3>另一名 Owner 独立审核</h3>
      <p className="setup-hint">导入者不能审核自己上传的费用。批准仍是人工未核验，不会变成 LIVE 或计入利润。</p>
      <label htmlFor="ads-v66-review-note">审核依据说明（至少 10 字）</label>
      <textarea id="ads-v66-review-note" minLength={10} maxLength={500} rows={3}
       value={note} onChange={e=>setNote(e.target.value)}/>
      <div className="sandbox-actions">
       <button type="button" className="primary-button"
        disabled={review.isPending||note.trim().length<10||note.length>500}
        onClick={()=>review.mutate('APPROVE')}>人工审核通过（仍未核验）</button>
       <button type="button" className="refresh-button"
        disabled={review.isPending||note.trim().length<10||note.length>500}
        onClick={()=>review.mutate('REJECT')}>拒绝重复或无效报告</button>
      </div>
     </form>}
     {detail.data.batch.reviewNote&&<p className="setup-hint">审核记录：{detail.data.batch.reviewNote}</p>}
    </div>)}
  </section>
  <section className="core-tools" aria-label="已人工复核广告费用参照表">
   <h3>已人工复核的 Ads 日费用参照（非平台核验）</h3>
   {reviewed.isPending?<p className="empty-state">读取费用参照…</p>:
    reviewed.isError?<p className="setup-error" role="alert">{String(reviewed.error)}</p>:
    !reviewed.data?.length?<p className="empty-state">没有经第二名 Owner 审核的离线日报行。</p>:
    <div className="table-scroll" tabIndex={0} role="region" aria-label="去重后的人工广告费用参照">
     <table><thead><tr>{['日期','Profile','Campaign','金额','来源报告'].map(h=>
      <th key={h}>{h}</th>)}</tr></thead><tbody>
      {reviewed.data.map((x,i)=><tr key={x.profileId+'|'+x.businessDate+'|'+x.campaignReference+'|'+i}>
       <td>{x.businessDate}</td><td>{x.profileReference}</td><td>{x.campaignReference}</td>
       <td>{x.currency} {x.spendAmount}</td><td>{x.reportReference}</td></tr>)}
     </tbody></table></div>}
   <p className="setup-hint">此表与手工广告费用参考、Finances 平台费用和订单利润独立，不执行跨来源相加或自动 SKU 归因。</p>
  </section>
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
 </section>
}
