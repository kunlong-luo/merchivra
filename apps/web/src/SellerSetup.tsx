import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MARKETPLACE_PRESETS, fetchLocalAccounts, fetchSetupAudit, createLocalAccount, addLocalMarketplace } from './lib/setup'
import { Chip, DataTable, errorText } from './ui'

type Region = 'NA' | 'EU' | 'FE'
export default function SellerSetup({ tenantId }: { tenantId: string }) {
  const client = useQueryClient()
  const [displayName, setDisplayName] = useState('')
  const [region, setRegion] = useState<Region>('NA')
  const [accountId, setAccountId] = useState('')
  const [marketplaceId, setMarketplaceId] = useState('')
  const [requestKey, setRequestKey] = useState(() => crypto.randomUUID())
  const [feedback, setFeedback] = useState('')
  const [failure, setFailure] = useState('')
  const accounts = useQuery({ queryKey: ['setup', tenantId, 'accounts'], queryFn: fetchLocalAccounts, staleTime: 15_000 })
  const audit = useQuery({ queryKey: ['setup', tenantId, 'audit'], queryFn: fetchSetupAudit, staleTime: 15_000 })
  const localAccounts = useMemo(() => (accounts.data ?? []).filter(a => a.connectionStatus === 'UNCONFIGURED'), [accounts.data])
  const effectiveAccountId = localAccounts.some(a => a.id === accountId) ? accountId : (localAccounts[0]?.id ?? '')
  const selected = localAccounts.find(a => a.id === effectiveAccountId)
  const choices = MARKETPLACE_PRESETS[selected?.region ?? 'NA']
  const effectiveMarketplaceId = choices.some(m => m.id === marketplaceId) ? marketplaceId : (choices[0]?.id ?? '')
  useEffect(() => { if (effectiveAccountId !== accountId) setAccountId(effectiveAccountId) }, [effectiveAccountId, accountId])
  useEffect(() => { if (effectiveMarketplaceId !== marketplaceId) setMarketplaceId(effectiveMarketplaceId) }, [effectiveMarketplaceId, marketplaceId])
  function refresh() {
    void client.invalidateQueries({ queryKey: ['setup', tenantId] })
    void client.invalidateQueries({ queryKey: ['core', 'marketplaces'] })
  }
  const create = useMutation({
    mutationFn: () => createLocalAccount({ displayName: displayName.trim(), region }, requestKey),
    onSuccess: value => {
      setFailure(''); setFeedback(value.displayName + ' 已创建（尚未连接 Amazon）')
      setAccountId(value.id); setDisplayName(''); setRequestKey(crypto.randomUUID()); refresh()
    },
    onError: e => setFailure(errorText(e, '创建失败'))
  })
  const add = useMutation({
    mutationFn: () => addLocalMarketplace(effectiveAccountId, effectiveMarketplaceId),
    onSuccess: result => {
      setFailure(''); setFeedback(result.marketplaceId + ' 已添加（仅本地占位配置）'); refresh()
    },
    onError: e => setFailure(errorText(e, '添加失败'))
  })
  function submit(e: FormEvent, action: () => void) { e.preventDefault(); action() }
  return <section className="panel setup-panel">
    <div className="panel-heading"><div><h2>本地 Seller Account 配置</h2>
      <p>仅为当前租户创建未连接的店铺占位信息，不输入 Amazon Seller ID、Token 或 SP-API 密钥。</p></div>
      <Chip>未连接</Chip></div>
    <div className="setup-forms">
      <form className="setup-form" aria-busy={create.isPending} onSubmit={e => submit(e, () => create.mutate())}>
        <strong>创建店铺占位记录</strong><label htmlFor="local-shop-name">店铺名称</label>
        <input id="local-shop-name" value={displayName} onChange={e => setDisplayName(e.target.value)}
          required minLength={2} maxLength={100} placeholder="例如：测试店铺 A" autoComplete="off" />
        <label htmlFor="local-shop-region">区域</label>
        <select id="local-shop-region" value={region} onChange={e => setRegion(e.target.value as Region)}>
          <option value="NA">北美 NA</option><option value="EU">欧洲 EU</option><option value="FE">远东 FE</option>
        </select>
        <button type="submit" className="primary-button" disabled={create.isPending || displayName.trim().length < 2}>
          {create.isPending ? '保存中…' : '创建本地店铺'}</button>
      </form>
      <form className="setup-form" aria-busy={add.isPending} onSubmit={e => submit(e, () => add.mutate())}>
        <strong>添加 Marketplace 配置</strong>
        {accounts.isPending ? <p className="setup-hint">加载店铺列表中…</p>
          : accounts.isError ? <p className="setup-hint">店铺列表加载失败，请刷新页面。</p> : <>
          <label htmlFor="local-shop-select">店铺</label>
          <select id="local-shop-select" value={effectiveAccountId} onChange={e => setAccountId(e.target.value)}
            disabled={!localAccounts.length}>
            {localAccounts.map(account => <option key={account.id} value={account.id}>
              {account.displayName} · {account.region}</option>)}
          </select>
          <label htmlFor="local-marketplace">Marketplace</label>
          <select id="local-marketplace" value={effectiveMarketplaceId} onChange={e => setMarketplaceId(e.target.value)}
            disabled={!selected}>
            {choices.map(choice => <option key={choice.id} value={choice.id}>{choice.label}</option>)}
          </select>
          <button type="submit" className="primary-button"
            disabled={add.isPending || !selected || !effectiveMarketplaceId}>{add.isPending ? '保存中…' : '添加站点'}</button>
          {!localAccounts.length && <p className="setup-hint">请先创建本地店铺。</p>}
        </>}
      </form>
    </div>
    {feedback && <div role="status" className="panel-caption">{feedback}</div>}
    {failure && <div role="alert" className="setup-error">{failure}</div>}
    <div className="setup-audit">
      <div className="setup-audit-heading"><strong>配置审计 · 最近 30 条</strong>
        <button type="button" className="text-button" onClick={refresh}>刷新</button></div>
      {audit.isPending ? <p className="setup-hint" role="status">正在读取配置审计…</p>
        : audit.isError ? <p className="setup-hint" role="alert">审计读取失败，请尝试刷新。</p>
        : !audit.data?.length ? <p className="setup-hint">暂无配置操作记录。</p>
        : <DataTable rows={audit.data} rowKey={e => e.id} label="可水平滚动的本地配置审计表格" columns={[
          { title: '操作', render: e => e.action === 'LOCAL_SELLER_ACCOUNT_CREATED' ? '创建店铺' : '添加站点' },
          { title: '资源标识', render: e => e.resourceId.slice(0, 8) + '…' },
          { title: '发生时间', render: e => e.createdAt }
        ]} />}
    </div>
  </section>
}
