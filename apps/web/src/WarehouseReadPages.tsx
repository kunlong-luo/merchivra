import {useState,type FormEvent} from 'react'
import {useQuery} from '@tanstack/react-query'
import {type ColumnDef} from '@tanstack/react-table'
import {Select} from '@base-ui/react/select'
import {ChevronDown,Check} from 'lucide-react'
import CursorPageControls from './CursorPageControls'
import {ScopedTable} from './components/PurchasingDataTables'
import {fetchWarehouseStockPage,fetchPendingReceiptsPage,
 type LocalStock,type PendingPurchaseReceipt} from './lib/purchasing'

function safeTerm(s:string){return s.trim().toUpperCase()}
function searchIsValid(s:string){return /^[A-Z0-9_-]{0,80}$/.test(s)}

export function WarehouseStockPagePanel({tenantId,warehouseId}:{
 tenantId:string;warehouseId:string
}){
 const [draft,setDraft]=useState('')
 const [search,setSearch]=useState('')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const page=useQuery({
  queryKey:['purchasing',tenantId,'stock-page',warehouseId,search,cursor],
  queryFn:()=>fetchWarehouseStockPage({warehouseId,limit:50,search,cursor}),
  enabled:!!warehouseId,retry:false
 })
 const rows=page.data?.items??[]
 const columns:ColumnDef<LocalStock>[]=[
  {accessorKey:'internalSku',header:'内部 SKU',enableHiding:false},
  {accessorKey:'recordedReceivedQuantity',header:'历史采购收货',enableHiding:false},
  {accessorKey:'onHandQuantity',header:'本地台账余额',enableHiding:false}
 ]
 function apply(e:FormEvent){
  e.preventDefault()
  const next=safeTerm(draft)
  if(!searchIsValid(next))return
  setSearch(next);setCursors([null])
 }
 return <div className="core-tools">
  <form className="v107-search-form" onSubmit={apply}>
   <label htmlFor="v113-stock-search">内部 SKU 包含（服务器全仓查询）</label>
   <input id="v113-stock-search" type="search" maxLength={80}
    pattern="[A-Za-z0-9_-]*" autoComplete="off" value={draft}
    onChange={e=>setDraft(e.target.value)} placeholder="例如 STOCK-"/>
   <button className="refresh-button" type="submit">查询</button>
  </form>
  <p className="setup-hint">仓库全量可分页 · 当前第 {cursors.length} 页，
   本页 {rows.length} 个 SKU；不代表仓库全部 SKU 数或完整财务库存。
   当前页之外的 SKU 余额未知，库存写入另行精确核验。</p>
  {page.isPending?<p className="empty-state">读取库存页中…</p>:
   page.isError?<p role="alert" className="setup-error">
    库存余额读取失败：{String(page.error)}
    <button type="button" className="refresh-button"
     onClick={()=>void page.refetch()}>重试</button></p>:
   rows.length===0?<p className="empty-state">当前检索条件下没有库存记录。</p>:
   <ScopedTable rows={rows} columns={columns} getRowId={r=>r.productSkuId}
    label="本地仓库库存余额"/>}
  {!!page.data&&<CursorPageControls label="仓库库存余额" page={cursors.length}
   busy={page.isFetching} hasMore={page.data.hasMore}
   nextCursor={page.data.nextCursor}
   onPrev={()=>setCursors(v=>v.length>1?v.slice(0,-1):v)}
   onNext={next=>setCursors(v=>[...v,next])}/>}
 </div>
}

const statuses=[
 {value:'ALL',label:'全部待收货状态'},
 {value:'APPROVED',label:'已审批'},
 {value:'RECEIVING',label:'部分收货'}
] as const

export function PendingReceiptPages({tenantId,onOpen}:{
 tenantId:string;onOpen:(orderId:string,lineId:string)=>void
}){
 const [draft,setDraft]=useState('')
 const [search,setSearch]=useState('')
 const [status,setStatus]=useState<'ALL'|'APPROVED'|'RECEIVING'>('ALL')
 const [cursors,setCursors]=useState<(string|null)[]>([null])
 const cursor=cursors.at(-1)??null
 const page=useQuery({
  queryKey:['purchasing',tenantId,'pending-page',status,search,cursor],
  queryFn:()=>fetchPendingReceiptsPage({limit:50,status,search,cursor}),retry:false
 })
 const rows=page.data?.items??[]
 const columns:ColumnDef<PendingPurchaseReceipt>[]=[
  {accessorKey:'orderNumber',header:'采购单号',enableHiding:false},
  {accessorKey:'internalSku',header:'内部 SKU',enableHiding:false},
  {id:'received',header:'已收 / 订购',enableHiding:false,
   cell:({row})=>row.original.receivedQuantity+' / '+row.original.orderedQuantity},
  {accessorKey:'remainingQuantity',header:'待收货',enableHiding:false},
  {id:'cost',header:'原币单价',enableHiding:false,
   cell:({row})=>row.original.currency+' '+row.original.unitCost},
  {id:'action',header:'操作',enableHiding:false,
   cell:({row})=><button type="button" className="refresh-button"
    onClick={()=>onOpen(row.original.purchaseOrderId,row.original.purchaseOrderLineId)}>
    打开收货</button>}
 ]
 function apply(e:FormEvent){
  e.preventDefault()
  const next=safeTerm(draft)
  if(!searchIsValid(next))return
  setSearch(next);setCursors([null])
 }
 return <div className="core-tools">
  <div className="v107-list-toolbar">
   <form className="v107-search-form" onSubmit={apply}>
    <label htmlFor="v113-pending-search">采购单号或内部 SKU（服务器全库检索）</label>
    <input id="v113-pending-search" type="search" autoComplete="off"
     pattern="[A-Za-z0-9_-]*" maxLength={80} value={draft}
     onChange={e=>setDraft(e.target.value)} placeholder="例如 PO-"/>
    <button className="refresh-button" type="submit">查询</button>
   </form>
   <Select.Root items={statuses} value={status}
    onValueChange={v=>{
     if(v==='ALL'||v==='APPROVED'||v==='RECEIVING'){
      setStatus(v);setCursors([null])
     }
    }}>
    <Select.Label className="v105-filter-label">待收货状态（全库）</Select.Label>
    <Select.Trigger className="v105-filter-trigger" aria-label="待收货状态筛选">
     <Select.Value/>
     <Select.Icon><ChevronDown size={16} aria-hidden="true"/></Select.Icon>
    </Select.Trigger>
    <Select.Portal><Select.Positioner className="v105-filter-positioner"
     sideOffset={6} alignItemWithTrigger={false}>
     <Select.Popup className="v105-filter-popup">
      <Select.List className="v105-filter-list">
       {statuses.map(o=><Select.Item key={o.value} value={o.value}
        className="v105-filter-item">
        <Select.ItemText>{o.label}</Select.ItemText>
        <Select.ItemIndicator className="v105-filter-check">
         <Check size={16} aria-hidden="true"/></Select.ItemIndicator>
       </Select.Item>)}
      </Select.List>
     </Select.Popup>
    </Select.Positioner></Select.Portal>
   </Select.Root>
  </div>
  <p className="setup-hint">服务器汇总全部历史收货并保留剩余量大于零的采购明细。
   当前第 {cursors.length} 页，本页 {rows.length} 条（每页最多 50 条），
   不是全部待收货总数，也不表示供应商已发货。</p>
  {page.isPending?<p className="empty-state">读取待收货记录中…</p>:
   page.isError?<p role="alert" className="setup-error">
    待收货记录读取失败：{String(page.error)}
    <button type="button" className="refresh-button"
     onClick={()=>void page.refetch()}>重试</button></p>:
   rows.length===0?<p className="empty-state">当前条件下没有待收货明细。</p>:
   <ScopedTable rows={rows} columns={columns}
    getRowId={r=>r.purchaseOrderLineId} label="采购待收货明细"/>}
  {!!page.data&&<CursorPageControls label="采购待收货明细"
   page={cursors.length} busy={page.isFetching}
   hasMore={page.data.hasMore} nextCursor={page.data.nextCursor}
   onPrev={()=>setCursors(v=>v.length>1?v.slice(0,-1):v)}
   onNext={next=>setCursors(v=>[...v,next])}/>}
 </div>
}
