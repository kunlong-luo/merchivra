import { z } from 'zod'
import { csrfHeader } from './auth'

const accountSchema = z.object({
  id: z.string().uuid(), displayName: z.string(),
  region: z.enum(['NA', 'EU', 'FE']), connectionStatus: z.string()
})
const marketplaceSchema = z.object({
  id: z.string().uuid(), sellerAccountId: z.string().uuid(),
  marketplaceId: z.string(), currency: z.string(),
  timezone: z.string(), connectionStatus: z.string()
})
const auditSchema = z.object({
  id: z.string().uuid(), actorUserId: z.string().uuid(),
  action: z.enum(['LOCAL_SELLER_ACCOUNT_CREATED', 'LOCAL_MARKETPLACE_ADDED']),
  resourceType: z.string(), resourceId: z.string().uuid(), createdAt: z.string()
})
export type LocalAccount = z.infer<typeof accountSchema>

export const MARKETPLACE_PRESETS = {
  NA: [{ id: 'ATVPDKIKX0DER', label: '美国站 / USD' }],
  EU: [{ id: 'A1PA6795UKMFR9', label: '德国站 / EUR' }],
  FE: [{ id: 'A1VC38T7YXB528', label: '日本站 / JPY' }]
} as const

async function parse<T>(res: Response, schema: z.ZodType<T>): Promise<T> {
  if (!res.ok) {
    if (res.status === 401) throw new Error('请重新登录')
    if (res.status === 403) throw new Error('没有 Owner 权限')
    if (res.status === 404) throw new Error('店铺不可访问')
    if (res.status === 409) throw new Error('幂等键的原请求参数不同')
    throw new Error('本地配置失败：HTTP ' + res.status)
  }
  return schema.parse(await res.json())
}
export async function fetchLocalAccounts() {
  return parse(await fetch('/api/v1/seller-accounts', { credentials: 'same-origin' }),
    z.array(accountSchema))
}
export async function fetchSetupAudit() {
  return parse(await fetch('/api/v1/seller-accounts/audit', { credentials: 'same-origin' }),
    z.array(auditSchema))
}
export async function createLocalAccount(payload: {displayName: string; region: 'NA' | 'EU' | 'FE'}, key: string) {
  return parse(await fetch('/api/v1/seller-accounts', {
    method: 'POST', credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': key, ...(await csrfHeader())
    }, body: JSON.stringify(payload)
  }), accountSchema)
}
export async function addLocalMarketplace(accountId: string, marketplaceId: string) {
  return parse(await fetch('/api/v1/seller-accounts/' + encodeURIComponent(accountId) + '/marketplaces', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeader()) },
    body: JSON.stringify({ marketplaceId })
  }), marketplaceSchema)
}
