import {useEffect,useMemo,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {MARKETPLACE_PRESETS,fetchLocalAccounts,fetchSetupAudit,createLocalAccount,addLocalMarketplace} from './lib/setup'
export default function SellerSetup({tenantId}:{tenantId:string}){
 const client=useQueryClient()
 const [name,setName]=useState(''),[region,setRegion]=useState<'NA'|'EU'|'FE'>('NA')
 const [accountId,setAccountId]=useState(''),[marketplaceId,setMarketplaceId]=useState('')
 const [requestKey,setRequestKey]=useState(crypto.randomUUID()),[feedback,setFeedback]=useState(''),[failure,setFailure]=useState('')
 const accounts=useQuery({queryKey:['setup',tenantId,'accounts'],queryFn:fetchLocalAccounts,staleTime:15000})
 const audit=useQuery({queryKey:['setup',tenantId,'audit'],queryFn:fetchSetupAudit,staleTime:15000})
 const local=useMemo(()=>accounts.data?.filter(a=>a.connectionStatus==='UNCONFIGURED')??[],[accounts.data])
 useEffect(()=>{if(!local.some(a=>a.id===accountId))setAccountId(local[0]?.id??'')},[local,accountId])
 const selected=local.find(a=>a.id===accountId),choices=MARKETPLACE_PRESETS[selected?.region??'NA']
 useEffect(()=>{if(!choices.some(m=>m.id===marketplaceId))setMarketplaceId(choices[0]?.id??'')},[choices,marketplaceId])
 function refresh(){void client.invalidateQueries({queryKey:['setup',tenantId]});void client.invalidateQueries({queryKey:['core','marketplaces']})}
 const create=useMutation({mutationFn:()=>createLocalAccount({displayName:name,region},requestKey),
  onSuccess:r=>{setFeedback(r.displayName+' 已创建（尚未连接 Amazon）');setFailure('');setAccountId(r.id);setName('');setRequestKey(crypto.randomUUID());refresh()},onError:(e:Error)=>setFailure(e.message)})
 const add=useMutation({mutationFn:()=>addLocalMarketplace(accountId,marketplaceId),onSuccess:r=>{setFeedback(r.marketplaceId+' 已添加（仅本地占位配置）');setFailure('');refresh()},onError:(e:Error)=>setFailure(e.message)})
 return <section className="panel setup-panel"><div className="panel-heading"><div><h2>本地 Seller Account 配置</h2><p>仅创建未连接的店铺占位信息；不输入 Amazon Seller ID、Token 或 SP-API 密钥。</p></div><span className="status-chip chip-amber">未连接</span></div>
 <div className="setup-forms">
 <form className="setup-form" aria-busy={create.isPending} onSubmit={e=>{e.preventDefault();create.mutate()}}><strong>创建店铺占位记录</strong>
 <label htmlFor="local-shop-name">店铺名称</label><input id="local-shop-name" value={name} onChange={e=>setName(e.target.value.trim())} required minLength={2} maxLength={100} placeholder="例如：测试店铺 A" autoComplete="off"/>
 <label htmlFor="local-shop-region">区域</label><select id="local-shop-region" value={region} onChange={e=>setRegion(e.target.value as 'NA'|'EU'|'FE')}><option value="NA">北美 NA</option><option value="EU">欧洲 EU</option><option value="FE">远东 FE</option></select>
 <button type="submit" className="primary-button" disabled={create.isPending||name.length<2}>{create.isPending?'保存中…':'创建本地店铺'}</button></form>
 <form className="setup-form" aria-busy={add.isPending} onSubmit={e=>{e.preventDefault();add.mutate()}}><strong>添加 Marketplace 配置</strong>
 {accounts.isPending?<p className="setup-hint">加载店铺列表中…</p>:accounts.isError?<p className="setup-hint">店铺列表加载失败，请刷新页面。</p>:<>
 <label htmlFor="local-shop-select">店铺</label><select id="local-shop-select" value={accountId} onChange={e=>setAccountId(e.target.value)} disabled={!local.length}>
 {local.map(a=><option key={a.id} value={a.id}>{a.displayName} · {a.region}</option>)}</select>
 <label htmlFor="local-marketplace">Marketplace</label><select id="local-marketplace" value={marketplaceId} onChange={e=>setMarketplaceId(e.target.value)} disabled={!selected}>
 {choices.map(c=><option key={c.id} value={c.id}>{c.label}</option>)}</select>
 <button type="submit" className="primary-button" disabled={add.isPending||!selected||!marketplaceId}>{add.isPending?'保存中…':'添加站点'}</button>
 {!local.length&&<p className="setup-hint">请先创建本地店铺。</p>}</>}</form></div>
 {feedback&&<div className="panel-caption" role="status">{feedback}</div>}{failure&&<div className="setup-error" role="alert">{failure}</div>}
 <div className="setup-audit"><div className="setup-audit-heading"><strong>配置审计 · 最近 30 条</strong><button className="text-button" onClick={refresh}>刷新</button></div>
 {audit.isPending?<p className="setup-hint">正在读取配置审计…</p>:audit.isError?<p className="setup-hint" role="alert">审计读取失败</p>:!audit.data?.length?<p className="setup-hint">暂无配置操作记录。</p>:
 <div className="table-scroll" role="region" tabIndex={0} aria-label="可水平滚动的本地配置审计表格"><table><thead><tr><th>操作</th><th>资源标识</th><th>发生时间</th></tr></thead><tbody>{audit.data.map(e=><tr key={e.id}><td>{e.action==='LOCAL_SELLER_ACCOUNT_CREATED'?'创建店铺':'添加站点'}</td><td>{e.resourceId.slice(0,8)}…</td><td>{e.createdAt}</td></tr>)}</tbody></table></div>}</div></section>
}
