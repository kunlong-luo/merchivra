import {afterEach,describe,expect,it,vi} from 'vitest'
import {
 fetchSupplierScorecardsPage,fetchPurchaseFollowupsPage,
 scanPurchaseFollowups,setPurchaseFollowupState
} from './supplier-performance'
import {
 fetchSupplierReturnTracesPage,reportSupplierReturnDispatch,
 reportSupplierReturnReceipt
} from './lot-supplier-returns'

const id=(n:number)=>'00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
const warehouse=id(1),supplier=id(2),disposition=id(3),actor=id(4),key=id(5)
const finding=id(6)
const scorecard={supplierId:supplier,supplierName:'Synthetic',
 completeAsnSampleCount:2,observedOnTimeAsnCount:2,inspectedReceiptSampleCount:1,
 acceptedQcUnits:10,inspectedQcUnits:10,activeVarianceCount:0,
 observedOnTimePercent:'100.0',inspectedAcceptancePercent:'100.0',
 evidenceScore:null,scoringStatus:'INSUFFICIENT_EVIDENCE'}
const makeTrace=(n:number,status='DISPATCH_REPORTED')=>({
 id:id(100+n),dispositionId:disposition,sourceReceiptId:id(7),supplierId:supplier,
 quantity:8,carrierReference:'LOCAL-RET-'+n,reason:'Manually reported dispatch',
 status,dispatchedBy:actor,
 receivedQuantity:status==='DISPATCH_REPORTED'?null:7,
 receiptReference:status==='DISPATCH_REPORTED'?null:'LOCAL-ACK-'+n,
 receivedReportedBy:status==='DISPATCH_REPORTED'?null:id(8)
})
afterEach(()=>vi.unstubAllGlobals())
describe('V120 generated evidence and locally reported supplier return contracts',()=>{
 it('keeps missing sample score null and sends full supplier page cursors',async()=>{
  const urls:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   urls.push(url)
   expect(options.cache).toBe('no-store')
   expect(options.credentials).toBe('same-origin')
   if(url.includes('/scorecards'))return new Response(JSON.stringify({
    items:[scorecard],nextCursor:null,hasMore:false,pageSize:50
   }),{status:200})
   return new Response(JSON.stringify({items:[],nextCursor:null,hasMore:false,
    pageSize:50}),{status:200})
  }))
  const scores=await fetchSupplierScorecardsPage('SCORE-CUR')
  expect(scores.items[0].evidenceScore).toBeNull()
  expect(scores.items[0].scoringStatus).toBe('INSUFFICIENT_EVIDENCE')
  await fetchPurchaseFollowupsPage('ALL','FOLLOWUP-CUR')
  expect(urls).toHaveLength(2)
  const first=new URL(urls[0],'http://localhost'),second=new URL(urls[1],'http://localhost')
  expect(first.pathname).toBe('/api/v1/purchasing/supplier-performance/scorecards/page')
  expect(first.searchParams.get('cursor')).toBe('SCORE-CUR')
  expect(second.searchParams.get('cursor')).toBe('FOLLOWUP-CUR')
  expect(second.searchParams.get('filter')).toBe('ALL')
  expect(second.searchParams.get('limit')).toBe('50')
 })
 it('225 trace dispatch reports are accessible across five pages, including 201st',async()=>{
  const paths:string[]=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,options:RequestInit)=>{
   paths.push(url)
   expect(options.credentials).toBe('same-origin')
   expect(options.cache).toBe('no-store')
   const parsed=new URL(url,'http://localhost')
   const n=Number(parsed.searchParams.get('cursor')?.slice(1)??'0')
   const start=n*50
   return new Response(JSON.stringify({
    items:Array.from({length:Math.min(50,225-start)},(_,i)=>makeTrace(start+i+1)),
    pageSize:50,hasMore:start+50<225,
    nextCursor:start+50<225?'P'+(n+1):null
   }),{status:200})
  }))
  let cursor:string|null=null
  const sizes:number[]=[],ids=new Set<string>()
  for(let i=0;i<5;i++){
   const p=await fetchSupplierReturnTracesPage(warehouse,cursor)
   sizes.push(p.items.length)
   p.items.forEach(x=>ids.add(x.id))
   if(i===4)expect(p.items[0].id).toBe(id(301))
   cursor=p.nextCursor
  }
  expect(sizes).toEqual([50,50,50,50,25])
  expect(ids.size).toBe(225)
  expect(cursor).toBeNull()
  expect(paths[4]).toContain('/warehouses/'+warehouse+'/page')
 })
 it('local dispatch and independent receipt carry a CSRF token and no tenant identity',async()=>{
  const posted:Array<{url:string;init:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit={})=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v120-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   posted.push({url,init})
   return new Response(JSON.stringify(makeTrace(1,url.endsWith('/receipt')?
    'RECEIPT_MISMATCH':'DISPATCH_REPORTED')),{status:url.endsWith('/receipt')?200:201})
  }))
  await reportSupplierReturnDispatch({requestKey:key,dispositionId:disposition,
   carrierReference:'FAKE-TRACK-2026',reason:'Reported physical goods handed to carrier'})
  const receipt=await reportSupplierReturnReceipt(id(101),{
   receivedQuantity:7,receiptReference:'VENDOR-RCPT-2026',
   reason:'Supplier manually acknowledged seven cartons'})
  expect(receipt.status).toBe('RECEIPT_MISMATCH')
  expect(receipt.receivedQuantity).toBe(7)
  expect(posted).toHaveLength(2)
  for(const p of posted){
   expect(p.init.method).toBe('POST')
   expect(p.init.credentials).toBe('same-origin')
   expect(p.init.cache).toBe('no-store')
   expect(new Headers(p.init.headers).get('X-CSRF-TOKEN'))
    .toBe('v120-valid-csrf-token')
   expect(String(p.init.body)).not.toContain('tenantId')
  }
  expect(JSON.parse(String(posted[0].init.body))).toMatchObject({
   requestKey:key,dispositionId:disposition})
  expect(JSON.parse(String(posted[1].init.body))).toMatchObject({
   receivedQuantity:7,receiptReference:'VENDOR-RCPT-2026'})
 })
 it('human followup scan and state change use shared CSRF and acknowledge not outreach',async()=>{
  const calls:Array<{url:string;init:RequestInit}>=[]
  vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit={})=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v120-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   calls.push({url,init})
   if(url.endsWith('/scan'))return new Response(JSON.stringify({
    id:finding,triggerType:'MANUAL',matchedCount:1,clearedCount:0}),{status:200})
   return new Response(JSON.stringify({id:finding,state:'ACKNOWLEDGED'}),{status:200})
  }))
  expect((await scanPurchaseFollowups()).matchedCount).toBe(1)
  expect((await setPurchaseFollowupState(finding,'ACKNOWLEDGED')).state)
   .toBe('ACKNOWLEDGED')
  expect(JSON.parse(String(calls[1].init.body))).toEqual({state:'ACKNOWLEDGED'})
  expect(new Headers(calls[1].init.headers).get('X-CSRF-TOKEN'))
   .toBe('v120-valid-csrf-token')
 })
 it('invalid route UUID and inconsistent page envelope fail closed',async()=>{
  const spy=vi.fn()
  vi.stubGlobal('fetch',spy)
  expect(()=>fetchSupplierReturnTracesPage('../tenant')).toThrow()
  expect(()=>reportSupplierReturnReceipt('bad-id',{
   receivedQuantity:1,receiptReference:'RCPT-2026',
   reason:'Manual receipt note'})).toThrow()
  expect(()=>setPurchaseFollowupState('not-uuid','DONE')).toThrow()
  expect(spy).not.toHaveBeenCalled()
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({
   items:[],hasMore:true,nextCursor:null,pageSize:50
  }),{status:200})))
  await expect(fetchSupplierReturnTracesPage(warehouse)).rejects.toThrow()
 })
 it('409 when supplier receipt already reported never claims confirmed external delivery',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
   if(url.endsWith('/auth/csrf'))return new Response(JSON.stringify({
    token:'v120-valid-csrf-token',headerName:'X-CSRF-TOKEN'}),{status:200})
   return new Response(JSON.stringify({message:'State already changed'}),{status:409})
  }))
  await expect(reportSupplierReturnReceipt(id(101),{
   receivedQuantity:8,receiptReference:'RCPT-2026',
   reason:'Manual audit of supplier receipt'})).rejects.toThrow()
 })
})
