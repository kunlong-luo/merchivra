import {describe,expect,it,vi,afterEach} from 'vitest'
import {fetchSyncTaskSnapshot,syncTaskSnapshotSchema} from './sync-task-monitor'

const task = {
  jobId:'11111111-1111-4111-8111-111111111111',
  marketplaceId:'22222222-2222-4222-8222-222222222222',
  kind:'ORDERS',origin:'MOCK',state:'WAITING_RETRY',
  committedPages:2,retryCount:3,nextAttemptAt:'2026-11-01T00:05:00Z',
  updatedAt:'2026-11-01T00:00:00Z',lastFailureCode:'UPSTREAM_RETRY',
  leaseExpired:false
}
const snapshot={
  summary:{total:1,ready:0,running:0,retrying:1,paused:0,succeeded:0},
  tasks:[task],limit:50,hasMore:false
}

describe('tenant sync task monitoring',()=>{
  afterEach(()=>vi.unstubAllGlobals())
  it('decodes a bounded safe response without exposing cursors',()=>{
    const decoded=syncTaskSnapshotSchema.parse(snapshot)
    expect(decoded.tasks[0].state).toBe('WAITING_RETRY')
    expect(decoded.tasks[0]).not.toHaveProperty('nextToken')
    expect(()=>syncTaskSnapshotSchema.parse({...snapshot,tasks:[{...task,kind:'HACKED'}]})).toThrow()
    expect(()=>syncTaskSnapshotSchema.parse({...snapshot,limit:101})).toThrow()
  })
  it('fetches from the owner-session route without supplying tenant IDs or tokens',async()=>{
    const fetcher=vi.fn().mockResolvedValue({
      ok:true,status:200,json:async()=>snapshot
    })
    vi.stubGlobal('fetch',fetcher)
    const result=await fetchSyncTaskSnapshot('ORDERS','WAITING_RETRY')
    expect(result.summary.retrying).toBe(1)
    const [url,request]=fetcher.mock.calls[0]
    expect(url).toContain('/api/v1/operations/sync-tasks?')
    expect(url).toContain('kind=ORDERS')
    expect(url).toContain('state=WAITING_RETRY')
    expect(url).not.toContain('tenantId')
    expect(request.credentials).toBe('same-origin')
    expect(request.cache).toBe('no-store')
  })
  it('does not treat unauthorized requests as empty queues',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403}))
    await expect(fetchSyncTaskSnapshot('ALL','ALL')).rejects.toThrow('Owner')
  })
})
