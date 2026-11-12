import type {QueryClient} from '@tanstack/react-query'

/**
 * V144: the QueryClient is intentionally retained by the Next App Router
 * between login/logout and across browser back/forward. Never carry tenant
 * data or mutation results across a change of authenticated identity,
 * including sessions that share the SAME tenant but have different roles.
 *
 * Keep the mounted ['auth','me'] observer only: Root must refetch it to
 * establish the next principal. All other query domains (including new
 * future ERP modules) are removed by default rather than by a fragile list.
 */
export async function purgeBusinessSessionCache(client:QueryClient):Promise<void>{
 const belongsToBusiness=(query:{queryKey:readonly unknown[]})=>
  query.queryKey[0]!=='auth'
 // Cancel before removing so a previous user's in-flight response cannot
 // re-populate a cache under the new session. Do not retry old mutations.
 await client.cancelQueries({predicate:belongsToBusiness})
 client.removeQueries({predicate:belongsToBusiness})
 client.getMutationCache().clear()
}
