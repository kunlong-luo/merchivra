import {useState} from 'react'
import {
 flexRender,getCoreRowModel,useReactTable,
 type ColumnDef,type VisibilityState
} from '@tanstack/react-table'
import {Columns3} from 'lucide-react'
import type {FinanceEvent} from '../lib/finance'

const categories:Record<string,string>={
 PRODUCT_SALES:'销售入账分类',PRODUCT_REFUND:'退款',AMAZON_FEE:'平台费用',
 FBA_FEE:'FBA 费用',ADJUSTMENT:'其他调整',UNCLASSIFIED:'未分类'
}
const states:Record<FinanceEvent['reviewStatus'],string>={
 REVIEW_REQUIRED:'待人工核对',MATCHED:'已人工关联',UNMATCHED:'未匹配'
}
const optionalLabels:Record<string,string>={
 category:'类别',postedAt:'时间',sourceType:'来源类型'
}
function time(iso:string){
 return new Date(iso).toLocaleString('zh-CN',{hour12:false})
}

/**
 * V106: TanStack Table is purely a PRESENTATION model over one server-selected
 * 50-row finance page; there is no global client sorting or accounting math.
 * The source, signed original amount/currency, review state, event identity
 * and actions cannot be hidden. Event UUIDs are persistent row identifiers.
 */
export default function FinanceEventsTable({events,onReview}:{
 events:FinanceEvent[]
 onReview:(eventId:string)=>void
}){
 const [columnVisibility,setColumnVisibility]=useState<VisibilityState>({
  sourceType:false
 })
 const columns:ColumnDef<FinanceEvent>[]=[
  {accessorKey:'eventKey',header:'事件编号',enableHiding:false},
  {accessorKey:'dataOrigin',header:'来源',enableHiding:false,
   cell:({row})=><span className={row.original.dataOrigin==='LIVE'?
    'status-chip chip-muted':'status-chip chip-amber'}>
    {row.original.dataOrigin==='LIVE'?'SP-API 来源':'人工未核验'}
   </span>},
  {accessorKey:'category',header:'类别',
   cell:({row})=>categories[row.original.category]??row.original.category},
  {accessorKey:'amount',header:'原币金额（带符号）',enableHiding:false,
   cell:({row})=><span className="v106-finance-amount">
    {row.original.currency} {row.original.amount}
   </span>},
  {accessorKey:'postedAt',header:'发生时间',
   cell:({row})=>time(row.original.postedAt)},
  {accessorKey:'sourceType',header:'来源类型'},
  {accessorKey:'reviewStatus',header:'关联状态',enableHiding:false,
   cell:({row})=><span title={row.original.reviewStatus}>
    {states[row.original.reviewStatus]}
   </span>},
  {id:'actions',header:'操作',enableHiding:false,
   cell:({row})=><button type="button" className="text-button"
    onClick={()=>onReview(row.original.id)}>
    关联审核
   </button>}
 ]
 const table=useReactTable({
  data:events,columns,getCoreRowModel:getCoreRowModel(),
  getRowId:row=>row.id,
  state:{columnVisibility},onColumnVisibilityChange:setColumnVisibility,
  manualPagination:true,manualFiltering:true,manualSorting:true,pageCount:-1
 })
 return <>
  <div className="v105-table-toolbar v106-finance-toolbar">
   <span className="v105-table-hint">
    当前页 {table.getRowModel().rows.length} 条 · 服务端时间顺序 · 保留原币与来源
   </span>
   <details className="v105-column-picker v106-column-picker">
    <summary><Columns3 size={16} aria-hidden="true"/> 显示列</summary>
    <div role="group" aria-label="财务事件可选表格列"
     className="v105-column-options v106-column-options">
     {table.getAllLeafColumns().filter(c=>c.getCanHide()).map(c=>
      <label key={c.id}>
       <input type="checkbox" checked={c.getIsVisible()}
        onChange={c.getToggleVisibilityHandler()}/>
       {optionalLabels[c.id]??c.id}
      </label>)}
    </div>
   </details>
  </div>
  <div className="table-scroll v106-finance-events-table" role="region"
   tabIndex={0} aria-label="财务事件">
   <table>
    <thead>{table.getHeaderGroups().map(group=>
     <tr key={group.id}>{group.headers.map(header=>
      <th key={header.id} scope="col">
       {header.isPlaceholder?null:
        flexRender(header.column.columnDef.header,header.getContext())}
      </th>)}</tr>)}</thead>
    <tbody>{table.getRowModel().rows.map(row=>
     <tr key={row.id}>{row.getVisibleCells().map(cell=>
      <td key={cell.id}
       className={cell.column.id==='eventKey'?'strong-cell':undefined}>
       {flexRender(cell.column.columnDef.cell,cell.getContext())}
      </td>)}</tr>)}</tbody>
   </table>
  </div>
 </>
}
