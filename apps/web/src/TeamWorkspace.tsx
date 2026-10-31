import {useMemo,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {listTeamMembers,listTeamInvites,createTeamInvite,revokeTeamInvite,setTeamReviewer,revokeTeamOwner} from './lib/team'
import type {IssuedInvite} from './lib/team'
import {fetchCoreMarketplaces} from './lib/core'
export default function TeamWorkspace({tenantId,currentUserId}:{tenantId:string;currentUserId:string}){
 const client=useQueryClient()
 const [login,setLogin]=useState(''),[role,setRole]=useState<'OWNER'|'OPERATOR_READ'>('OWNER'),[account,setAccount]=useState('')
 const [issued,setIssued]=useState<IssuedInvite|null>(null),[message,setMessage]=useState(''),[error,setError]=useState('')
 const scope=useQuery({queryKey:['team',tenantId,'scope-accounts'],queryFn:fetchCoreMarketplaces,retry:false,staleTime:30000})
 const members=useQuery({queryKey:['team',tenantId,'members'],queryFn:listTeamMembers,retry:false,staleTime:10000})
 const invites=useQuery({queryKey:['team',tenantId,'invites'],queryFn:listTeamInvites,retry:false,staleTime:10000})
 const accounts=useMemo(()=>[...new Map((scope.data??[]).map(m=>[m.sellerAccountId,m])).values()],[scope.data])
 const refresh=()=>void client.invalidateQueries({queryKey:['team',tenantId]})
 const issue=useMutation({mutationFn:()=>createTeamInvite(login,role,role==='OPERATOR_READ'?account:undefined),
   onSuccess:r=>{setIssued(r);setLogin('');setMessage('邀请已生成，请通过可信渠道交给受邀者。');setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 type Action={kind:'reviewer'|'owner'|'invite';id:string;enabled?:boolean}
 const action=useMutation({mutationFn:(a:Action)=>a.kind==='reviewer'?setTeamReviewer(a.id,!!a.enabled):a.kind==='owner'?revokeTeamOwner(a.id):revokeTeamInvite(a.id),
  onSuccess:()=>{setMessage('变更已保存并写入审计日志');setError('');refresh()},onError:(e:Error)=>setError(e.message)})
 async function copyToken(){if(!issued)return;try{await navigator.clipboard.writeText(issued.token);setMessage('邀请令牌已复制，发送后请清除剪贴板')}catch{setError('无法访问剪贴板，请手动复制令牌')}}
 function revoke(id:string){if(window.confirm('确认撤销此账号对当前租户的访问权限？旧会话将失效。'))action.mutate({kind:'owner',id})}
 return <div className="team-workspace">
 <section className="panel full-panel"><div className="panel-heading"><div><h2>管理员与安全审核员</h2><p>OWNER 具有全租户权限；OPERATOR_READ 仅可查看指定店铺的 Listing、订单与 FBA。</p></div><button className="refresh-button" onClick={refresh}>刷新</button></div>
 {members.isPending?<div className="empty-state">读取成员…</div>:members.isError?<p className="setup-hint" role="alert">{members.error instanceof Error?members.error.message:'无法读取团队'}</p>:
 <div className="table-scroll" role="region" tabIndex={0} aria-label="团队成员与角色"><table><thead><tr>{['登录名','成员状态','Orders 角色审核权限','操作'].map(h=><th key={h}>{h}</th>)}</tr></thead>
 <tbody>{(members.data??[]).map(m=><tr key={m.userId}><td className="strong-cell">{m.loginName} {m.userId===currentUserId&&<span className="secondary-cell">（当前账号）</span>}
 <div className="secondary-cell">{m.role==='OPERATOR_READ'?'店铺只读员工':'租户管理员'}{m.sellerAccountId&&' · '+m.sellerAccountId.slice(0,8)}</div></td>
 <td><span className={`status-chip ${m.status==='ACTIVE'?'chip-green':'chip-muted'}`}>{m.status==='ACTIVE'?'有效':'已撤销'}</span></td>
 <td><span className={`status-chip ${m.reviewer?'chip-green':'chip-muted'}`}>{m.reviewer?'已授权':'未授权'}</span></td>
 <td><div className="sandbox-actions"><button className="refresh-button" disabled={action.isPending||m.status!=='ACTIVE'||m.role!=='OWNER'||(m.userId===currentUserId&&!m.reviewer)}
 onClick={()=>action.mutate({kind:'reviewer',id:m.userId,enabled:!m.reviewer})}>{m.reviewer?'撤销审核员':'授予审核员'}</button>
 <button className="text-button" disabled={action.isPending||m.status!=='ACTIVE'||m.userId===currentUserId} onClick={()=>revoke(m.userId)}>移除管理员</button></div></td></tr>)}</tbody></table></div>}</section>
 <section className="panel full-panel"><div className="panel-heading"><div><h2>邀请另一位管理员</h2><p>生成 24 小时有效的一次性令牌。可选择 OWNER 或绑定店铺的 OPERATOR_READ。</p></div></div>
 <form className="team-invite-form" onSubmit={e=>{e.preventDefault();issue.mutate()}}>
 <label htmlFor="invite-role">邀请角色</label><select id="invite-role" value={role} aria-label="邀请权限" onChange={e=>setRole(e.target.value as 'OWNER'|'OPERATOR_READ')}><option value="OWNER">OWNER · 全租户管理员</option><option value="OPERATOR_READ">OPERATOR_READ · 指定店铺只读员工</option></select>
 {role==='OPERATOR_READ'&&<><label htmlFor="invite-account">允许访问的 Seller Account</label><select id="invite-account" value={account} required aria-label="员工授权店铺" onChange={e=>setAccount(e.target.value)}><option value="" disabled>选择店铺</option>{accounts.map(a=><option key={a.sellerAccountId} value={a.sellerAccountId}>{a.region} · {a.marketplaceId}</option>)}</select></>}
 <label htmlFor="invite-login">新管理员登录名</label><div className="sandbox-actions"><input id="invite-login" value={login} onChange={e=>setLogin(e.target.value.trim())} required pattern="[A-Za-z0-9._-]{3,64}" minLength={3} maxLength={64} autoComplete="off" placeholder="例如 ops-reviewer"/>
 <button type="submit" className="primary-button" disabled={issue.isPending||!login||(role==='OPERATOR_READ'&&!account)}>生成邀请</button></div></form>
 {issued&&<div className="team-invite-secret" role="status"><strong>仅本次显示：一次性邀请令牌</strong><p>受邀者在登录页面使用令牌激活。请勿发布到公开 Issue、日志或聊天记录。</p><code>{issued.token}</code><div className="sandbox-actions"><button className="refresh-button" onClick={()=>void copyToken()}>复制令牌</button><button className="text-button" onClick={()=>setIssued(null)}>清除显示</button></div></div>}
 {message&&<p className="setup-hint" role="status">{message}</p>}{error&&<p className="setup-error" role="alert">{error}</p>}
 <div className="table-scroll" role="region" tabIndex={0} aria-label="最近的管理员邀请"><table><thead><tr>{['登录名','状态','失效时间','操作'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{(invites.data??[]).map(i=><tr key={i.id}><td>{i.loginName} <small>{i.role==='OPERATOR_READ'?'· 店铺只读':'· 管理员'}</small></td><td>{i.status}</td><td>{new Date(i.expiresAt).toLocaleString('zh-CN')}</td><td><button className="text-button" disabled={i.status!=='PENDING'||action.isPending} onClick={()=>action.mutate({kind:'invite',id:i.id})}>撤销邀请</button></td></tr>)}</tbody></table></div>
 <p className="setup-hint">OPERATOR_READ 仅能读取指定店铺核心表，不可管理 OAuth、团队、同步任务或运营规则。</p>
 </section></div>
}
