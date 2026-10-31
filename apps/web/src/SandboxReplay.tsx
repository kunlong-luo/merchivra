import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {replaySandbox,fetchSandboxRuns,fetchMockPageProgress,advanceMockPage} from './lib/sandbox'
export default function SandboxReplay({tenantId,marketplaceId,marketplaceCode,connectionStatus}:{tenantId:string;marketplaceId:string;marketplaceCode:string;connectionStatus:string}){
 const client=useQueryClient(),[message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const [key,setKey]=useState(crypto.randomUUID()),[pageKey,setPageKey]=useState(crypto.randomUUID())
 const enabled=['ATVPDKIKX0DER','A1PA6795UKMFR9'].includes(marketplaceCode)&&connectionStatus==='UNCONFIGURED'
 const runs=useQuery({queryKey:['sandbox',tenantId,marketplaceId],queryFn:()=>fetchSandboxRuns(marketplaceId),enabled,staleTime:15000})
 const page=useQuery({queryKey:['sandbox-page',tenantId,marketplaceId],queryFn:()=>fetchMockPageProgress(marketplaceId),enabled,staleTime:10000})
 const advance=useMutation({mutationFn:()=>advanceMockPage(marketplaceId,pageKey),onSuccess:r=>{setMessage(r.replayed?'同一幂等键已执行过：不会再次提交页面':`已持久提交第 ${r.nextPage}/${r.totalPages} 页；事务代数 ${r.leaseEpoch}`);setFailure('');setPageKey(crypto.randomUUID());void page.refetch();void client.invalidateQueries({queryKey:['core',tenantId]})},onError:(e:Error)=>setFailure(e.message)})
 const replay=useMutation({mutationFn:(fail:boolean)=>replaySandbox(marketplaceId,key,fail),onSuccess:r=>{setMessage(r.status==='SUCCEEDED'?`模拟 Orders（${r.orderPageCount} 页）/Listing（${r.listingCount}）/FBA（${r.inventoryCount}）已幂等写入，财务未获授权`:'模拟失败已记录，业务数据未改变');setFailure('');setKey(crypto.randomUUID());void client.invalidateQueries({queryKey:['sandbox',tenantId]});void client.invalidateQueries({queryKey:['core',tenantId]});void client.invalidateQueries({queryKey:['setup',tenantId,'audit']})},onError:(e:Error)=>setFailure(e.message)})
 return <section className="sandbox-panel" aria-label="纯虚构 SP-API 模拟调用">
 <div className="sandbox-panel-top"><div><strong>模拟 SP-API 操作链 · Sandbox</strong><p>显式触发，使用固定虚构数据模拟订单分页、Listing 与 FBA 库存；严格标记 MOCK，不连接 Amazon。</p></div><span className="status-chip chip-amber">MOCK ONLY</span></div>
 {connectionStatus!=='UNCONFIGURED'?<p className="setup-hint">仅对未连接的本地店铺开放模拟回放，不覆盖已连接数据。</p>:
 !enabled?<p className="setup-hint">此站点尚无虚构场景。目前支持美国站和德国站。</p>:<>
 <div className="sandbox-actions"><button className="primary-button" disabled={replay.isPending} onClick={()=>replay.mutate(false)}>{replay.isPending?'模拟运行中…':'模拟拉取并入库'}</button>
 <button className="refresh-button" disabled={replay.isPending} onClick={()=>replay.mutate(true)}>模拟一次失败</button><span className="setup-hint">每次操作创建新的幂等键。</span></div>
 <div className="sandbox-page-progress"><strong>持久分页检查点（手动一页一事务）</strong>
 <p>第 {page.data?.nextPage??0} / {page.data?.totalPages??'—'} 页 · 状态 {page.data?.status??'读取中'} · 事务代数 {page.data?.leaseEpoch??0}（不会连接真实 Amazon）</p>
 {!!page.data?.totalPages&&<progress value={page.data.nextPage} max={page.data.totalPages} aria-label="已持久提交的模拟订单页进度"/>}
 <div className="sandbox-actions"><button className="refresh-button" disabled={advance.isPending||page.data?.status==='SUCCEEDED'||page.isError} onClick={()=>advance.mutate()}>{advance.isPending?'正在提交…':'提交下一模拟订单页'}</button><button className="text-button" onClick={()=>void page.refetch()}>刷新检查点</button></div>
 {page.isError&&<p className="setup-hint" role="alert">无法读取检查点，请检查授权或 API。</p>}
 <p className="setup-hint">断电或重启后从持久检查点继续。保留整批 Mock 导入。</p></div>
 {message&&<div className="panel-caption" role="status">{message}</div>}{failure&&<div className="setup-error" role="alert">{failure}</div>}
 {runs.isError?<p className="setup-hint">任务历史读取失败，请检查 Session 和数据库。</p>:!!runs.data?.length&&
 <div className="sandbox-history"><strong>模拟同步记录（最近 20 次）</strong><div className="table-scroll" role="region" tabIndex={0} aria-label="可水平滚动的模拟同步历史表格"><table>
 <thead><tr>{['时间','状态','Orders 页','Listing','订单','FBA','财务'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{runs.data.map(r=><tr key={r.id}><td className="small-cell">{r.createdAt}</td><td><span className={`status-chip ${r.status==='SUCCEEDED'?'chip-green':'chip-amber'}`}>{r.status}</span></td><td>{r.orderPageCount}</td><td>{r.listingCount}</td><td>{r.orderCount}</td><td>{r.inventoryCount}</td><td>{r.financeStatus}</td></tr>)}</tbody></table></div></div>}
 </>}</section>
}
