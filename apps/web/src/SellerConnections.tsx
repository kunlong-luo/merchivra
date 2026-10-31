import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchLocalAccounts } from './lib/setup'
import { getOauthStatus, getOauthAudit, startSellerOauth, verifySellerOauth,
  revokeSellerOauth, getRoleReviews, requestRoleReview, decideRoleReview,
  revokeOrdersRole } from './lib/connection'

const oauthLabels: Record<string, string> = {
  NOT_CONNECTED: '未连接', PENDING_VERIFICATION: '等待站点核验',
  VERIFYING: '核验中', VERIFICATION_FAILED: '站点核验失败',
  VERIFIED: '已核验', REVOKED: '本地已撤销'
}
function errorText(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请刷新状态'
}
function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '未知时间' :
    date.toLocaleString('zh-CN', { timeZone: 'UTC', hour12: false }) + ' UTC'
}
function maskSeller(id: string | null) {
  if (!id) return '尚无 Seller ID'
  return id.length > 8 ? id.slice(0, 4) + '••••' + id.slice(-4) : '••••'
}
export default function SellerConnections({ tenantId, currentUserId }: {
  tenantId: string; currentUserId: string
}) {
  const client = useQueryClient()
  const [selection, setSelection] = useState('')
  const [message, setMessage] = useState('')
  const [failure, setFailure] = useState('')
  const [beta, setBeta] = useState(false)
  const [digest, setDigest] = useState('')
  const [caseRef, setCaseRef] = useState('')
  const returnFromOauth = new URLSearchParams(window.location.search).get('amazon_oauth') === 'pending_verification'
  const accounts = useQuery({
    queryKey: ['connections', tenantId, 'accounts'], queryFn: fetchLocalAccounts, staleTime: 15_000
  })
  const selectedId = accounts.data?.some(a => a.id === selection)
    ? selection : (accounts.data?.[0]?.id ?? '')
  const selected = accounts.data?.find(a => a.id === selectedId)
  const oauth = useQuery({
    queryKey: ['connections', tenantId, selectedId, 'oauth'],
    queryFn: () => getOauthStatus(selectedId), enabled: !!selectedId, retry: false, staleTime: 10_000
  })
  const audit = useQuery({
    queryKey: ['connections', tenantId, selectedId, 'audit'],
    queryFn: () => getOauthAudit(selectedId),
    enabled: !!selectedId && oauth.isSuccess, retry: false, staleTime: 10_000
  })
  const reviews = useQuery({
    queryKey: ['connections', tenantId, selectedId, 'orders-role-review'],
    queryFn: () => getRoleReviews(selectedId),
    enabled: !!selectedId && oauth.data?.status === 'VERIFIED',
    retry: false, staleTime: 10_000
  })
  function refresh() {
    void client.invalidateQueries({ queryKey: ['connections', tenantId] })
    void client.invalidateQueries({ queryKey: ['setup', tenantId, 'accounts'] })
    void client.invalidateQueries({ queryKey: ['core', 'marketplaces'] })
  }
  const start = useMutation({
    mutationFn: () => startSellerOauth(selectedId, beta),
    onSuccess: result => {
      // A short-lived HttpOnly handoff cookie was set by the same-origin POST.
      // Navigate only to a strictly validated Seller Central consent URL.
      window.location.assign(result.authorizationUrl)
    },
    onError: error => setFailure(errorText(error))
  })
  const verify = useMutation({
    mutationFn: () => verifySellerOauth(selectedId),
    onSuccess: result => {
      setFailure('')
      setMessage('已核验 ' + result.verifiedMarketplaceCount + ' 个 Marketplace。Orders 权限仍需独立审核。')
      refresh()
    },
    onError: error => { setFailure(errorText(error)); refresh() }
  })
  const revoke = useMutation({
    mutationFn: () => revokeSellerOauth(selectedId,
      oauth.data?.authorizationMethod === 'PRIVATE_SELF'),
    onSuccess: () => {
      setFailure('')
      setMessage('已清除本地加密授权并阻止后续使用；Amazon Seller Central 的授权不受此操作影响。')
      refresh()
    },
    onError: error => setFailure(errorText(error))
  })
  const requestReview = useMutation({
    mutationFn: () => requestRoleReview(selectedId, digest, caseRef.trim()),
    onSuccess: () => {
      setFailure(''); setMessage('Orders 角色审核申请已提交，需不同的安全审核员独立确认。')
      setDigest(''); setCaseRef(''); refresh()
    },
    onError: error => setFailure(errorText(error))
  })
  const decision = useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'approve' | 'reject' }) =>
      decideRoleReview(id, action),
    onSuccess: result => {
      setFailure(''); setMessage('审核结果：' + result.state); refresh()
    },
    onError: error => setFailure(errorText(error))
  })
  const revokeRole = useMutation({
    mutationFn: () => revokeOrdersRole(selectedId),
    onSuccess: () => { setFailure(''); setMessage('Orders 角色证据已在本地撤销。'); refresh() },
    onError: error => setFailure(errorText(error))
  })
  function request(e: FormEvent) { e.preventDefault(); requestReview.mutate() }
  function confirmRevoke() {
    if (!window.confirm('确认撤销此店铺的本地 Amazon 授权？这会清除保存的加密凭证并阻止后续订单同步，且不能撤销 Amazon 侧授权。')) return
    revoke.mutate()
  }
  function confirmRevokeRole() {
    if (window.confirm('确认撤销此店铺的 Orders 角色审核证据并停止后续受保护 Orders 读取？')) revokeRole.mutate()
  }
  return <div className="core-workspace">
    <div className="core-intro">
      <strong>Amazon Seller 连接管理 · 仅租户 Owner</strong>
      <p>支持已有 OAuth / 站点核验 / Orders 角色审核的人工操作。所有真实 Amazon 网络出站默认关闭；此界面不会代替 Amazon 应用授权、安全审核或生产验收。</p>
    </div>
    {returnFromOauth && <div className="panel-caption" role="status">
      已收到 Amazon OAuth 回调；这只表示凭证交换阶段可能完成。请刷新状态并执行独立的 Marketplace 核验，不能直接视为已连接。
    </div>}
    <section className="panel full-panel">
      <div className="panel-heading"><div><h2>卖家连接</h2>
        <p>先创建本地店铺及站点，再由已授权的账号所有者在 Seller Central 同意授权。</p></div>
        <button className="refresh-button" type="button" onClick={refresh}>刷新状态</button>
      </div>
      {accounts.isPending ? <p className="empty-state">正在读取当前租户店铺…</p> :
        accounts.isError ? <p className="setup-error" role="alert">{errorText(accounts.error)}</p> :
        !accounts.data?.length ? <div className="empty-state">
          暂无 Seller Account。请到“核心业务数据 → 本地店铺配置与测试工具”创建占位店铺和 Marketplace。
        </div> : <>
          <div className="core-controls">
            <label className="shop-filter" htmlFor="oauth-account"><span>Seller Account</span>
              <select id="oauth-account" value={selectedId} onChange={e => {
                setSelection(e.target.value); setMessage(''); setFailure(''); setDigest(''); setCaseRef('')
              }}>
                {accounts.data.map(a => <option key={a.id} value={a.id}>
                  {a.displayName} · {a.region} · {a.connectionStatus}</option>)}
              </select>
            </label>
          </div>
          {selected && <p className="setup-hint">本地连接状态：{selected.connectionStatus}；只有服务端核验通过且具备独立 Orders 角色证据，才可能启用真实订单同步。</p>}
          {oauth.isPending ? <div className="empty-state">正在读取加密授权状态…</div> :
            oauth.isError ? <div className="core-query-error" role="alert">
              授权端点不可用：{errorText(oauth.error)}。检查部署开关；Demo 默认不能开启真实 OAuth。
            </div> : oauth.data && <>
              <div className="orders-sync-grid">
                <div><span>授权状态</span><strong>{oauthLabels[oauth.data.status] ?? oauth.data.status}</strong></div>
                <div><span>应用授权方式</span><strong>{oauth.data.authorizationMethod === 'PRIVATE_SELF'
                  ? 'Seller Central 私有应用自授权' : '公有应用 OAuth'}</strong></div>
                <div><span>授权 Seller ID（遮蔽）</span><strong>{maskSeller(oauth.data.sellerId)}</strong></div>
                <div><span>区域</span><strong>{oauth.data.region ?? selected?.region ?? '—'}</strong></div>
                <div><span>站点权限</span><strong>{oauth.data.status === 'VERIFIED' ? '仍需核对 24h 时效及角色' : '未满足正式准入'}</strong></div>
              </div>
              {oauth.data.authorizationMethod === 'PRIVATE_SELF' && <p className="setup-hint">
                私有应用授权须由管理员在 Seller Central 自授权后，通过服务端一次性导入程序写入
                加密 Vault。这里不会提供 Refresh Token 输入框。导入后重新刷新状态并验证 Marketplace。
              </p>}
              {oauth.data.authorizationMethod !== 'PRIVATE_SELF' &&
                ((oauth.data.status === 'NOT_CONNECTED' && selected?.connectionStatus === 'UNCONFIGURED') ||
                (oauth.data.status === 'REVOKED' && selected?.connectionStatus === 'REVOKED')) &&
                <label className="setup-hint" htmlFor="oauth-beta">
                  <input id="oauth-beta" type="checkbox" checked={beta}
                    onChange={e => setBeta(e.target.checked)} />
                  以 Amazon Draft 公有应用测试授权（version=beta）；已发布应用请勿勾选
                </label>}
              <div className="sandbox-actions">
                {oauth.data.authorizationMethod !== 'PRIVATE_SELF' &&
                  ((oauth.data.status === 'NOT_CONNECTED' && selected?.connectionStatus === 'UNCONFIGURED')
                    || (oauth.data.status === 'REVOKED' && selected?.connectionStatus === 'REVOKED')) &&
                  <button type="button" className="primary-button" disabled={start.isPending}
                    onClick={() => { setFailure(''); start.mutate() }}>
                    {start.isPending ? '创建授权请求中…' :
                      oauth.data.status === 'REVOKED' ? '重新授权原 Seller' : '前往 Amazon 授权'}
                  </button>}
                {oauth.data.status === 'REVOKED' && <p className="setup-hint">
                  只能重新授权此前绑定的同一个 Amazon Seller，旧的 Orders 角色审核与站点核验证据不会沿用。
                  如需接入其他 Seller，请新建店铺记录，避免混用历史订单和库存。
                </p>}
                {['PENDING_VERIFICATION', 'VERIFICATION_FAILED', 'VERIFIED'].includes(oauth.data.status) &&
                  <button type="button" className="refresh-button" disabled={verify.isPending}
                    onClick={() => { setFailure(''); verify.mutate() }}>
                    {verify.isPending ? '正在核验…' : '手动验证 Marketplace'}
                  </button>}
                {!['NOT_CONNECTED', 'REVOKED'].includes(oauth.data.status) &&
                  <button type="button" className="text-button" disabled={revoke.isPending}
                    onClick={confirmRevoke}>撤销本地授权</button>}
              </div>
              <p className="setup-hint">Marketplace 核验需真实生产配置和明确开启；失败后需按后端限流重试。本地撤销不能替代 Amazon Seller Central 撤权。</p>
            </>}
        </>}
      {message && <p className="panel-caption" role="status">{message}</p>}
      {failure && <p className="setup-error" role="alert">{failure}</p>}
    </section>
    {!!selectedId && oauth.isSuccess && <section className="panel full-panel">
      <div className="panel-heading"><div><h2>授权审计</h2>
        <p>仅显示动作和时间，不存储或回显 OAuth Code、Refresh Token、State。</p></div></div>
      {audit.isPending ? <p className="empty-state">正在读取审计事件…</p> :
        audit.isError ? <p className="setup-error" role="alert">{errorText(audit.error)}</p> :
        !audit.data?.length ? <p className="empty-state">暂无授权审计记录。</p> :
        <div className="table-scroll" role="region" tabIndex={0} aria-label="Seller OAuth 审计事件">
          <table><thead><tr><th scope="col">动作</th><th scope="col">时间（UTC）</th></tr></thead><tbody>
            {audit.data.map((e, i) => <tr key={e.occurredAt + e.type + i}>
              <td>{e.type}</td><td>{formatTime(e.occurredAt)}</td></tr>)}
          </tbody></table>
        </div>}
    </section>}
    {!!selectedId && oauth.data?.status === 'VERIFIED' && <section className="panel full-panel">
      <div className="panel-heading"><div><h2>Orders API 角色审核</h2>
        <p>Seller 站点参与资格不等于 Amazon 应用具有 Inventory and Order Tracking 权限。此处必须使用独立外部审核证据，且申请人不能自己批准。</p></div>
      </div>
      {reviews.isPending ? <p className="empty-state">正在读取 Orders 角色审核状态…</p> :
        reviews.isError ? <p className="setup-hint" role="status">
          角色审核模块未启用或不可访问：{errorText(reviews.error)}。默认关闭，不能据此宣称已有 Orders 权限。
        </p> : <>
          <form className="team-invite-form" onSubmit={request}>
            <label htmlFor="role-case">外部审核编号（不含敏感信息）</label>
            <input id="role-case" value={caseRef} onChange={e => setCaseRef(e.target.value.trim())}
              minLength={8} maxLength={128} pattern="[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}"
              required autoComplete="off" placeholder="例如 REVIEW-20261031-001" />
            <label htmlFor="role-digest">外部证明文件 SHA-256（64 位小写十六进制）</label>
            <input id="role-digest" value={digest} onChange={e => setDigest(e.target.value.trim())}
              required pattern="[0-9a-f]{64}" minLength={64} maxLength={64} autoComplete="off"
              placeholder="仅粘贴 SHA-256，不上传证据原文" />
            <button className="primary-button" type="submit" disabled={requestReview.isPending ||
              !/^[0-9a-f]{64}$/.test(digest) || !/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,127}$/.test(caseRef)}>
              {requestReview.isPending ? '提交中…' : '申请独立 Orders 角色审核'}</button>
          </form>
          {!!reviews.data?.length && <div className="table-scroll" role="region" tabIndex={0}
            aria-label="Orders 角色审核历史"><table><thead><tr>
              <th scope="col">外部审核编号</th><th scope="col">状态</th><th scope="col">申请时间</th><th scope="col">操作</th>
            </tr></thead><tbody>{reviews.data.map(r => <tr key={r.id}>
              <td>{r.externalCaseRef}</td><td>{r.state}</td><td>{formatTime(r.createdAt)}</td>
              <td>{r.state === 'PENDING' && r.requestedBy !== currentUserId &&
                <div className="sandbox-actions">
                  <button type="button" className="refresh-button" disabled={decision.isPending}
                    onClick={() => decision.mutate({ id: r.id, action: 'approve' })}>审核批准</button>
                  <button type="button" className="text-button" disabled={decision.isPending}
                    onClick={() => decision.mutate({ id: r.id, action: 'reject' })}>驳回</button>
                </div>}
                {r.state === 'PENDING' && r.requestedBy === currentUserId && '需另一名审核员'}
              </td>
            </tr>)}</tbody></table></div>}
          <button type="button" className="text-button" disabled={revokeRole.isPending}
            onClick={confirmRevokeRole}>撤销当前 Orders 角色证据</button>
        </>}
    </section>}
  </div>
}
