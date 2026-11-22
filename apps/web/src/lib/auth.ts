import { z } from 'zod'

export const viewerSchema = z.object({
  userId: z.string().uuid(),
  activeTenantId: z.string().uuid(),
  availableTenantIds: z.array(z.string().uuid()),
  isOwner: z.boolean().default(true)
})
export type AuthViewer = z.infer<typeof viewerSchema>

const csrfSchema = z.object({
  token: z.string().min(10),
  headerName: z.literal('X-CSRF-TOKEN')
})

export async function csrfHeader(): Promise<Record<string, string>> {
  const response = await fetch('/api/v1/auth/csrf', {
    credentials: 'same-origin',
    cache: 'no-store'
  })
  if (!response.ok) throw new Error('无法初始化登录安全校验')
  const csrf = csrfSchema.parse(await response.json())
  return { [csrf.headerName]: csrf.token }
}

// A broken API/proxy is not a failed login. Never turn 5xx or malformed
// responses into a guest session, or echo raw server errors to the UI.
export type SessionCheckFailure =
  | 'NETWORK' | 'SERVICE' | 'ROUTE' | 'FORBIDDEN' | 'RESPONSE' | 'UNKNOWN'

export class SessionCheckError extends Error {
  constructor(
    public readonly kind: SessionCheckFailure,
    public readonly httpStatus: number | null = null
  ) {
    super(`Session check failed: ${kind}${httpStatus === null ? '' : ` (HTTP ${httpStatus})`}`)
    this.name = 'SessionCheckError'
  }
}

export function sessionFailureDetails(error: unknown) {
  const failure = error instanceof SessionCheckError ? error : new SessionCheckError('UNKNOWN')
  const category = failure.kind
  const status = failure.httpStatus
  if (category === 'FORBIDDEN') return {
    heading: '当前账号无法访问工作空间',
    description: '服务已响应，但当前会话缺少有效的租户访问权限。',
    suggestion: '请检查本地管理员、成员关系及租户状态，或联系工作空间管理员。',
    diagnostic: status === null ? '会话权限受限' : `HTTP ${status} · /api/v1/auth/me`
  }
  if (category === 'ROUTE') return {
    heading: '本地服务连接需要检查',
    description: '登录检查接口未找到，目前无法确认登录状态。',
    suggestion: '检查 Next.js 的 /api 代理地址是否指向正确的 Spring Boot 服务。',
    diagnostic: status === null ? '会话接口未找到' : `HTTP ${status} · /api/v1/auth/me`
  }
  if (category === 'RESPONSE') return {
    heading: '本地服务响应异常',
    description: '服务已响应，但会话信息格式不符合预期。',
    suggestion: '请确认前后端使用相同版本，并检查 Spring Boot 日志。',
    diagnostic: '会话返回内容无效 · /api/v1/auth/me'
  }
  return {
    heading: '本地服务暂时未就绪',
    description: '暂时无法连接到登录服务。你的账号不一定有问题。',
    suggestion: '请确认 Spring Boot 与 PostgreSQL 已启动，再重新连接。',
    diagnostic: status === null
      ? '无法连接会话接口 · /api/v1/auth/me'
      : `HTTP ${status} · /api/v1/auth/me`
  }
}

export function retryableSessionFailure(error: unknown): boolean {
  return error instanceof SessionCheckError &&
    (error.kind === 'NETWORK' || error.kind === 'SERVICE')
}

export async function fetchViewer(): Promise<AuthViewer | null> {
  let res: Response
  try {
    res = await fetch('/api/v1/auth/me', { credentials: 'same-origin', cache: 'no-store' })
  } catch {
    throw new SessionCheckError('NETWORK')
  }
  if (res.status === 401) return null
  if (res.status === 403) throw new SessionCheckError('FORBIDDEN', res.status)
  if (res.status === 404) throw new SessionCheckError('ROUTE', res.status)
  if (!res.ok) throw new SessionCheckError(
    res.status >= 500 ? 'SERVICE' : 'UNKNOWN', res.status)
  let data: unknown
  try {
    data = await res.json()
  } catch {
    throw new SessionCheckError('RESPONSE')
  }
  const parsed = viewerSchema.safeParse(data)
  if (!parsed.success) throw new SessionCheckError('RESPONSE')
  return parsed.data
}

export async function loginSession(username: string, password: string): Promise<void> {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      ...(await csrfHeader()),
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    body: new URLSearchParams({ username, password })
  })
  if (response.status === 401) throw new Error('用户名或密码错误')
  if (!response.ok) throw new Error(`登录失败 (HTTP ${response.status})`)
}

export async function logoutSession(): Promise<void> {
  const response = await fetch('/api/v1/auth/logout', {
    method: 'POST',
    credentials: 'same-origin',
    headers: await csrfHeader()
  })
  if (!response.ok) throw new Error(`退出失败 (HTTP ${response.status})`)
}
