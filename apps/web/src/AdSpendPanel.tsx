import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {getAdSpends,getAdSkuOptions,getAdSpendPreview,
 createAdSpend,assignAdSpend,withdrawAdSpend,getAdSpendAudit} from './lib/ad-spend'

const statuses:Record<string,string>={
 NO_SKU_ALLOCATION:'尚未分配到内部 SKU',
 PARTIALLY_ALLOCATED:'尚有广告支出未归属',
 FULLY_ALLOCATED_UNVERIFIED:'人工分摊比例达到 100%（未验证 Ads API）'
}
export default function AdSpendPanel({tenantId,marketplaceId,currency}:{
 tenantId:string;marketplaceId:string;currency:string
}){
 const cache=useQueryClient()
 const base=['finance','ad-spend',tenantId,marketplaceId]
 const [sourceKey,setSourceKey]=useState('')
 const [campaign,setCampaign]=useState('')
 const [date,setDate]=useState('')
 const [spend,setSpend]=useState('')
 const [selected,setSelected]=useState('')
 const [skuId,setSkuId]=useState('')
 const [basis,setBasis]=useState('')
 const [reference,setReference]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const [notice,setNotice]=useState(''),[failure,setFailure]=useState('')
 const sources=useQuery({queryKey:[...base,'sources'],
  queryFn:()=>getAdSpends(marketplaceId),retry:false})
 const skus=useQuery({queryKey:[...base,'skus'],
  queryFn:()=>getAdSkuOptions(marketplaceId),retry:false})
 const details=useQuery({queryKey:[...base,selected,'preview'],
  queryFn:()=>getAdSpendPreview(marketplaceId,selected),
  enabled:!!selected,retry:false})
 const audit=useQuery({queryKey:[...base,selected,'audit'],
  queryFn:()=>getAdSpendAudit(marketplaceId,selected),
  enabled:!!selected&&showAudit,retry:false})
 function refresh(){void cache.invalidateQueries({queryKey:base})}
 const create=useMutation({mutationFn:()=>createAdSpend(marketplaceId,{
  sourceKey,campaignReference:campaign,businessDate:date,
  spendAmount:spend,currency}),
  onSuccess:s=>{setSourceKey('');setCampaign('');setDate('');setSpend('');
   setSelected(s.id);setNotice('已录入人工未核验广告费用参考，不代表官方 Ads API 数据');
   setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 const allocate=useMutation({mutationFn:()=>assignAdSpend(marketplaceId,selected,{
  skuId,basisPoints:Number(basis),evidenceReference:reference}),
  onSuccess:()=>{setNotice('SKU 广告费用分摊已保存并审计');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const remove=useMutation({mutationFn:(sku:string)=>
  withdrawAdSpend(marketplaceId,selected,sku),
  onSuccess:()=>{setNotice('SKU 分摊已撤销，审计仍保留');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const canCreate=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(sourceKey)&&
  /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$/.test(campaign)&&
  /^\d{4}-\d{2}-\d{2}$/.test(date)&&
  /^[0-9]{1,14}(?:\.[0-9]{1,6})?$/.test(spend)
 const validShare=!!skuId&&Number.isInteger(Number(basis))&&Number(basis)>0&&
  Number(basis)<=10000&&
  /^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/.test(reference)
 const submit=(e:FormEvent)=>{e.preventDefault();if(canCreate)create.mutate()}
 const save=(e:FormEvent)=>{e.preventDefault();if(validShare)allocate.mutate()}
 const chosen=sources.data?.find(x=>x.id===selected)
 return <section className="panel full-panel" aria-label="广告费用原币与内部SKU分摊">
  <div className="panel-heading"><div>
   <h2>广告支出 · 本地参考来源与 SKU 分摊</h2>
   <p>支持人工参考的广告支出、广告活动、业务日与内部 SKU 分摊。
      此功能不读取 Amazon Ads API，不能直接作为已验证利润费用。</p></div>
   <button className="refresh-button" type="button" onClick={refresh}>刷新</button>
  </div>
  <form className="team-invite-form" onSubmit={submit}>
   <label htmlFor="ads-source-key">人工广告来源参考号（站点内唯一）</label>
   <input id="ads-source-key" required minLength={8} maxLength={160}
    pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}"
    value={sourceKey} onChange={e=>setSourceKey(e.target.value)}
    placeholder="ADS-MANUAL-20261031-01"/>
   <label htmlFor="ads-campaign">广告活动参考 ID</label>
   <input id="ads-campaign" required maxLength={160}
    pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}" value={campaign}
    onChange={e=>setCampaign(e.target.value)} placeholder="CAMPAIGN-001"/>
   <label htmlFor="ads-day">支出业务日期（站点当地日期）</label>
   <input id="ads-day" type="date" required value={date}
    onChange={e=>setDate(e.target.value)}/>
   <label htmlFor="ads-cost">原币支出金额（{currency}，非负）</label>
   <input id="ads-cost" inputMode="decimal" required
    pattern="[0-9]{1,14}(\.[0-9]{1,6})?"
    value={spend} onChange={e=>setSpend(e.target.value)} placeholder="25.00"/>
   <button className="primary-button" type="submit"
    disabled={!canCreate||create.isPending}>保存人工广告支出参考</button>
  </form>
  {sources.isPending?<p>正在加载广告费用参考…</p>:
   sources.isError?<p className="setup-error" role="alert">{String(sources.error)}</p>:
   !sources.data?.length?<p className="empty-state">还没有手工广告费用来源。</p>:
   <label className="shop-filter" htmlFor="ads-selected">
    <span>选择广告费用来源</span>
    <select id="ads-selected" value={selected}
     onChange={e=>{setSelected(e.target.value);setShowAudit(false)}}>
     <option value="">选择要分配的支出</option>
     {sources.data.map(s=><option key={s.id} value={s.id}>
      {s.businessDate} · {s.campaignReference} · {s.currency} {s.spendAmount}</option>)}
    </select>
   </label>}
  {chosen&&details.isPending?<p>正在核对 SKU 分摊比例…</p>:
   details.isError?<p role="alert" className="setup-error">{String(details.error)}</p>:
   details.data&&<>
    <p role="status"><strong>{statuses[details.data.summary.status]}</strong></p>
    <p>人工来源广告支出：{details.data.source.currency} {details.data.summary.spendAmount}；
     已分摊：{details.data.summary.attributedSpend}；
     未分摊：{details.data.summary.unallocatedSpend}。
     目前分摊比例：{(details.data.summary.totalBasisPoints/100).toFixed(2)}%。</p>
    {!!details.data.assignments.length&&
     <div className="table-scroll" tabIndex={0} role="region" aria-label="广告费用SKU分摊">
      <table><thead><tr><th>内部 SKU</th><th>分摊比例</th><th>分摊金额参考</th>
       <th>人工审核依据</th><th>操作</th></tr></thead><tbody>
       {details.data.assignments.map(s=><tr key={s.skuId}>
        <td>{s.internalSku}</td><td>{(s.basisPoints/100).toFixed(2)}%</td>
        <td>{currency} {s.approximateSpend}</td><td>{s.evidenceReference}</td>
        <td><button type="button" className="text-button" disabled={remove.isPending}
         onClick={()=>remove.mutate(s.skuId)}>撤销分摊</button></td>
       </tr>)}</tbody></table></div>}
    <form className="team-invite-form" onSubmit={save}>
     <label htmlFor="ads-sku">选择内部商品 SKU（最多展示前 200 项）</label>
     <select id="ads-sku" value={skuId} onChange={e=>{
      const id=e.target.value;setSkuId(id)
      const found=details.data?.assignments.find(v=>v.skuId===id)
      setBasis(found?String(found.basisPoints):'')
      setReference(found?.evidenceReference??'')
     }}>
      <option value="">选择 SKU</option>
      {(skus.data??[]).map(s=><option key={s.id} value={s.id}>{s.internalSku}</option>)}
     </select>
     <label htmlFor="ads-share">分摊基点（10000 = 100%；如 2500 = 25%）</label>
     <input id="ads-share" type="number" min={1} max={10000} step={1} required
      value={basis} onChange={e=>setBasis(e.target.value)}/>
     <label htmlFor="ads-evidence">人工分摊依据编号</label>
     <input id="ads-evidence" required minLength={8} maxLength={160}
      pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}" value={reference}
      onChange={e=>setReference(e.target.value)} placeholder="ADS-ATTRIBUTION-001"/>
     <button className="primary-button" type="submit"
      disabled={!validShare||allocate.isPending}>审核 SKU 广告费用分摊</button>
    </form>
    <button className="text-button" type="button"
     onClick={()=>setShowAudit(v=>!v)}>{showAudit?'收起':'查看'}分摊审计</button>
    {showAudit&&(audit.isPending?<p>正在读取审计…</p>:
      audit.isError?<p role="alert" className="setup-error">{String(audit.error)}</p>:
      <div className="table-scroll" tabIndex={0} role="region" aria-label="广告分摊审计">
       <table><thead><tr><th>SKU ID</th><th>操作</th><th>比例</th>
        <th>依据</th><th>时间</th></tr></thead><tbody>
        {(audit.data??[]).map((a,i)=><tr key={i}><td>{a.skuId}</td>
         <td>{a.action}</td><td>{(a.basisPoints/100).toFixed(2)}%</td>
         <td>{a.evidenceReference}</td><td>{a.occurredAt}</td>
        </tr>)}</tbody></table></div>)}
   </>}
  {notice&&<p className="panel-caption" role="status">{notice}</p>}
  {failure&&<p role="alert" className="setup-error">{failure}</p>}
  <p className="setup-hint">广告支出原始来源与 SKU 分配均为人工未核验。分摊比例为本地运营判断，
   不等于 Amazon Ads 的真实点击、归因订单、ROAS 或银行实际扣费。</p>
 </section>
}
