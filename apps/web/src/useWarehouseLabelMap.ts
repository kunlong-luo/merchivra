import {useQuery} from '@tanstack/react-query'
import {fetchWarehouseLabels,type LocalWarehouse} from './lib/purchasing'

/** V115: one bounded HTTP batch for historical page labels beyond legacy 200.
 * Never display an unverified name for an inaccessible or missing warehouse.
 * Missing IDs remain raw IDs in the UI, not guessed or silently hidden.
 */
export function useWarehouseLabelMap(tenantId:string,
 ids:readonly (string|null|undefined)[],known:readonly LocalWarehouse[]) {
 const existing=new Map(known.map(w=>[w.id,w] as const))
 const missing=[...new Set(ids.filter((id):id is string=>!!id&&!existing.has(id)))]
  .sort().slice(0,100)
 const query=useQuery({
  queryKey:['purchasing',tenantId,'warehouse-label-batch-v115',...missing],
  queryFn:()=>fetchWarehouseLabels(missing),
  enabled:missing.length>0,retry:false,staleTime:60000
 })
 const labels=new Map(existing)
 for(const w of query.data??[])labels.set(w.id,w)
 return {labels,isFetching:query.isFetching,isError:query.isError,
  unresolved:missing.filter(id=>!labels.has(id))}
}
