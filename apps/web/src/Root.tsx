import {useState} from 'react'
import {useQuery,useQueryClient} from '@tanstack/react-query'
import App,{type View} from './App'
import {fetchViewer,loginSession,logoutSession,
 retryableSessionFailure,sessionFailureDetails} from './lib/auth'
import {acceptTeamInvitation} from './lib/team'
import {purgeBusinessSessionCache} from './lib/session-query-cache'

/**
 * A failed startup session probe must not be presented as a bad password.
 * Render a calm, actionable connection state rather than a technical alert.
 * Never render the authenticated ERP until /auth/me actually succeeds.
 */
function SessionConnectionState({error,onRetry}:{
 error:unknown;onRetry:()=>void
}){
 const detail=sessionFailureDetails(error)
 return <div className="auth-shell auth-connect-shell">
  <main className="auth-connect-card" aria-labelledby="auth-connect-title">
   <div className="auth-connect-brand"><span className="auth-symbol" aria-hidden="true">M</span>
    <span>MERCHIVRA <small>LOCAL WORKSPACE</small></span></div>
   <div className="auth-connect-illustration" aria-hidden="true">
    <span className="auth-connect-pulse"/><span className="auth-connect-line"/><span className="auth-connect-node"/>
   </div>
   <div className="auth-connect-kicker">本地连接状态 · 工作空间已保护</div>
   <h1 id="auth-connect-title">{detail.heading}</h1>
   <p className="auth-connect-description">{detail.description}</p>
   <p className="auth-connect-suggestion">{detail.suggestion}</p>
   <div className="auth-connect-actions">
    <button type="button" className="auth-submit" onClick={onRetry}>重新连接</button>
    <a href="https://github.com/kunlong-luo/merchivra/blob/main/docs/development.md#3-分别启动开发进程"
     target="_blank" rel="noopener noreferrer">查看启动说明</a>
   </div>
   <details className="auth-connect-details">
    <summary>查看连接检查方法和技术信息</summary>
    <p>如果只运行了 <code>pnpm dev</code>，还需要启动 Spring Boot 和 PostgreSQL 18。</p>
    <p>检查后端：<code>curl -i http://127.0.0.1:8080/api/v1/ping</code></p>
    <p>检查前端代理：<code>curl -i http://127.0.0.1:3000/api/v1/auth/me</code></p>
    <p>没有登录时，第二条命令返回 HTTP 401 是正常的；持续返回 5xx 时请检查服务端日志。</p>
    <p className="auth-connect-diagnostic">诊断信息：{detail.diagnostic}</p>
   </details>
  </main>
 </div>
}

export default function Root({initialView='core',routeNavigation=false,onNavigate}:{
 initialView?:View;routeNavigation?:boolean;
 onNavigate?:(view:View,mode?:'push'|'replace')=>void
}={}){
 const client=useQueryClient()
 const [username,setUsername]=useState(''),[password,setPassword]=useState('')
 const [inviteMode,setInviteMode]=useState(false),[inviteToken,setInviteToken]=useState('')
 const [invitePassword,setInvitePassword]=useState(''),[activationMessage,setActivationMessage]=useState('')
 const [errorMessage,setErrorMessage]=useState(''),[authenticating,setAuthenticating]=useState(false)
 const [activating,setActivating]=useState(false),[signingOut,setSigningOut]=useState(false)
 const {data:viewer,isPending,isError,error,refetch}=useQuery({
  queryKey:['auth','me'],queryFn:fetchViewer,staleTime:0,
  // Startup races are common in local dev. Retry only connectivity/5xx,
  // never 401 (guest), 403 (permission) or malformed authenticated data.
  retry:(attempt,failure)=>attempt<2&&retryableSessionFailure(failure),
  retryDelay:attempt=>Math.min(1000*(attempt+1),2000)
 })
 // V144: refuse partial cache prefix allowlists: purchase, catalog and new
 // future module queries must all be purged on identity changes.
 const clearCaches=()=>purgeBusinessSessionCache(client)
 async function signIn(event:React.FormEvent){
   event.preventDefault();setErrorMessage('');setAuthenticating(true)
   try {await loginSession(username,password);setPassword('');await clearCaches();await refetch()}
   catch(e){setErrorMessage(e instanceof Error?e.message:'登录失败');setPassword('')}
   finally{setAuthenticating(false)}
 }
 async function activate(event:React.FormEvent){
   event.preventDefault();setErrorMessage('');setActivating(true)
   try{await acceptTeamInvitation(inviteToken,invitePassword);setInviteToken('');setInvitePassword('');setActivationMessage('账号已激活。现在可以使用邀请中的登录名和新密码登录。');setInviteMode(false)}
   catch(e){setErrorMessage(e instanceof Error?e.message:'邀请激活失败');setInvitePassword('')}
   finally{setActivating(false)}
 }
 async function signOut(){
   setErrorMessage('');setSigningOut(true)
   try{await logoutSession();await clearCaches();await refetch()}
   catch(e){setErrorMessage(e instanceof Error?e.message:'退出失败')}
   finally{setSigningOut(false)}
 }
 if(isPending)return <div className="auth-shell auth-connect-shell" role="status">
  <section className="auth-connect-card auth-connect-loading">
   <div className="auth-connect-brand"><span className="auth-symbol" aria-hidden="true">M</span>
    <span>MERCHIVRA <small>LOCAL WORKSPACE</small></span></div>
   <div className="loading-ring" aria-hidden="true"/>
   <h1>正在连接工作空间</h1>
   <p>正在确认本地登录服务，业务数据将在会话验证成功后加载。</p>
  </section>
 </div>
 if(isError)return <SessionConnectionState error={error} onRetry={()=>void refetch()}/>
 if(viewer)return <App viewer={viewer} signingOut={signingOut} onLogout={()=>void signOut()} initialView={initialView} routeNavigation={routeNavigation} onNavigate={onNavigate}/>
 return <div className="auth-shell">
 {!inviteMode?<form className="auth-card" onSubmit={signIn}>
  <div className="auth-symbol">M</div><div className="auth-kicker">SELF-HOSTED COMMERCE ERP</div>
  <h1>登录 Merchivra</h1><p>管理员由部署环境首次初始化；没有默认密码。支持本地账号及会话登录。</p>
  <label htmlFor="auth-username">用户名</label><input id="auth-username" value={username} onChange={e=>setUsername(e.target.value.trim())} minLength={3} maxLength={64} autoComplete="username" placeholder="输入初始化用户名" required/>
  <label htmlFor="auth-password">密码</label><input id="auth-password" value={password} onChange={e=>setPassword(e.target.value)} type="password" autoComplete="current-password" placeholder="输入管理员密码" required/>
  {errorMessage&&<div className="auth-error" role="alert">{errorMessage}</div>}
  {activationMessage&&<div className="setup-hint" role="status">{activationMessage}</div>}
  <button className="auth-submit" disabled={authenticating} type="submit">{authenticating?'正在登录…':'登录工作台'}</button>
  <div className="auth-footnote">首次运行前，在本地 <code>.env</code> 设置 <code>MERCHIVRA_BOOTSTRAP_USERNAME</code> 和 <code>MERCHIVRA_BOOTSTRAP_PASSWORD</code>（至少 16 位）。</div>
  <button className="text-button" type="button" onClick={()=>{setInviteMode(true);setErrorMessage('')}}>使用邀请令牌激活新管理员</button>
  <a href="https://github.com/kunlong-luo/merchivra/blob/main/docs/security/local-auth.md" target="_blank" rel="noopener noreferrer">部署与登录说明</a>
 </form>:<form className="auth-card" onSubmit={activate}>
  <div className="auth-symbol">M</div><h1>激活管理员邀请</h1>
  <p>邀请令牌仅使用一次，有效期 24 小时。密码由受邀者独立设置。</p>
  <label htmlFor="activation-token">一次性邀请令牌</label><input id="activation-token" value={inviteToken} onChange={e=>setInviteToken(e.target.value.trim())} required autoComplete="off" minLength={43} maxLength={43} placeholder="粘贴邀请令牌"/>
  <label htmlFor="activation-password">设置新密码（至少 16 位）</label><input id="activation-password" value={invitePassword} onChange={e=>setInvitePassword(e.target.value)} type="password" autoComplete="new-password" minLength={16} maxLength={128} required placeholder="设置独立登录密码"/>
  {errorMessage&&<p className="auth-error" role="alert">{errorMessage}</p>}
  <button type="submit" className="auth-submit" disabled={activating}>{activating?'激活中…':'激活账号'}</button>
  <button type="button" className="text-button" onClick={()=>{setInviteMode(false);setInviteToken('');setInvitePassword('');setErrorMessage('')}}>返回登录</button>
 </form>}
 </div>
}
