import type {CoreListingPage,CoreOrderPage} from './core'

export type ScopedChoice={value:string;label:string;source:'MOCK'|'UNVERIFIED'|'LIVE'}
export type ScopedChoicePage={
 choices:ScopedChoice[];nextCursor:string|null;hasMore:boolean
}
/** UI adapters only; the server owns filtering, paging and seller-account ACL. */
export function listingChoices(page:CoreListingPage):ScopedChoicePage {
 return {choices:page.items.map(row=>({
  value:row.sellerSku,label:row.sellerSku+
   (row.asin?' · '+row.asin:' · ASIN 未观测')+
   ' · '+row.mappingStatus,
  source:row.dataOrigin
 })),nextCursor:page.nextCursor,hasMore:page.hasMore}
}
export function orderChoices(page:CoreOrderPage):ScopedChoicePage {
 return {choices:page.items.map(row=>({
  value:row.id,label:row.externalOrderId+' · '+row.dataOrigin+
   ' · '+row.fulfillmentStatus,
  source:row.dataOrigin
 })),nextCursor:page.nextCursor,hasMore:page.hasMore}
}
/** Never submit a value from an unloaded/stale page as a newly verified choice. */
export function onCurrentPickerPage(page:ScopedChoicePage|undefined,value:string):boolean {
 return !!value&&!!page?.choices.some(row=>row.value===value)
}
