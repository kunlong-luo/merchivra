import { z } from 'zod'
import { csrfHeader } from './auth'

const base = '/api/v1/seller-accounts'
export const oauthStatusSchema = z.object({
  status: z.string(), sellerId: z.string().nullable(), region: z.string().nullable()
})
export const oauthAuditSchema = z.array(z.object({
  type: z.string(), occurredAt: z.string()
}))
export const oauthStartSchema = z.object({
  authorizationUrl: z.string().url(), expiresAt: z.string()
})
export const verificationSchema = z.object({
  status: z.literal('VERIFIED'), verifiedMarketplaceCount: z.number().int().nonnegative()
})
export const roleReviewSchema = z.array(z.object({
  id: z.string().uuid(), state: z.string(),
  externalCaseRef: z.string(), evidenceSha256: z.string(),
  requestedBy: z.string().uuid(), createdAt: z.string(), reviewedAt: z.string().nullable()
}))
const createdReviewSchema = z.object({ id: z.string().uuid(), state: z.literal('PENDING') })
const decisionSchema = z.object({ state: z.string() })
export type OauthStatus = z.infer<typeof oauthStatusSchema>
export type RoleReview = z.infer<typeof roleReviewSchema>[number]

function path(accountId: string, suffix: string) {
  // Account is a route identifier only. Tenant identity is derived by the server Session.
  return base + '/' + encodeURIComponent(accountId) + suffix
}
async function response<T>(res: Response, schema: z.ZodType<T>): Promise<T> {
  if (!res.ok) {
    if (res.status === 404) throw new Error('功能尚未在服务端启用，或当前店铺不可访问')
    if (res.status === 401) throw new Error('登录已过期，请重新登录')
    if (res.status === 403) throw new Error('当前账号没有此操作权限')
    if (res.status === 409) throw new Error('状态或授权证据已变化，请刷新后重试')
    if (res.status === 429) throw new Error('请求过于频繁，请稍后再试')
    throw new Error('Seller 授权接口暂不可用（HTTP ' + res.status + '）')
  }
  return schema.parse(await res.json())
}
async function get<T>(url: string, schema: z.ZodType<T>): Promise<T> {
  return response(await fetch(url, {
    credentials: 'same-origin', cache: 'no-store',
    headers: { Accept: 'application/json' }
  }), schema)
}
async function post<T>(url: string, schema: z.ZodType<T>, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST', credentials: 'same-origin', cache: 'no-store',
    headers: {
      ...(await csrfHeader()),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  })
  return response(res, schema)
}
export function getOauthStatus(accountId: string) {
  return get(path(accountId, '/oauth/status'), oauthStatusSchema)
}
export function getOauthAudit(accountId: string) {
  return get(path(accountId, '/oauth/audit'), oauthAuditSchema)
}
/**
 * No arbitrary external redirect: the server controls the Amazon consent URL.
 * Recheck on the client before navigating in case a proxy injects a malicious URL.
 */
export function sellerCentralConsentUrl(value: string): string {
  const url = new URL(value)
  const allowed = ['sellercentral.amazon.com', 'sellercentral.amazon.de', 'sellercentral.amazon.co.jp']
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !allowed.includes(url.hostname) || url.pathname !== '/apps/authorize/consent' ||
      url.hash || !url.searchParams.get('application_id') || !url.searchParams.get('state')) {
    throw new Error('授权地址不符合允许的 Amazon Seller Central 域名及路径')
  }
  return url.toString()
}
export async function startSellerOauth(accountId: string, beta = false) {
  const result = await post(path(accountId, '/oauth/start') +
    (beta ? '?beta=true' : ''), oauthStartSchema)
  return { authorizationUrl: sellerCentralConsentUrl(result.authorizationUrl), expiresAt: result.expiresAt }
}
export function verifySellerOauth(accountId: string) {
  return post(path(accountId, '/oauth/verify'), verificationSchema)
}
export async function revokeSellerOauth(accountId: string): Promise<void> {
  const res = await fetch(path(accountId, '/oauth/revoke'), {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: await csrfHeader()
  })
  if (!res.ok) {
    await response(res, z.unknown())
    throw new Error('撤销操作未成功')
  }
  if (res.status !== 204) throw new Error('撤销响应不符合预期')
}
export function getRoleReviews(accountId: string) {
  return get(path(accountId, '/orders-role-review'), roleReviewSchema)
}
export function requestRoleReview(accountId: string, evidenceSha256: string, externalCaseRef: string) {
  return post(path(accountId, '/orders-role-review'), createdReviewSchema,
    { evidenceSha256, externalCaseRef })
}
export function decideRoleReview(reviewId: string, decision: 'approve' | 'reject') {
  return post(base + '/orders-role-review/' + encodeURIComponent(reviewId) + '/' + decision,
    decisionSchema)
}
export async function revokeOrdersRole(accountId: string): Promise<void> {
  const res = await fetch(path(accountId, '/orders-role-review/revoke'), {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: await csrfHeader()
  })
  if (res.status !== 204) {
    if (!res.ok) {
      await response(res, z.unknown())
    }
    throw new Error('撤销 Orders 角色证据未成功')
  }
}
