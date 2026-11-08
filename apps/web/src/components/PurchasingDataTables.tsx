import {useMemo,useState,type FormEvent} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Select} from '@base-ui/react/select'
import {Check,ChevronDown,Columns3} from 'lucide-react'
import {
 flexRender,getCoreRowModel,useReactTable,
 type ColumnDef,type VisibilityState
} from '@tanstack/react-table'
import {fetchPurchaseOrdersPage,type Supplier,type PurchaseOrder,
 type LocalStockMovement} from '../lib/purchasing'

type Option={value:string;label:string}

/** A controlled Base UI select. Options are only local view filters, never RBAC. */
function ListFilter({label,value,options,onChange}:{
 label:string;value:string;options:Option[];onChange:(next:string)=>void
}){
 return <div className="v107-list-filter">
  <Select.Root items={options} value={value}
   onValueChange={next=>{
    if(next&&options.some(item=>item.value===next))onChange(next)
   }}>
   <Select.Label className="v105-filter-label">{label}</Select.Label>
   <Select.Trigger className="v105-filter-trigger"
    aria-label={label}>
    <Select.Value/>
    <Select.Icon><ChevronDown size={16} aria-hidden="true"/></Select.Icon>
   </Select.Trigger>
   <Select.Portal>
    <Select.Positioner className="v105-filter-positioner"
     sideOffset={6} alignItemWithTrigger={false}>
     <Select.Popup className="v105-filter-popup">
      <Select.List className="v105-filter-list">
       {options.map(option=><Select.Item key={option.value}
        value={option.value} className="v105-filter-item">
        <Select.ItemText>{option.label}</Select.ItemText>
        <Select.ItemIndicator className="v105-filter-check">
         <Check size={16} aria-hidden="true"/>
        </Select.ItemIndicator>
       </Select.Item>)}
      </Select.List>
     </Select.Popup>
    </Select.Positioner>
   </Select.Portal>
  </Select.Root>
 </div>
}

/** This table never sorts, paginates or searches beyond the API-returned rows. */
function ScopedTable<T>({rows,columns,getRowId,label,hidden={},labels={}}:{
 rows:T[];columns:ColumnDef<T>[];getRowId:(row:T)=>string
 label:string;hidden?:VisibilityState;labels?:Record<string,string>
}){
 const [visibility,setVisibility]=useState<VisibilityState>(hidden)
 const table=useReactTable({
  data:rows,columns,getRowId,getCoreRowModel:getCoreRowModel(),
  manualSorting:true,manualFiltering:true,manualPagination:true,pageCount:-1,
  state:{columnVisibility:visibility},onColumnVisibilityChange:setVisibility
 })
 return <>
  {table.getAllLeafColumns().some(c=>c.getCanHide())&&
   <div className="v105-table-toolbar v107-table-toolbar">
    <span className="v105-table-hint">当前视图 {rows.length} 条 · 不对未加载记录排序</span>
    <details className="v105-column-picker">
     <summary><Columns3 size={16} aria-hidden="true"/> 显示列</summary>
     <div role="group" aria-label={label+'可选列'} className="v105-column-options">
      {table.getAllLeafColumns().filter(c=>c.getCanHide()).map(c=>
       <label key={c.id}>
        <input type="checkbox" checked={c.getIsVisible()}
         onChange={c.getToggleVisibilityHandler()}/>
        {labels[c.id]??c.id}
       </label>)}
     </div>
    </details>
   </div>}
  <div className="table-scroll v107-data-table" role="region"
   tabIndex={0} aria-label={label}>
   <table>
    <thead>{table.getHeaderGroups().map(group=>
     <tr key={group.id}>{group.headers.map(header=>
      <th key={header.id} scope="col">
       {header.isPlaceholder?null:
        flexRender(header.column.columnDef.header,header.getContext())}
      </th>)}</tr>)}</thead>
    <tbody>{table.getRowModel().rows.map(row=>
     <tr key={row.id}>{row.getVisibleCells().map(cell=>
      <td key={cell.id}>
       {flexRender(cell.column.columnDef.cell,cell.getContext())}
      </td>)}</tr>)}</tbody>
   </table>
  </div>
 </>
}

export function SupplierDirectoryTable({suppliers}:{
 suppliers:Supplier[]
}){
 const columns:ColumnDef<Supplier>[]=[
  {accessorKey:'code',header:'供应商编号',enableHiding:false},
  {accessorKey:'name',header:'供应商名称',enableHiding:false},
  {accessorKey:'status',header:'状态',enableHiding:false,
   cell:({row})=><span className="status-chip chip-muted">
    {row.original.status}
   </span>}
 ]
 return <div className="v107-supplier-directory">
  <p className="setup-hint">当前接口返回 {suppliers.length} 个供应商（非全库总数）。</p>
  {suppliers.length===0?<p className="empty-state">暂无供应商。</p>:
   <ScopedTable rows={suppliers} columns={columns}
    getRowId={row=>row.id} label="供应商目录"/>}
 </div>
}

const statusOptions:Option[]=[
 {value:'ALL',label:'全部采购状态'},
 {value:'DRAFT',label:'草稿 DRAFT'},
 {value:'SUBMITTED',label:'待审批 SUBMITTED'},
 {value:'APPROVED',label:'已审批 APPROVED'},
 {value:'RECEIVING',label:'收货中 RECEIVING'},
 {value:'RECEIVED',label:'已收货 RECEIVED'},
 {value:'CANCELLED',label:'已取消 CANCELLED'}
]
/**
 * V110: filters and cursor are server-side. Never derive a global search from
 * the first 100 /orders records. Cache keys include tenant+filters+cursor.
 */
export function PurchaseOrdersTable({
 tenantId,suppliers,currentUserId,busy,onView,onAction,onCancel
}:{
 tenantId:string;suppliers:Supplier[]
 currentUserId:string;busy:boolean
 onView:(id:string)=>void
 onAction:(id:string,kind:'submit'|'approve')=>void
 onCancel:(order:PurchaseOrder)=>void
}){
 const [status,setStatus]=useState('ALL')
 const [draftSearch,setDraftSearch]=useState('')
 const [search,setSearch]=useState('')
 const [cursorStack,setCursorStack]=useState<(string|null)[]>([null])
 const cursor=cursorStack[cursorStack.length-1]
 const orders=useQuery({
  queryKey:['purchasing',tenantId,'orders-page',status,search,cursor],
  queryFn:()=>fetchPurchaseOrdersPage({limit:50,status,search,cursor}),
  retry:false
 })
 const rows=orders.data?.items??[]
 const hasMore=orders.data?.hasMore===true && !!orders.data.nextCursor
 const pageNumber=cursorStack.length
 const supplierNames=useMemo(()=>new Map(suppliers.map(s=>[s.id,
  s.code+' · '+s.name])),[suppliers])
 function changeStatus(next:string){
  setStatus(next);setCursorStack([null])
 }
 function applySearch(e:FormEvent){
  e.preventDefault()
  const normalized=draftSearch.trim().toUpperCase()
  if(!/^[A-Z0-9_-]{0,80}$/.test(normalized))return
  setSearch(normalized);setCursorStack([null])
 }
 const columns:ColumnDef<PurchaseOrder>[]=[
  {accessorKey:'orderNumber',header:'采购单号',enableHiding:false},
  {accessorKey:'status',header:'状态',enableHiding:false,
   cell:({row})=><span className="status-chip chip-muted">
    {row.original.status}
   </span>},
  {accessorKey:'supplierId',header:'供应商',
   cell:({row})=>supplierNames.get(row.original.supplierId)??
    '当前供应商目录未匹配 · '+row.original.supplierId.slice(0,8)},
  {accessorKey:'currency',header:'原币币种',enableHiding:false},
  {accessorKey:'updatedAt',header:'本地更新时间',
   cell:({row})=>new Date(row.original.updatedAt).toLocaleString('zh-CN')},
  {id:'actions',header:'操作',enableHiding:false,
   cell:({row})=>{
    const order=row.original
    return <div className="sandbox-actions v107-order-actions">
     <button type="button" className="refresh-button"
      onClick={()=>onView(order.id)}>明细</button>
     {order.status==='DRAFT'&&<button type="button"
      className="refresh-button" disabled={busy}
      onClick={()=>onAction(order.id,'submit')}>提交审批</button>}
     {order.status==='SUBMITTED'&&order.createdBy!==currentUserId&&
      <button type="button" className="primary-button"
       disabled={busy} onClick={()=>onAction(order.id,'approve')}>
       审批通过
      </button>}
     {['DRAFT','SUBMITTED','APPROVED'].includes(order.status)&&
      <button type="button" className="text-button"
       disabled={busy} onClick={()=>onCancel(order)}>取消</button>}
    </div>
   }}
 ]
 return <>
  <div className="v107-list-toolbar">
   <form className="v110-order-search" onSubmit={applySearch}>
    <label className="v107-search-label" htmlFor="v107-po-search">
     <span>采购单号（服务器全库检索）</span>
     <input id="v107-po-search" type="search" maxLength={80}
      pattern="[A-Za-z0-9_-]*"
      value={draftSearch} onChange={e=>setDraftSearch(e.target.value)}
      placeholder="输入单号，回车或点查询" autoComplete="off"/>
    </label>
    <button type="submit" className="refresh-button">查询</button>
   </form>
   <ListFilter label="筛选采购单状态" value={status}
    options={statusOptions} onChange={changeStatus}/>
  </div>
  <p className="setup-hint v107-loaded-scope">
   服务器按最新更新时间排序 · 第 {pageNumber} 页（每页至多 50 条）。
   当前页 {rows.length} 条；状态和单号条件由服务器查询整个租户，
   不把当前页当作全部采购单或审计快照。
  </p>
  {orders.isPending?<p className="empty-state" role="status">读取采购单第 {pageNumber} 页…</p>:
   orders.isError?<div role="alert" className="setup-error">
    读取采购单失败：{String(orders.error)}
    <button type="button" className="refresh-button"
     onClick={()=>void orders.refetch()}>重试</button>
   </div>:
   rows.length===0?<p className="empty-state">当前条件下没有匹配的采购单。</p>:
   <ScopedTable rows={rows} columns={columns}
    getRowId={row=>row.id} label="采购单列表"
    hidden={{supplierId:false,updatedAt:false}}
    labels={{supplierId:'供应商',updatedAt:'本地更新时间'}}/>}
  <div className="v110-page-actions" role="group" aria-label="采购单历史翻页">
   <button type="button" className="refresh-button"
    disabled={cursorStack.length===1||orders.isFetching}
    onClick={()=>setCursorStack(p=>p.slice(0,-1))}>上一页</button>
   <span aria-live="polite">第 {pageNumber} 页</span>
   <button type="button" className="refresh-button"
    disabled={!hasMore||orders.isFetching}
    onClick={()=>{if(orders.data?.nextCursor)setCursorStack(p=>[...p,orders.data!.nextCursor])}}>
    下一页
   </button>
  </div>
 </>
}

const movementLabels:Record<LocalStockMovement['movementType'],string>={
 PURCHASE_RECEIPT:'采购收货',OUTBOUND:'本地出库',
 TRANSFER_OUT:'调拨转出',TRANSFER_IN:'调拨转入',
 COUNT_ADJUSTMENT:'盘点调整',QC_QUARANTINE:'质检隔离',
 QC_RELEASE:'质检放行',LOT_QUARANTINE:'批次隔离'
}
const movementOptions:Option[]=[
 {value:'ALL',label:'全部流水类型'},
 ...Object.entries(movementLabels).map(([value,label])=>({value,label}))
]
export function WarehouseLedgerTable({movements}:{
 movements:LocalStockMovement[]
}){
 const [type,setType]=useState('ALL')
 const rows=movements.filter(m=>type==='ALL'||m.movementType===type)
 const columns:ColumnDef<LocalStockMovement>[]=[
  {accessorKey:'createdAt',header:'时间',enableHiding:false,
   cell:({row})=>new Date(row.original.createdAt).toLocaleString('zh-CN')},
  {accessorKey:'internalSku',header:'内部 SKU',enableHiding:false},
  {accessorKey:'quantity',header:'变动',enableHiding:false,
   cell:({row})=><strong className="v107-ledger-amount">
    {row.original.quantity>0?'+':''}{row.original.quantity}
   </strong>},
  {accessorKey:'movementType',header:'流水类型',enableHiding:false,
   cell:({row})=>movementLabels[row.original.movementType]},
  {id:'source',header:'业务来源 ID',enableHiding:false,
   cell:({row})=>{
    const m=row.original
    return <span className="v107-ledger-source">
     {m.purchaseReceiptId??m.operationId??m.inspectionId??'无来源记录'}
    </span>
   }},
  {accessorKey:'reason',header:'业务原因',enableHiding:false,
   cell:({row})=>row.original.reason??'采购已审批收货'}
 ]
 return <>
  <div className="v107-list-toolbar">
   <ListFilter label="筛选本地库存流水类型" value={type}
    options={movementOptions} onChange={setType}/>
  </div>
  <p className="setup-hint v107-loaded-scope">
   最近 {movements.length} 条服务器返回的本地流水中，当前显示 {rows.length} 条。
   类型筛选仅作用于本次加载的记录，不代表完整历史；
   每条流水保留来源 ID 与业务原因。
  </p>
  {rows.length===0?<p className="empty-state">本次加载的流水中没有此类型记录。</p>:
   <ScopedTable rows={rows} columns={columns}
    getRowId={row=>row.id} label="仓库完整库存流水"/>}
 </>
}
