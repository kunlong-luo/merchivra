import type {Sku} from './catalog'

/** V91: exact internal UUID selected ONLY on this bounded, successfully
 * loaded page. An archived SKU must never become an active writing target.
 */
export function visibleInternalSkus(rows:readonly Sku[],
 activeOnly=true):Sku[]{
 return rows.filter(s=>!activeOnly||s.status==='ACTIVE')
}
export function validInternalSkuChoice(rows:readonly Sku[],
 value:string,activeOnly=true):boolean {
 return !!value&&visibleInternalSkus(rows,activeOnly).some(s=>s.id===value)
}
/** Existing /catalog/skus is max 100; exactly 100 means a possible, not
 * guaranteed, next page. The after-cursor is the last actual internal SKU,
 * not a UUID.
 */
export function nextInternalSkuAfter(rows:readonly Sku[]):string|null {
 return rows.length===100?rows[99]?.internalSku??null:null
}
