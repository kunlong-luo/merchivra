import {useEffect,useMemo,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {deactivateClassificationRule,getClassificationAudit,getClassificationPreview,
 getClassificationRules,upsertClassificationRule,
 type ClassificationCategory} from './lib/finance-classification'
import type {FinanceBreakdown} from './lib/finance'

const categoryNames:Record<ClassificationCategory,string>={
 PRODUCT_SALES:'商品销售',PRODUCT_REFUND:'商品退款',
 AMAZON_FEE:'平台费用',FBA_FEE:'FBA 费用',
 ADJUSTMENT:'其他调整',UNCLASSIFIED:'暂未分类'
}
const states:Record<string,string>={
 NO_BREAKDOWNS:'来源明细缺失',
 MISSING_AMOUNT:'存在未取得金额',
 CURRENCY_MISMATCH:'出现不同原币',
 UNMAPPED_LEAF:'存在未人工分类的末级明细',
 AMOUNT_MISMATCH:'末级总和与原交易金额不同',
 RECONCILED_UNVERIFIED:'金额已对平，分类仍为待复核',
 MANUAL_NOT_ELIGIBLE:'人工未核验来源不参与正式财务分类'
}
export default function FinanceClassificationPanel({tenantId,marketplaceId,
 eventId,origin,rawBreakdowns}:{
 tenantId:string;marketplaceId:string;eventId:string;origin:string;
 rawBreakdowns:FinanceBreakdown[]|undefined
}){
 const [sourceType,setSourceType]=useState('')
 const [kind,setKind]=useState<ClassificationCategory>('UNCLASSIFIED')
 const [notice,setNotice]=useState(''),[error,setError]=useState('')
 const [auditRuleId,setAuditRuleId]=useState('')
 const cache=useQueryClient()
 const prefix=['finance','classification',tenantId,marketplaceId]
 const ruleKey=[...prefix,'rules']
 const previewKey=[...prefix,'preview',eventId]
 const rules=useQuery({queryKey:ruleKey,queryFn:()=>getClassificationRules(marketplaceId),
  enabled:origin==='LIVE',retry:false})
 const preview=useQuery({queryKey:previewKey,
  queryFn:()=>getClassificationPreview(marketplaceId,eventId),
  enabled:origin==='LIVE',retry:false})
 const audit=useQuery({queryKey:[...prefix,'audit',auditRuleId],
  queryFn:()=>getClassificationAudit(marketplaceId,auditRuleId),
  enabled:!!auditRuleId,retry:false})
 const candidates=useMemo(()=>Array.from(new Set(
  (rawBreakdowns??[]).filter(r=>r.leaf).map(r=>r.sourceType))).sort(),
  [rawBreakdowns])
 useEffect(()=>{setSourceType('');setKind('UNCLASSIFIED');setAuditRuleId('')},
  [marketplaceId,eventId])
 const refresh=()=>{
  void cache.invalidateQueries({queryKey:prefix})
 }
 const save=useMutation({mutationFn:()=>upsertClassificationRule(
  marketplaceId,sourceType,kind),onSuccess:r=>{
   setNotice('本地规则已记录并审计：'+r.sourceType+' → '+categoryNames[r.category])
   setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 const remove=useMutation({mutationFn:(id:string)=>
  deactivateClassificationRule(marketplaceId,id),onSuccess:()=>{
   setNotice('本地规则已停用并记录审计');setError('');refresh()},
  onError:(e:Error)=>setError(e.message)})
 if(origin!=='LIVE')return null
 const result=preview.data?.analysis
 return <section className="panel full-panel" aria-label="Finances 分类审阅">
  <div className="panel-heading"><div><h3>财务分类规则与金额核对</h3>
   <p>规则仅用于本地待复核预览，不修改 Amazon 交易、收入凭证或会计账簿。
    只核对末级明细，不能把父子层级重复求和。</p></div>
   <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  {preview.isPending?<p className="empty-state">正在核对来源明细与分类规则…</p>:
   preview.isError?<p className="setup-error" role="alert">{String(preview.error)}</p>:
   <>
    <p role="status">分类覆盖情况：<strong>{states[preview.data?.status??'']??
      preview.data?.status}</strong></p>
    {result&&<p className="setup-hint">
     源交易总额：{result.currency} {result.sourceTotal}；
     末级合计：{result.leafTotal===null?'未能核对':result.currency+' '+result.leafTotal}；
     已分类 {result.mappedCount}/{result.leafCount} 条。
    </p>}
    {!!result?.provisionalTotals.length&&<div className="table-scroll"
      role="region" tabIndex={0} aria-label="仅供复核的按类别原币合计">
     <table><thead><tr><th>暂定业务类别</th><th>带符号金额</th></tr></thead>
      <tbody>{result.provisionalTotals.map(v=><tr key={v.category}>
       <td>{categoryNames[v.category as ClassificationCategory]??v.category}</td>
       <td>{v.currency} {v.signedAmount}</td></tr>)}</tbody></table>
     </div>}
    {result?.coverage!=='RECONCILED_UNVERIFIED'&&<p className="setup-hint">
      未满足全部核对条件，不展示暂定分类金额；这不代表费用为零。
     </p>}
   </>}
  <div className="core-tools">
   <h3>人工确定来源类型的本地分类</h3>
   <p className="setup-hint">只从当前事件已取得的末级来源类型中选择，不根据类型名称自动猜测。
      规则作用于该站点同名来源类型的历史和未来预览。</p>
   {candidates.length===0?<p>当前事件没有可用的末级明细类型。</p>:
    <div className="team-invite-form">
     <label htmlFor="fin-source-rule">末级来源类型</label>
     <select id="fin-source-rule" value={sourceType}
      onChange={e=>setSourceType(e.target.value)}>
      <option value="">选择来源明细类型</option>
      {candidates.map(s=><option key={s} value={s}>{s}</option>)}
     </select>
     <label htmlFor="fin-rule-category">本地人工分类</label>
     <select id="fin-rule-category" value={kind}
      onChange={e=>setKind(e.target.value as ClassificationCategory)}>
      {Object.entries(categoryNames).map(([code,label])=>
       <option key={code} value={code}>{label}</option>)}
     </select>
     <button type="button" className="primary-button"
      disabled={!sourceType||kind==='UNCLASSIFIED'||save.isPending}
      onClick={()=>save.mutate()}>审核并保存本地分类</button>
    </div>}
   {rules.isError?<p className="setup-error" role="alert">{String(rules.error)}</p>:
    !!rules.data?.length&&<div className="table-scroll" role="region" tabIndex={0}
     aria-label="站点本地财务规则">
     <table><thead><tr><th>精确来源类型</th><th>分类</th>
       <th>状态 / 修订</th><th>操作</th></tr></thead><tbody>
      {rules.data.map(r=><tr key={r.id}>
       <td>{r.sourceType}</td><td>{categoryNames[r.category]??r.category}</td>
       <td>{r.state} · v{r.revision}</td><td>
        <button type="button" className="text-button"
         onClick={()=>setAuditRuleId(auditRuleId===r.id?'':r.id)}>
         {auditRuleId===r.id?'收起审计':'查看审计'}</button>
        {r.state==='ACTIVE'&&<button type="button" className="text-button"
         disabled={remove.isPending}
         onClick={()=>{if(window.confirm('停用当前站点这一条分类规则？'))remove.mutate(r.id)}}>
         停用</button>}</td>
      </tr>)}</tbody></table>
     </div>}
   {!!auditRuleId&&(audit.isPending?<p>读取审计记录…</p>:
    audit.isError?<p className="setup-error">{String(audit.error)}</p>:
    <div className="table-scroll" role="region" tabIndex={0}
      aria-label="分类规则修订审计">
     <table><thead><tr><th>版本</th><th>操作</th><th>变更前</th>
       <th>变更后</th><th>时间</th></tr></thead><tbody>
      {(audit.data??[]).map((e,i)=><tr key={i}><td>{e.revision}</td>
       <td>{e.action}</td><td>{e.previousCategory??'无'}</td>
       <td>{e.newCategory??'无'}</td><td>{e.occurredAt}</td></tr>)}
     </tbody></table></div>)}
  </div>
  {notice&&<p role="status" className="panel-caption">{notice}</p>}
  {error&&<p role="alert" className="setup-error">{error}</p>}
 </section>
}
