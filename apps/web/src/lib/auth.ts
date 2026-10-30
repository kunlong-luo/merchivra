import { z } from 'zod'

export const viewerSchema = z.object({
  userId: z.string().uuid(),
  activeTenantId: z.string().uuid(),
  availableTenantIds: z.array(z.string().uuid())
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

export async function fetchViewer(): Promise<AuthViewer | null> {
  const res = await fetch('/api/v1/auth/me', { credentials: 'same-origin', cache: 'no-store' })
  if (res.status === 401) return null
  if (!res.ok) throw new Error(`会话检测失败 (HTTP ${res.status})`)
  return viewerSchema.parse(await res.json())
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
