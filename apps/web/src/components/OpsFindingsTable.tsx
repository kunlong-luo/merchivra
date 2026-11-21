import {useState} from 'react'
import {
 flexRender,getCoreRowModel,useReactTable,
 type ColumnDef,type VisibilityState
} from '@tanstack/react-table'
import {Columns3} from 'lucide-react'
import OpsAttentionControls from '../OpsAttentionControls'
import SourceEvidenceDrawer from './SourceEvidenceDrawer'
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
 const [inspectedFindingId,setInspectedFindingId]=useState('')
 const inspected=items.find(item=>item.id===inspectedFindingId)
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
     <button type="button" className="text-button"
      onClick={()=>setInspectedFindingId(i.id)}
      aria-label={'查看异常来源 '+i.title}>查看证据</button>
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
  <SourceEvidenceDrawer open={!!inspected} title="运营异常 · 来源证据"
   description="本次服务器游标页已读的异常快照；不等于原业务单据已完成"
   onClose={()=>setInspectedFindingId('')}>
   {inspected&&<>
    <div className="v179-evidence-tags">
     <span className="v179-source-tag">{severityNames[inspected.severity]}</span>
     <span className="v179-source-tag">{stateNames[inspected.state]}</span>
     <span className="v179-source-tag">{sourceLabel(inspected.dataOrigin)}</span>
    </div>
    <h4 className="v179-evidence-section">{inspected.title}</h4>
    <dl className="v179-evidence-facts">
     <div><dt>规则</dt><dd>{ruleNames[inspected.ruleCode]}</dd></div>
     <div><dt>关联站点</dt><dd><code>{inspected.marketplaceId}</code></dd></div>
     <div><dt>原始来源键</dt><dd><code>{inspected.sourceKey}</code></dd></div>
     <div><dt>发现时间</dt><dd>{formatSourceTime(inspected.lastSeenAt)}</dd></div>
     <div><dt>负责人</dt><dd>{inspected.assignedTo??'未分配'}</dd></div>
     <div><dt>暂缓到期</dt><dd>{inspected.snoozedUntil??'未暂缓'}</dd></div>
    </dl>
    {fbaRule(inspected.ruleCode)&&<button type="button" className="refresh-button"
     onClick={()=>{onOpenFbaException(inspected.marketplaceId,inspected.sourceKey);
      setInspectedFindingId('')}}>返回 FBA 原处理单核对</button>}
    <p className="v179-evidence-warning">该列表只是规则命中和工作队列。DONE 不是质检放行、
     库存入库或财务结算；请在有权限的原处理单验证后再依业务流程处理。</p>
   </>}
  </SourceEvidenceDrawer>
 </>
}
