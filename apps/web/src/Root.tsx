import {useState} from 'react'
import {useQuery,useQueryClient} from '@tanstack/react-query'
import App from './App'
import {fetchViewer,loginSession,logoutSession} from './lib/auth'
import {acceptTeamInvitation} from './lib/team'

export default function Root(){
 const client=useQueryClient()
 const [username,setUsername]=useState(''),[password,setPassword]=useState('')
 const [inviteMode,setInviteMode]=useState(false),[inviteToken,setInviteToken]=useState('')
 const [invitePassword,setInvitePassword]=useState(''),[activationMessage,setActivationMessage]=useState('')
 const [errorMessage,setErrorMessage]=useState(''),[authenticating,setAuthenticating]=useState(false)
 const [activating,setActivating]=useState(false),[signingOut,setSigningOut]=useState(false)
 const {data:viewer,isPending,isError,error,refetch}=useQuery({queryKey:['auth','me'],queryFn:fetchViewer,retry:false,staleTime:0})
 function clearCaches(){
   for(const key of [['api','demo'],['core'],['setup'],['sandbox'],['operations'],['team'],['orders-sync']])
     client.removeQueries({queryKey:key})
 }
 async function signIn(event:React.FormEvent){
   event.preventDefault();setErrorMessage('');setAuthenticating(true)
   try {await loginSession(username,password);setPassword('');clearCaches();await refetch()}
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
   try{await logoutSession();clearCaches();await refetch()}
   catch(e){setErrorMessage(e instanceof Error?e.message:'退出失败')}
   finally{setSigningOut(false)}
 }
 if(isPending||isError) return <div className="auth-shell"><section className="auth-card" role={isError?'alert':undefined}><div className="auth-symbol">M</div><h1>{isError?'无法验证身份':'Merchivra'}</h1><p>{isError?(error instanceof Error?error.message:'请检查数据库与 API 服务。'):'正在校验登录会话…'}</p>{isError&&<button className="auth-submit" onClick={()=>void refetch()}>重试</button>}</section></div>
 if(viewer)return <App viewer={viewer} signingOut={signingOut} onLogout={()=>void signOut()}/>
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
