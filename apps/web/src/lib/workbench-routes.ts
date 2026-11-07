/**
 * V100: one source of truth for Next App Router paths.
 * No business API or privileged data can appear in these helpers.
 * Spring Boot session and role checks remain authoritative.
 */
export const WORKBENCH_VIEWS=[
 'overview','core','orders','catalog','inventory',
 'purchasing','ops','finance','connections','team','sync'
] as const
export type WorkbenchView=typeof WORKBENCH_VIEWS[number]
export function isWorkbenchView(value:string):value is WorkbenchView{
 return (WORKBENCH_VIEWS as readonly string[]).includes(value)
}
export function workbenchPath(view:WorkbenchView):string{
 return view==='overview'?'/':'/'+view
}
export function viewFromWorkbenchPath(pathname:string|null):WorkbenchView|null{
 if(pathname===null)return null
 const trimmed=pathname.replace(/\/+$/,'')
 if(trimmed===''||trimmed==='/')return 'overview'
 const slug=trimmed.startsWith('/')?trimmed.slice(1):trimmed
 return !slug.includes('/')&&isWorkbenchView(slug)?slug:null
}
/** Next routes display real Core database data, not the Vite demo Gateway. */
export function coreSectionForView(view:WorkbenchView):
 'listings'|'orders'|'inventory'|null {
 if(view==='catalog')return 'listings'
 if(view==='orders')return 'orders'
 if(view==='inventory')return 'inventory'
 return null
}
