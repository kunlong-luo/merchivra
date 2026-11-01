import {z} from 'zod'

export const syncTaskKind = z.enum(['ALL','ORDERS','CATALOG_LISTINGS','CATALOG_FBA','FINANCES'])
export const syncTaskState = z.enum(['ALL','READY','RUNNING','RETRY','WAITING_RETRY','PAUSED','SUCCEEDED'])

export const syncTaskSchema = z.object({
  jobId: z.string().uuid(),
  marketplaceId: z.string().uuid(),
  kind: syncTaskKind.exclude(['ALL']),
  origin: z.enum(['MOCK','LIVE']),
  state: syncTaskState.exclude(['ALL']),
  committedPages: z.number().int().nonnegative(),
  retryCount: z.number().int().nonnegative(),
  nextAttemptAt: z.string().nullable(),
  updatedAt: z.string(),
  lastFailureCode: z.string().nullable(),
  leaseExpired: z.boolean()
})
export const syncTaskSnapshotSchema = z.object({
  summary: z.object({
    total: z.number().int().nonnegative(),
    ready: z.number().int().nonnegative(),
    running: z.number().int().nonnegative(),
    retrying: z.number().int().nonnegative(),
    paused: z.number().int().nonnegative(),
    succeeded: z.number().int().nonnegative()
  }),
  tasks: z.array(syncTaskSchema).max(100),
  limit: z.number().int().min(1).max(100),
  hasMore: z.boolean()
})
export type SyncTaskKind = z.infer<typeof syncTaskKind>
export type SyncTaskState = z.infer<typeof syncTaskState>

export async function fetchSyncTaskSnapshot(kind: SyncTaskKind, state: SyncTaskState) {
  const query = new URLSearchParams({kind,state,limit:'50'})
  const response = await fetch('/api/v1/operations/sync-tasks?' + query.toString(), {
    credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}
  })
  if (response.status === 401) throw new Error('登录已失效，请重新登录')
  if (response.status === 403) throw new Error('当前租户没有查看同步任务的 Owner 权限')
  if (!response.ok) throw new Error('读取同步任务失败（HTTP ' + response.status + '）')
  return syncTaskSnapshotSchema.parse(await response.json())
}
