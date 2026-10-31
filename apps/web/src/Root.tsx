import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import App from './App'
import { fetchViewer, loginSession, logoutSession } from './lib/auth'
import { acceptTeamInvitation } from './lib/team'
import { errorText } from './ui'

export default function Root() {
  const client = useQueryClient()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [inviteMode, setInviteMode] = useState(false)
  const [inviteToken, setInviteToken] = useState('')
  const [invitePassword, setInvitePassword] = useState('')
  const [activationMessage, setActivationMessage] = useState('')
  const [errorMessage, setErrorMessage] = useState('')
  const [authenticating, setAuthenticating] = useState(false)
  const [activating, setActivating] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  const viewerQuery = useQuery({
    queryKey: ['auth', 'me'], queryFn: fetchViewer, retry: false, staleTime: 0
  })
  function clearSensitiveCache() {
    for (const key of [['api', 'demo'], ['core'], ['setup'], ['sandbox'], ['sandbox-page'], ['orders-sync'], ['operations'], ['team']]) {
      client.removeQueries({ queryKey: key })
    }
  }
  async function signIn(e: FormEvent) {
    e.preventDefault()
    setErrorMessage('')
    setAuthenticating(true)
    try {
      await loginSession(username.trim(), password)
      setPassword('')
      clearSensitiveCache()
      await viewerQuery.refetch()
    } catch (error) {
      setErrorMessage(errorText(error, '登录失败')); setPassword('')
    } finally { setAuthenticating(false) }
  }
  async function activate(e: FormEvent) {
    e.preventDefault(); setErrorMessage(''); setActivating(true)
    try {
      await acceptTeamInvitation(inviteToken.trim(), invitePassword)
      setInviteToken(''); setInvitePassword('')
      setActivationMessage('账号已激活。现在可以使用邀请中的登录名和新密码登录。')
      setInviteMode(false)
    } catch (error) {
      setErrorMessage(errorText(error, '邀请激活失败')); setInvitePassword('')
    } finally { setActivating(false) }
  }
  async function signOut() {
    setErrorMessage(''); setSigningOut(true)
    try {
      await logoutSession()
      clearSensitiveCache()
      await viewerQuery.refetch()
    } catch (error) {
      setErrorMessage(errorText(error, '退出失败'))
    } finally { setSigningOut(false) }
  }
  if (viewerQuery.isPending) return <div className="auth-shell"><section className="auth-card" aria-live="polite">
    <div className="auth-symbol">M</div><h1>Merchivra</h1><p>正在校验登录会话…</p>
  </section></div>
  if (viewerQuery.isError) return <div className="auth-shell"><section className="auth-card" role="alert">
    <div className="auth-symbol">M</div><h1>无法验证身份</h1>
    <p>{errorText(viewerQuery.error, '请检查数据库与 API 服务。')}</p>
    <button className="auth-submit" type="button" onClick={() => void viewerQuery.refetch()}>重试</button>
  </section></div>
  if (viewerQuery.data) return <App viewer={viewerQuery.data} signingOut={signingOut} onLogout={() => void signOut()} logoutError={errorMessage} />
  return <div className="auth-shell">
    {!inviteMode ? <form className="auth-card" onSubmit={e => void signIn(e)}>
      <div className="auth-symbol">M</div><div className="auth-kicker">SELF-HOSTED COMMERCE ERP</div>
      <h1>登录 Merchivra</h1>
      <p>管理员由部署环境首次初始化；没有默认密码。当前仅支持本地账号与 Session 登录。</p>
      <label htmlFor="auth-username">用户名</label>
      <input id="auth-username" value={username} onChange={e => setUsername(e.target.value)}
        autoComplete="username" minLength={3} maxLength={64} required placeholder="输入初始化用户名" />
      <label htmlFor="auth-password">密码</label>
      <input id="auth-password" value={password} onChange={e => setPassword(e.target.value)}
        type="password" autoComplete="current-password" required placeholder="输入管理员密码" />
      {errorMessage && <div className="auth-error" role="alert">{errorMessage}</div>}
      {activationMessage && <div role="status" className="setup-hint">{activationMessage}</div>}
      <button className="auth-submit" disabled={authenticating} type="submit">{authenticating ? '正在登录…' : '登录工作台'}</button>
      <div className="auth-footnote">首次运行前，在本地 <code>.env</code> 设置
        <code> MERCHIVRA_BOOTSTRAP_USERNAME </code> 和
        <code> MERCHIVRA_BOOTSTRAP_PASSWORD </code>（至少 16 位），然后启动或重启服务。</div>
      <button type="button" className="text-button" onClick={() => { setInviteMode(true); setErrorMessage('') }}>使用邀请令牌激活新管理员</button>
      <a href="https://github.com/kunlong-luo/merchivra/blob/main/docs/security/local-auth.md"
        target="_blank" rel="noopener noreferrer">部署与登录说明</a>
    </form> : <form className="auth-card" onSubmit={e => void activate(e)}>
      <div className="auth-symbol">M</div><h1>激活管理员邀请</h1>
      <p>邀请令牌仅使用一次，有效期 24 小时。请从管理员通过可信渠道提供的令牌激活；密码由你自己设置。</p>
      <label htmlFor="activation-token">一次性邀请令牌</label>
      <input id="activation-token" value={inviteToken} onChange={e => setInviteToken(e.target.value)}
        required autoComplete="off" minLength={43} maxLength={43} placeholder="粘贴邀请令牌" />
      <label htmlFor="activation-password">设置新密码（至少 16 位）</label>
      <input id="activation-password" value={invitePassword} onChange={e => setInvitePassword(e.target.value)}
        type="password" autoComplete="new-password" minLength={16} maxLength={128} required placeholder="设置独立登录密码" />
      {errorMessage && <p className="auth-error" role="alert">{errorMessage}</p>}
      <button type="submit" className="auth-submit" disabled={activating}>{activating ? '激活中…' : '激活账号'}</button>
      <button type="button" className="text-button" onClick={() => {
        setInviteMode(false); setInviteToken(''); setInvitePassword(''); setErrorMessage('')
      }}>返回登录</button>
    </form>}
  </div>
}
