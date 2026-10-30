import { z } from 'zod'

const moneySchema = z.object({
  amount: z.string().regex(/^-?\d+(?:\.\d{1,6})?$/),
  currency: z.string().regex(/^[A-Z]{3}$/)
})

const accountSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  region: z.enum(['NA', 'EU', 'FE']),
  marketplaceId: z.string(),
  currency: z.string(),
  connectionStatus: z.literal('MOCK_ONLY')
})

const listingSchema = z.object({
  id: z.string(),
  accountId: z.string().uuid(),
  sellerSku: z.string(),
  asin: z.string(),
  internalSku: z.string().nullable(),
  mappingStatus: z.enum(['ACTIVE', 'UNMAPPED', 'CONFLICT', 'SUGGESTED'])
})

const orderSchema = z.object({
  id: z.string(),
  accountId: z.string().uuid(),
  externalOrderId: z.string(),
  sellerSku: z.string(),
  fulfillmentStatus: z.string(),
  refundStatus: z.enum(['NONE', 'PARTIAL', 'FULL', 'UNKNOWN']),
  financialStatus: z.literal('NOT_OBSERVED'),
  provisionalSales: moneySchema,
  refundAmount: moneySchema,
  sourceUpdatedAt: z.string()
})

const inventorySchema = z.object({
  id: z.string(),
  accountId: z.string().uuid(),
  sellerSku: z.string(),
  fulfillable: z.number().int().nonnegative().nullable(),
  reserved: z.number().int().nonnegative().nullable(),
  quality: z.enum(['OBSERVED', 'UNKNOWN', 'STALE', 'PARTIAL']),
  observedAt: z.string().nullable()
})

const summarySchema = z.object({
  accountId: z.string().uuid(),
  currency: z.string(),
  orderCount: z.number().int().nonnegative(),
  provisionalSales: moneySchema,
  observedRefunds: moneySchema,
  listingCount: z.number().int().nonnegative(),
  unmappedListings: z.number().int().nonnegative(),
  unknownInventoryCount: z.number().int().nonnegative(),
  financialCompleteness: z.literal('NOT_OBSERVED')
})

export const demoOverviewSchema = z.object({
  mode: z.literal('MOCK'),
  notice: z.string().min(1),
  fixtureAsOf: z.string(),
  accounts: z.array(accountSchema),
  listings: z.array(listingSchema),
  orders: z.array(orderSchema),
  inventory: z.array(inventorySchema),
  summaries: z.array(summarySchema)
})

export type DemoOverview = z.infer<typeof demoOverviewSchema>
export type DemoAccount = DemoOverview['accounts'][number]
export type DemoOrder = DemoOverview['orders'][number]
export type DemoListing = DemoOverview['listings'][number]
export type DemoInventory = DemoOverview['inventory'][number]

export function parseDemoOverview(value: unknown): DemoOverview {
  return demoOverviewSchema.parse(value)
}

export async function fetchDemoOverview(): Promise<DemoOverview> {
  const response = await fetch('/api/v1/demo/overview', {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin'
  })
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('Mock 演示未启用：请在本地设置 MERCHIVRA_DEMO_ENABLED=true。')
    }
    throw new Error(`Mock API 返回 HTTP ${response.status}`)
  }
  return parseDemoOverview(await response.json())
}

export const demoSyncRunSchema = z.object({
  id: z.string().uuid(),
  status: z.enum(['SUCCEEDED', 'FAILED_RETRYABLE']),
  source: z.literal('MOCK'),
  fixtureCount: z.number().int().nonnegative(),
  failureCode: z.string().nullable(),
  startedAt: z.string(),
  finishedAt: z.string()
})
export type DemoSyncRun = z.infer<typeof demoSyncRunSchema>

async function readList<T>(url: string, schema: z.ZodType<T>): Promise<T> {
  const res = await fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
  if (!res.ok) throw new Error(`Mock API: HTTP ${res.status}`)
  return schema.parse(await res.json())
}
export const fetchDemoOrders = (account: string) =>
  readList('/api/v1/demo/orders?accountId=' + encodeURIComponent(account), z.array(orderSchema))
export const fetchDemoListings = (account: string) =>
  readList('/api/v1/demo/listings?accountId=' + encodeURIComponent(account), z.array(listingSchema))
export const fetchDemoInventory = (account: string) =>
  readList('/api/v1/demo/inventory/fba?accountId=' + encodeURIComponent(account), z.array(inventorySchema))
export const fetchDemoSyncRuns = () =>
  readList('/api/v1/demo/sync-runs', z.array(demoSyncRunSchema))

export async function startDemoSync(simulateFailure: boolean): Promise<DemoSyncRun> {
  const response = await fetch('/api/v1/demo/sync-runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    credentials: 'same-origin',
    body: JSON.stringify({ simulateFailure })
  })
  if (!response.ok) throw new Error(`Mock 同步返回 HTTP ${response.status}`)
  return demoSyncRunSchema.parse(await response.json())
}

export const demoCoreStatusSchema = z.object({
  source: z.literal('MOCK'),
  tenantId: z.string().uuid(),
  accounts: z.number().int().nonnegative(),
  marketplaces: z.number().int().nonnegative(),
  internalSkus: z.number().int().nonnegative(),
  listings: z.number().int().nonnegative(),
  orders: z.number().int().nonnegative(),
  orderItems: z.number().int().nonnegative(),
  inventoryObservations: z.number().int().nonnegative()
})
export type DemoCoreStatus = z.infer<typeof demoCoreStatusSchema>
export const fetchDemoCoreStatus = () =>
  readList('/api/v1/demo/core-status', demoCoreStatusSchema)
