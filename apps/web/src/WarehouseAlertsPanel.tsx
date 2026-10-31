import {useState,type FormEvent} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {Sku} from './lib/catalog'
import {fetchWarehouseAdvice,saveWarehousePolicy,deleteWarehousePolicy,
 fetchWarehouseFindings,scanWarehouseAlerts,changeWarehouseFinding,
 type WarehouseFindingState} from './lib/warehouse-alerts'

const stateNames:Record<string,string>={
 NO_LEDGER:'尚无本地库存流水，无法判断',
 LEDGER_ANOMALY:'台账余额异常（负数），停止建议',
 LOW_STOCK:'触发本地补货阈值',
 HEALTHY:'本地台账未触发阈值'
}
const ruleNames:Record<string,string>={
 LOCAL_LOW_STOCK:'仓库台账低于补货阈值',
 LOCAL_LEDGER_NEGATIVE:'本地仓库台账出现负数',
 LOCAL_COUNT_VARIANCE:'近 7 日盘点差异达到 100 件'
}
const findingNames:Record<string,string>={
 OPEN:'待处理',ACKNOWLEDGED:'已认领',DONE:'人工已处理',CLEARED:'条件已消失'
}
export default function WarehouseAlertsPanel({tenantId,warehouseId,skus}:{
 tenantId:string;warehouseId:string;skus:Sku[]
}){
 const client=useQueryClient()
 const [sku,setSku]=useState(''),[point,setPoint]=useState(3)
 const [target,setTarget]=useState(20)
 const [filter,setFilter]=useState<'ACTIVE'|'ALL'>('ACTIVE')
 const [message,setMessage]=useState(''),[failure,setFailure]=useState('')
 const [editing,setEditing]=useState('')
 const root=['purchasing',tenantId,'warehouse-alerts']
 const advice=useQuery({queryKey:[...root,warehouseId,'advice'],
  queryFn:()=>fetchWarehouseAdvice(warehouseId),retry:false})
 const findings=useQuery({queryKey:[...root,warehouseId,'findings',filter],
  queryFn:()=>fetchWarehouseFindings(warehouseId,filter),retry:false})
 const refresh=()=>void client.invalidateQueries({queryKey:root})
 const save=useMutation({mutationFn:()=>saveWarehousePolicy(warehouseId,sku,point,target),
  onSuccess:()=>{setMessage('仓库 SKU 补货策略已保存');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const remove=useMutation({mutationFn:(id:string)=>deleteWarehousePolicy(warehouseId,id),
  onSuccess:()=>{setMessage('仓库 SKU 策略已移除，下次巡检会清除旧提醒');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const scan=useMutation({mutationFn:scanWarehouseAlerts,
  onSuccess:r=>{setMessage('当前租户仓库巡检完成：匹配 '+r.matchedCount+
    ' 项，清除 '+r.clearedCount+' 项。');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message)})
 const change=useMutation({mutationFn:({id,state}:{id:string;state:WarehouseFindingState})=>
  changeWarehouseFinding(id,state),onMutate:v=>setEditing(v.id),
  onSuccess:()=>{setMessage('告警处理状态已保存');setFailure('');refresh()},
  onError:(e:Error)=>setFailure(e.message),onSettled:()=>setEditing('')})
 function submit(e:FormEvent){e.preventDefault();if(sku)save.mutate()}
 return <section className="panel full-panel" aria-label="本地仓库补货策略与异常待办">
  <div className="panel-heading"><div><h2>本地仓库补货与异常待办</h2>
   <p>仅使用当前仓库已记录的本地库存台账。无流水不等于库存 0；
    未指定仓库的采购待收货仅作参考，不能自动抵扣建议数量，也不会自动采购。</p>
  </div><button type="button" className="refresh-button" onClick={refresh}>刷新</button></div>
  <form className="team-invite-form" onSubmit={submit}>
   <label htmlFor="warehouse-alert-sku">内部 SKU</label>
   <select id="warehouse-alert-sku" value={sku} onChange={e=>setSku(e.target.value)}>
    <option value="">选择内部 SKU</option>
    {skus.filter(s=>s.status==='ACTIVE').map(s=>
     <option key={s.id} value={s.id}>{s.internalSku}</option>)}
   </select>
   <label htmlFor="warehouse-alert-point">本地台账触发阈值（件）</label>
   <input id="warehouse-alert-point" type="number" min={0} max={10000000}
    value={point} onChange={e=>setPoint(Number(e.target.value))}/>
   <label htmlFor="warehouse-alert-target">目标台账数量（件）</label>
   <input id="warehouse-alert-target" type="number" min={1} max={10000000}
    value={target} onChange={e=>setTarget(Number(e.target.value))}/>
   <button type="submit" className="primary-button" disabled={!sku||save.isPending||
    !Number.isSafeInteger(point)||!Number.isSafeInteger(target)||point<0||
    target<=point||target>10000000}>保存本地补货策略</button>
  </form>
  <h3>当前仓库 SKU 补货参考</h3>
  {advice.isPending?<p className="empty-state">读取仓库补货参考…</p>:
   advice.isError?<p className="setup-error" role="alert">{String(advice.error)}</p>:
   !advice.data?.length?<p className="empty-state">当前仓库尚未配置补货阈值。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="本地仓库补货参考表">
    <table><thead><tr><th scope="col">内部 SKU</th><th scope="col">本地台账</th>
     <th scope="col">阈值 / 目标</th><th scope="col">状态</th>
     <th scope="col">建议补充</th><th scope="col">已审批待收货（全仓未分配）</th>
     <th scope="col">操作</th></tr></thead><tbody>
     {advice.data.map(a=><tr key={a.policy.productSkuId}>
      <td>{a.internalSku}</td><td>{a.onHandQuantity??'未知'}</td>
      <td>{a.policy.reorderPoint} / {a.policy.targetStock}</td>
      <td>{stateNames[a.state]??a.state}</td>
      <td>{a.suggestedQuantity===null?'不能计算':a.suggestedQuantity}</td>
      <td>{a.approvedUnreceivedQuantity}（未按仓分配）</td>
      <td><div className="sandbox-actions">
       <button type="button" className="refresh-button" onClick={()=>{
        setSku(a.policy.productSkuId);setPoint(a.policy.reorderPoint);
        setTarget(a.policy.targetStock)}}>编辑</button>
       <button type="button" className="text-button" disabled={remove.isPending}
        onClick={()=>{if(window.confirm('确认移除此仓库的 SKU 补货阈值？'))
         remove.mutate(a.policy.productSkuId)}}>删除</button>
      </div></td>
     </tr>)}
    </tbody></table>
   </div>}
  <div className="panel-heading"><div><h3>仓库异常待办</h3>
   <p>手动巡检当前租户所有本地仓库；也可由部署者单独开启定时扫描。
    已处理的异常不会反复打开，除非条件先消失后再次出现。</p></div>
   <button type="button" className="primary-button" disabled={scan.isPending}
    onClick={()=>scan.mutate()}>{scan.isPending?'巡检中…':'立即巡检本地仓库'}</button></div>
  <label htmlFor="warehouse-alert-filter">待办状态</label>
  <select id="warehouse-alert-filter" value={filter}
   onChange={e=>setFilter(e.target.value as 'ACTIVE'|'ALL')}>
   <option value="ACTIVE">待处理及已认领</option><option value="ALL">全部记录</option>
  </select>
  {findings.isPending?<p className="empty-state">读取仓库待办…</p>:
   findings.isError?<p className="setup-error" role="alert">{String(findings.error)}</p>:
   !findings.data?.length?<p className="empty-state">
    当前筛选范围内没有仓库待办。首次使用请执行巡检。</p>:
   <div className="table-scroll" role="region" tabIndex={0} aria-label="仓储异常待办列表">
    <table><thead><tr><th scope="col">内部 SKU</th><th scope="col">异常类型</th>
     <th scope="col">状态</th><th scope="col">最后识别</th><th scope="col">操作</th>
    </tr></thead><tbody>
     {findings.data.map(f=><tr key={f.id}>
      <td>{f.internalSku}</td><td>{ruleNames[f.ruleCode]??f.ruleCode}</td>
      <td>{findingNames[f.state]??f.state}</td>
      <td>{new Date(f.lastSeenAt).toLocaleString('zh-CN')}</td>
      <td><div className="sandbox-actions">
       {f.state==='OPEN'&&<button type="button" className="refresh-button"
        disabled={!!editing} onClick={()=>change.mutate({id:f.id,state:'ACKNOWLEDGED'})}>
        认领</button>}
       {['OPEN','ACKNOWLEDGED'].includes(f.state)&&
        <button type="button" className="primary-button" disabled={!!editing}
         onClick={()=>change.mutate({id:f.id,state:'DONE'})}>标记已处理</button>}
       {['DONE','ACKNOWLEDGED'].includes(f.state)&&
        <button type="button" className="text-button" disabled={!!editing}
         onClick={()=>change.mutate({id:f.id,state:'OPEN'})}>重新打开</button>}
      </div></td>
     </tr>)}
    </tbody></table>
   </div>}
  {message&&<p className="panel-caption" role="status">{message}</p>}
  {failure&&<p className="setup-error" role="alert">{failure}</p>}
 </section>
}
