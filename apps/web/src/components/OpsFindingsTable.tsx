import {useState} from 'react'
import {
 flexRender,getCoreRowModel,useReactTable,
 type ColumnDef,type VisibilityState
} from '@tanstack/react-table'
import {Columns3} from 'lucide-react'
import OpsAttentionControls from '../OpsAttentionControls'
import type {Finding,FindingState} from '../lib/operations'
import {ruleNames,severityNames,stateNames} from '../lib/operations'
import {formatSourceTime,sourceLabel} from '../lib/core-view'

type Props={
 items:Finding[]
 tenantId:string
 userId:string
 editing:boolean
 canFba:(marketplaceId:string,cap:'assign'|'review'|'resolve')=>boolean
 onOpenFbaException:(marketplaceId:string,lineId:string)=>void
 onChangeState:(id:string,state:FindingState)=>void
}
const fbaRule=(code:string)=>code==='FBA_PO_EVIDENCE_CHANGED'||
 code==='FBA_PO_EXCEPTION_OVERDUE'
const optionalLabels:Record<string,string>={
 sourceKey:'关联来源',
 lastSeenAt:'上次发现',
 attention:'Owner / 提醒'
}
/**
 * TanStack Table owns the column model and stable row IDs, not the server's
 * pagination, rank, filtering or trust boundaries. The backend already
 * provides a cursor-paged, priority-ranked 50-row slice.
 */
export default function OpsFindingsTable({
 items,tenantId,userId,editing,canFba,onOpenFbaException,onChangeState
}:Props){
 const [columnVisibility,setColumnVisibility]=useState<VisibilityState>({})
 const columns:ColumnDef<Finding>[]=[
  {accessorKey:'severity',header:'级别',enableHiding:false,
   cell:({row})=><span className={`status-chip ${row.original.severity==='CRITICAL'?'chip-amber':'chip-muted'}`}>
    {severityNames[row.original.severity]}</span>},
  {accessorKey:'ruleCode',header:'异常类型',enableHiding:false,
   cell:({row})=>ruleNames[row.original.ruleCode]},
  {accessorKey:'sourceKey',header:'关联来源'},
  {accessorKey:'dataOrigin',header:'来源',enableHiding:false,
   cell:({row})=><span className={`status-chip ${row.original.dataOrigin==='MOCK'?'chip-amber':'chip-muted'}`}>
    {sourceLabel(row.original.dataOrigin)}</span>},
  {accessorKey:'state',header:'状态',enableHiding:false,
   cell:({row})=>stateNames[row.original.state]},
  {accessorKey:'lastSeenAt',header:'上次发现',
   cell:({row})=>formatSourceTime(row.original.lastSeenAt)},
  {id:'attention',header:'Owner / 提醒',
   cell:({row})=><OpsAttentionControls
    finding={row.original} tenantId={tenantId} userId={userId}
    canAssign={!fbaRule(row.original.ruleCode)||
     canFba(row.original.marketplaceId,'assign')}
    canReview={!fbaRule(row.original.ruleCode)||
     canFba(row.original.marketplaceId,'review')}/>},
  {id:'actions',header:'操作',enableHiding:false,
   cell:({row})=>{
    const i=row.original
    return <div className="ops-row-actions">
     {fbaRule(i.ruleCode)&&<button type="button" className="text-button"
       onClick={()=>onOpenFbaException(i.marketplaceId,i.sourceKey)}>
       查看 FBA 原处理单
      </button>}
     {(i.state==='OPEN'||i.state==='ACKNOWLEDGED')&&
      <button className="text-button" type="button"
       disabled={editing||(fbaRule(i.ruleCode)&&!canFba(i.marketplaceId,'resolve'))}
       onClick={()=>onChangeState(i.id,'DONE')}>标记已处理</button>}
     {(i.state==='DONE'||i.state==='ACKNOWLEDGED')&&
      <button className="text-button" type="button"
       disabled={editing||(fbaRule(i.ruleCode)&&!canFba(i.marketplaceId,'review'))}
       onClick={()=>onChangeState(i.id,'OPEN')}>重新打开</button>}
    </div>
   }}
 ]
 const table=useReactTable({
  data:items,columns,getCoreRowModel:getCoreRowModel(),
  getRowId:row=>row.id,
  manualPagination:true,manualFiltering:true,manualSorting:true,
  pageCount:-1,state:{columnVisibility},
  onColumnVisibilityChange:setColumnVisibility
 })
 return <>
  <div className="v105-table-toolbar">
   <span className="v105-table-hint">
    当前页 {table.getRowModel().rows.length} 条 · 服务端优先级排序 · 按需显示列
   </span>
   <details className="v105-column-picker">
    <summary><Columns3 size={16} aria-hidden="true"/> 显示列</summary>
    <div role="group" aria-label="运营待办可选表格列"
     className="v105-column-options">
     {table.getAllLeafColumns().filter(c=>c.getCanHide()).map(c=>
      <label key={c.id}>
       <input type="checkbox" checked={c.getIsVisible()}
        onChange={c.getToggleVisibilityHandler()}/>
       {optionalLabels[c.id]??c.id}
      </label>)}
    </div>
   </details>
  </div>
  <div className="table-scroll v105-ops-table" role="region"
   tabIndex={0} aria-label="可水平滚动的运营待办列表">
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
       className={cell.column.id==='ruleCode'?'strong-cell':
        ['sourceKey','lastSeenAt'].includes(cell.column.id)?'small-cell':undefined}>
       {flexRender(cell.column.columnDef.cell,cell.getContext())}
      </td>)}</tr>)}</tbody>
   </table>
  </div>
 </>
}
