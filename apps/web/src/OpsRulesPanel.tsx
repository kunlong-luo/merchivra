import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {fetchCoreMarketplaces} from './lib/core'
import {fetchOperationRules,fetchOperationRuleAudit,putOperationRule} from './lib/ops-rules'
import type {OpsRule} from './lib/ops-rules'
import {ruleNames} from './lib/operations'
import {formatSourceTime} from './lib/core-view'

function RuleRow({rule,tenantId,marketplaceId}:{rule:OpsRule,tenantId:string,marketplaceId:string}){
 const client=useQueryClient()
 const [threshold,setThreshold]=useState(String(rule.lowStockThreshold??3))
 const [error,setError]=useState('')
 const action=useMutation({
  mutationFn:({enabled,number}:{enabled:boolean,number:number|null})=>
   putOperationRule(marketplaceId,rule,enabled,number),
  onSuccess:()=>{setError('');void client.invalidateQueries({queryKey:['operations',tenantId]})},
  onError:(e:Error)=>{setError(e.message)}
 })
 const save=(enabled:boolean)=>{
  const parsed=/^(0|[1-9]\d{0,4})$/.test(threshold)?Number(threshold):NaN
  if(rule.ruleCode==='LOW_STOCK'&&(!Number.isInteger(parsed)||parsed>10000)){
   setError('低库存阈值须为 0–10000 整数');return
  }
  action.mutate({enabled,number:rule.ruleCode==='LOW_STOCK'?parsed:null})
 }
 return <tr>
  <td className="strong-cell">{ruleNames[rule.ruleCode]}</td>
  <td><span className="status-chip chip-muted">{rule.enabled?'启用':'暂停生成'}</span></td>
  <td>{rule.ruleCode==='LOW_STOCK'?
   <label className="shop-filter">阈值 <input aria-label="低库存站点阈值" type="number"
    min={0} max={10000} step={1} inputMode="numeric" value={threshold}
    onChange={e=>setThreshold(e.target.value)}/></label>:
   <span className="panel-caption">—</span>}</td>
  <td className="small-cell">{rule.version===0?'部署默认值':'版本 '+rule.version}</td>
  <td><div className="ops-row-actions">
   <button type="button" className="text-button" disabled={action.isPending}
    onClick={()=>save(!rule.enabled)}>{rule.enabled?'暂停规则':'启用规则'}</button>
   {rule.ruleCode==='LOW_STOCK'&&<button type="button" className="text-button"
    disabled={action.isPending||!rule.enabled} onClick={()=>save(true)}>保存阈值</button>}
  </div>{error&&<span role="alert" className="setup-error">{error}</span>}</td>
 </tr>
}

export default function OpsRulesPanel({tenantId}:{tenantId:string}){
 const [chosen,setChosen]=useState('')
 const [showAudit,setShowAudit]=useState(false)
 const marketplaces=useQuery({queryKey:['ops-rule-marketplaces',tenantId],
  queryFn:fetchCoreMarketplaces,staleTime:30000})
 const marketplaceId=marketplaces.data?.some(m=>m.id===chosen)?chosen:
  marketplaces.data?.[0]?.id??''
 const rules=useQuery({queryKey:['operations',tenantId,'rules',marketplaceId],
  queryFn:()=>fetchOperationRules(marketplaceId),enabled:!!marketplaceId,staleTime:30000})
 const history=useQuery({queryKey:['operations',tenantId,'rule-audit',marketplaceId],
  queryFn:()=>fetchOperationRuleAudit(marketplaceId),enabled:!!marketplaceId&&showAudit,
  staleTime:30000})
 return <section className="panel" aria-label="按站点配置运营异常规则">
  <div className="panel-heading"><div><h2>站点级异常规则</h2>
   <p>按 Marketplace 控制六类提醒和 FBA 低库存阈值；禁用仅影响下一次巡检产生的待办，不修改业务数据。</p>
  </div><span className="status-chip chip-muted">仅本地建议规则</span></div>
  {marketplaces.isError?<div role="alert" className="setup-error">
   读取站点失败：{marketplaces.error instanceof Error?marketplaces.error.message:'未知错误'}</div>:
   marketplaces.isPending?<div className="empty-state">读取站点…</div>:
   !marketplaceId?<div className="empty-state">请先在核心工作台创建本地 Marketplace。</div>:
   <>
    <div className="ops-toolbar"><label className="shop-filter">Marketplace
     <select aria-label="选择异常规则站点" value={marketplaceId}
      onChange={e=>{setChosen(e.target.value);setShowAudit(false)}}>
      {marketplaces.data?.map(m=><option key={m.id} value={m.id}>
       {m.marketplaceId} · {m.region} · {m.connectionStatus}</option>)}
     </select></label>
     <button type="button" className="refresh-button" onClick={()=>setShowAudit(v=>!v)}>
      {showAudit?'收起规则审计':'查看规则审计'}</button>
    </div>
    {rules.isPending?<div className="empty-state">读取规则…</div>:
     rules.isError?<div role="alert" className="setup-error">
      读取规则失败：{rules.error instanceof Error?rules.error.message:'未知错误'}</div>:
     <div className="table-scroll" tabIndex={0} role="region" aria-label="站点异常规则表">
      <table><thead><tr>{['异常类型','配置状态','低库存阈值','版本','操作']
       .map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
       <tbody>{rules.data?.map(rule=><RuleRow
        key={marketplaceId+'-'+rule.ruleCode+'-'+rule.version} rule={rule}
        marketplaceId={marketplaceId} tenantId={tenantId}/>)}</tbody>
      </table>
     </div>}
    {showAudit&&(history.isPending?<div className="empty-state">读取变更审计…</div>:
     history.isError?<div className="setup-error" role="alert">读取审计失败</div>:
     !history.data?.length?<p className="panel-caption">尚未修改站点规则</p>:
     <div className="table-scroll" role="region" tabIndex={0} aria-label="规则变更审计">
      <table><thead><tr>{['时间','规则','旧状态','新状态','版本']
       .map(h=><th key={h} scope="col">{h}</th>)}</tr></thead>
       <tbody>{history.data.slice(0,30).map((a,i)=><tr key={a.createdAt+a.ruleCode+i}>
        <td>{formatSourceTime(a.createdAt)}</td><td>{ruleNames[a.ruleCode]}</td>
        <td>{a.oldEnabled===null?'初次配置':a.oldEnabled?'启用':'暂停'}</td>
        <td>{a.newEnabled?'启用':'暂停'}
        {a.newThreshold!==null?' / 阈值 '+a.newThreshold:''}</td>
        <td>{a.newVersion}</td></tr>)}</tbody>
      </table></div>)}
    <p className="panel-caption">默认所有规则启用；阈值默认为部署配置（当前 3）。修改采用版本号冲突保护，需要“立即巡检”后才会更新异常队列；不会发邮件、写 Amazon 或改库存。</p>
   </>}
 </section>
}
