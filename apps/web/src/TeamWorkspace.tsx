import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listTeamMembers, listTeamInvites, createTeamInvite,
  revokeTeamInvite, setTeamReviewer, revokeTeamOwner, type IssuedInvite } from './lib/team'
import { fetchCoreMarketplaces } from './lib/core'
import { Chip, DataTable, errorText } from './ui'

type Role = 'OWNER' | 'OPERATOR_READ'
type Action = { kind: 'reviewer' | 'owner' | 'invite'; id: string; enabled?: boolean }
export default function TeamWorkspace({ tenantId, currentUserId }: {
  tenantId: string; currentUserId: string
}) {
  const client = useQueryClient()
  const [login, setLogin] = useState('')
  const [role, setRole] = useState<Role>('OWNER')
  const [sellerAccountId, setSellerAccountId] = useState('')
  const [result, setResult] = useState<IssuedInvite | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const scoped = useQuery({
    queryKey: ['team', tenantId, 'scope-accounts'], queryFn: fetchCoreMarketplaces, retry: false, staleTime: 30_000
  })
  const accounts = useMemo(() =>
    [...new Map((scoped.data ?? []).map(m => [m.sellerAccountId, m])).values()], [scoped.data])
  const members = useQuery({
    queryKey: ['team', tenantId, 'members'], queryFn: listTeamMembers, retry: false, staleTime: 10_000
  })
  const invites = useQuery({
    queryKey: ['team', tenantId, 'invites'], queryFn: listTeamInvites, retry: false, staleTime: 10_000
  })
  function refresh() { void client.invalidateQueries({ queryKey: ['team', tenantId] }) }
  const issue = useMutation({
    mutationFn: () => createTeamInvite(login.trim(), role, role === 'OPERATOR_READ' ? sellerAccountId : undefined),
    onSuccess: issued => {
      setResult(issued); setLogin(''); setMessage('邀请已生成。请通过可信渠道单独交给受邀者。')
      setError(''); refresh()
    },
    onError: e => setError(errorText(e, '邀请失败'))
  })
  const action = useMutation({
    mutationFn: async (args: Action) => {
      if (args.kind === 'reviewer') return setTeamReviewer(args.id, Boolean(args.enabled))
      if (args.kind === 'owner') return revokeTeamOwner(args.id)
      return revokeTeamInvite(args.id)
    },
    onSuccess: () => { setMessage('变更已提交并写入审计日志'); setError(''); refresh() },
    onError: e => setError(errorText(e, '操作失败'))
  })
  async function copyToken() {
    if (!result) return
    try {
      await navigator.clipboard.writeText(result.token)
      setMessage('邀请令牌已复制。发送后请清除剪贴板。')
    } catch { setError('浏览器无法使用剪贴板，请手动复制令牌') }
  }
  function removeOwner(id: string) {
    if (!window.confirm('确认撤销此管理员对当前租户的访问权限？此操作会使其会话立即失效。')) return
    action.mutate({ kind: 'owner', id })
  }
  function submitInvite(e: FormEvent) { e.preventDefault(); issue.mutate() }
  return <div className="team-workspace">
    <section className="panel full-panel">
      <div className="panel-heading"><div><h2>管理员与安全审核员</h2>
        <p>OWNER 具有全租户权限；OPERATOR_READ 仅可查看指定店铺的 Listing、订单与 FBA 观测。</p></div>
        <button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
      {members.isPending ? <div className="empty-state">读取成员…</div>
        : members.isError ? <p className="setup-hint" role="alert">{errorText(members.error, '无法读取团队')}</p>
        : <DataTable rows={members.data ?? []} rowKey={m => m.userId} label="团队成员与角色" columns={[
          { title: '登录名', className: 'strong-cell', render: m => <>
            {m.loginName} {m.userId === currentUserId && <span className="secondary-cell">（当前账号）</span>}
            <div className="secondary-cell">{m.role === 'OPERATOR_READ' ? '店铺只读员工' : '租户管理员'}
              {m.sellerAccountId && ' · ' + m.sellerAccountId.slice(0, 8)}</div>
          </> },
          { title: '成员状态', render: m => <Chip positive={m.status === 'ACTIVE'} muted={m.status !== 'ACTIVE'}>
            {m.status === 'ACTIVE' ? '有效' : '已撤销'}</Chip> },
          { title: 'Orders 角色审核权限', render: m =>
            <Chip positive={m.reviewer} muted={!m.reviewer}>{m.reviewer ? '已授权' : '未授权'}</Chip> },
          { title: '操作', render: m => <div className="sandbox-actions">
            <button className="refresh-button" type="button"
              disabled={action.isPending || m.status !== 'ACTIVE' || m.role !== 'OWNER' ||
                (m.userId === currentUserId && !m.reviewer)}
              onClick={() => action.mutate({ kind: 'reviewer', id: m.userId, enabled: !m.reviewer })}>
              {m.reviewer ? '撤销审核员' : '授予审核员'}</button>
            <button className="text-button" type="button"
              disabled={action.isPending || m.status !== 'ACTIVE' || m.userId === currentUserId}
              onClick={() => removeOwner(m.userId)}>移除管理员</button>
          </div> }
        ]} />}
    </section>
    <section className="panel full-panel">
      <div className="panel-heading"><div><h2>邀请另一位管理员</h2>
        <p>生成 24 小时有效的一次性令牌。可邀请 OWNER，或只授予指定店铺读权限的 OPERATOR_READ 员工。</p>
      </div></div>
      <form className="team-invite-form" onSubmit={submitInvite}>
        <label htmlFor="invite-role">邀请角色</label>
        <select id="invite-role" value={role} onChange={e => setRole(e.target.value as Role)} aria-label="邀请权限">
          <option value="OWNER">OWNER · 全租户管理员</option>
          <option value="OPERATOR_READ">OPERATOR_READ · 指定店铺只读员工</option>
        </select>
        {role === 'OPERATOR_READ' && <>
          <label htmlFor="invite-account">允许访问的 Seller Account</label>
          <select id="invite-account" value={sellerAccountId} onChange={e => setSellerAccountId(e.target.value)}
            required aria-label="员工授权店铺">
            <option value="" disabled>选择店铺</option>
            {accounts.map(a => <option key={a.sellerAccountId} value={a.sellerAccountId}>
              {a.region} · {a.marketplaceId}</option>)}
          </select>
        </>}
        <label htmlFor="invite-login">新管理员登录名</label>
        <div className="sandbox-actions">
          <input id="invite-login" value={login} onChange={e => setLogin(e.target.value)}
            required pattern="[A-Za-z0-9._-]{3,64}" minLength={3} maxLength={64}
            autoComplete="off" placeholder="例如 ops-reviewer" />
          <button type="submit" className="primary-button"
            disabled={issue.isPending || !login.trim() || (role === 'OPERATOR_READ' && !sellerAccountId)}>生成邀请</button>
        </div>
      </form>
      {result && <div className="team-invite-secret" role="status">
        <strong>仅本次显示：一次性邀请令牌</strong>
        <p>让受邀者在登录页选择“使用邀请令牌激活”，粘贴此令牌并设定独立密码。请勿将令牌贴入公开 Issue 或聊天记录。</p>
        <code>{result.token}</code>
        <div className="sandbox-actions"><button type="button" className="refresh-button" onClick={() => void copyToken()}>复制令牌</button>
          <button type="button" className="text-button" onClick={() => setResult(null)}>清除显示</button></div>
      </div>}
      {message && <p role="status" className="setup-hint">{message}</p>}
      {error && <p role="alert" className="setup-error">{error}</p>}
      <DataTable rows={invites.data ?? []} rowKey={r => r.id} label="最近的管理员邀请" columns={[
        { title: '登录名', render: r => <>{r.loginName} <small>{r.role === 'OPERATOR_READ' ? '· 店铺只读' : '· 管理员'}</small></> },
        { title: '状态', render: r => r.status },
        { title: '失效时间', render: r => new Date(r.expiresAt).toLocaleString('zh-CN') },
        { title: '操作', render: r => <button className="text-button" type="button"
          disabled={r.status !== 'PENDING' || action.isPending}
          onClick={() => action.mutate({ kind: 'invite', id: r.id })}>撤销邀请</button> }
      ]} />
      <p className="setup-hint">权限变更将立即影响后续服务端校验。OPERATOR_READ 仅能读取已授权店铺核心表，不可修改运营规则、创建同步任务、管理 OAuth/团队或读取敏感管理员接口。</p>
    </section>
  </div>
}
