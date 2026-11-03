import { z } from 'zod'

export const marketplaceSchema = z.object({
  id: z.string().uuid(),
  sellerAccountId: z.string().uuid(),
  marketplaceId: z.string().min(1),
  region: z.string(),
  currency: z.string().regex(/^[A-Z]{3}$/),
  timezone: z.string(),
  connectionStatus: z.string()
})

export const coreListingSchema = z.object({
  id: z.string().uuid(),
  sellerSku: z.string(),
  asin: z.string().nullable(),
  fnsku: z.string().nullable(),
  mappingStatus: z.string(),
  internalSku: z.string().nullable(),
  sourceUpdatedAt: z.string().nullable(),
  dataOrigin: z.enum(['MOCK','UNVERIFIED','LIVE'])
})

export const coreOrderSchema = z.object({
  id: z.string().uuid(),
  externalOrderId: z.string(),
  fulfillmentStatus: z.string(),
  refundStatus: z.string(),
  financialStatus: z.string(),
  itemCount: z.number().int().nonnegative(),
  itemsObservation: z.enum(['NOT_OBSERVED','OBSERVED']).optional().default('NOT_OBSERVED'),
  sourceUpdatedAt: z.string().nullable(),
  dataOrigin: z.enum(['MOCK','UNVERIFIED','LIVE'])
})

export const coreOrderPageSchema = z.object({
  items:z.array(coreOrderSchema).max(100),
  nextCursor:z.string().min(1).max(512).nullable(),
  hasMore:z.boolean(),pageSize:z.number().int().min(1).max(100),
  searchPrefix:z.string().max(80)
}).superRefine((page,ctx)=>{
  if(page.hasMore && !page.nextCursor)ctx.addIssue({
    code:'custom',message:'Missing next cursor for additional orders'
  })
  if(!page.hasMore && page.nextCursor)ctx.addIssue({
    code:'custom',message:'Unexpected cursor on last order page'
  })
})
export type CoreOrderPage=z.infer<typeof coreOrderPageSchema>

export const coreOrderItemSchema = z.object({
  id: z.string().uuid(),
  externalItemId: z.string(),
  sellerSku: z.string().nullable(),
  quantity: z.number().int().nonnegative().nullable(),
  itemSubtotal: z.string().nullable(),
  currency: z.string().nullable()
})

export const coreInventorySchema = z.object({
  id: z.string().uuid(),
  sellerSku: z.string(),
  quality: z.enum(['OBSERVED', 'PARTIAL', 'UNKNOWN', 'STALE']),
  fulfillable: z.number().int().nonnegative().nullable(),
  reserved: z.number().int().nonnegative().nullable(),
  observedAt: z.string().nullable(),
  ingestedAt: z.string(),
  dataOrigin: z.enum(['MOCK','UNVERIFIED','LIVE'])
})

export type CoreMarketplace = z.infer<typeof marketplaceSchema>
export type CoreListing = z.infer<typeof coreListingSchema>
export type CoreOrder = z.infer<typeof coreOrderSchema>
export type CoreOrderItem = z.infer<typeof coreOrderItemSchema>
export type CoreInventory = z.infer<typeof coreInventorySchema>

async function getCoreList<T>(url: string, schema: z.ZodType<T>): Promise<T> {
  const response = await fetch(url, {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { Accept: 'application/json' }
  })
  if (!response.ok) {
    if (response.status === 401) throw new Error('登录已过期，请重新登录')
    if (response.status === 403) throw new Error('当前租户没有读取权限')
    if (response.status === 404) throw new Error('Marketplace 不存在或无权访问')
    throw new Error(`核心业务 API HTTP ${response.status}`)
  }
  return schema.parse(await response.json())
}

export function fetchCoreMarketplaces() {
  // Never send a tenantId from the browser; server derives it from Session.
  return getCoreList('/api/v1/seller-marketplaces', z.array(marketplaceSchema))
}
export function fetchCoreListings(marketplaceId: string) {
  return getCoreList(
    `/api/v1/seller-marketplaces/${encodeURIComponent(marketplaceId)}/listings?limit=50`,
    z.array(coreListingSchema)
  )
}
export function fetchCoreOrders(marketplaceId: string) {
  return getCoreList(
    `/api/v1/seller-marketplaces/${encodeURIComponent(marketplaceId)}/orders?limit=50`,
    z.array(coreOrderSchema)
  )
}
/** V86 server-side prefix search and keyset traversal, never browser tenant ID. */
export function fetchCoreOrdersPage(marketplaceId:string,
 searchPrefix:string='',cursor:string|null=null,pageSize=50) {
 const query=new URLSearchParams({limit:String(pageSize)})
 if(searchPrefix.trim())query.set('search',searchPrefix.trim())
 if(cursor)query.set('cursor',cursor)
 return getCoreList(
  `/api/v1/seller-marketplaces/${encodeURIComponent(marketplaceId)}/orders/page?${query}`,
  coreOrderPageSchema
 )
}
export function fetchCoreOrderItems(marketplaceId:string,orderId:string) {
  return getCoreList(
    `/api/v1/seller-marketplaces/${encodeURIComponent(marketplaceId)}/orders/${encodeURIComponent(orderId)}/items`,
    z.array(coreOrderItemSchema)
  )
}
export function fetchCoreInventory(marketplaceId: string) {
  return getCoreList(
    `/api/v1/seller-marketplaces/${encodeURIComponent(marketplaceId)}/inventory/fba?limit=50`,
    z.array(coreInventorySchema)
  )
}
