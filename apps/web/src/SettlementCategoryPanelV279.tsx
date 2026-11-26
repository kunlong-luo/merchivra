import {useState} from 'react'
 import {categories,categoryLabels,rowsFromVisibleLines,editorMappings,saveMapping,loadMapping,
 previewMapping,readCategoryReview,writeCategoryReview,CategoryHttpError,
 type CategoryType,type EditorRow,type Draft,type Preview,type Review} from './lib/settlement-category'

const states:Record<string,string>={
 SOURCE_COMPARISON_HOLD:'来源核对尚未满足条件',
 CATEGORY_POPULATION_UNBOUNDED:'来源规模超出预检上限',
 CATEGORY_POPULATION_INCOMPLETE:'来源明细不完整',
 CATEGORY_MAPPING_INCOMPLETE:'映射覆盖不完整',
 ORDER_CATEGORY_AMOUNT_DIFFERENCE:'订单分类金额不一致',
 LOCAL_CATEGORY_AMOUNTS_AGREE_UNVERIFIED:'本地分类金额一致，尚未经外部核证'
}
const blank=():EditorRow=>({transactionType:'',amountType:'',amountDescription:'',category:''})
export default function SettlementCategoryPanelV279({marketplaceId,reportId,sourceTypes,lineCount}:{
 marketplaceId:string;reportId:string;sourceTypes:readonly CategoryType[];lineCount:number
}){
 const [rows,setRows]=useState<EditorRow[]>(()=>rowsFromVisibleLines(sourceTypes))
 const [lookup,setLookup]=useState('')
 const [draft,setDraft]=useState<Draft|null>(null)
 const [preview,setPreview]=useState<Preview|null>(null)
 const [review,setReview]=useState<Review|null>(null)
 const [reason,setReason]=useState('')
 const [busy,setBusy]=useState(false)
 const [message,setMessage]=useState('')
 const [error,setError]=useState('')
 const [readChecked,setReadChecked]=useState(false)
 const reset=()=>{setDraft(null);setPreview(null);setReview(null);setReadChecked(false);setError('');setMessage('')}
 const change=(i:number,patch:Partial<EditorRow>)=>{reset();setRows(prev=>prev.map((row,n)=>n===i?{...row,...patch}:row))}
 const run=async(work:()=>Promise<void>)=>{
  if(busy)return
  setBusy(true);setError('');setMessage('')
  try{await work()}catch(e){
   setReview(null);setReadChecked(false);setPreview(null)
   setError(e instanceof CategoryHttpError?e.message:'操作未完成，请检查输入或服务响应后重试。')
  }finally{setBusy(false)}
 }
 const adopt=(d:Draft)=>{
  setDraft(d);setLookup(d.mappingSha256);setPreview(null);setReview(null);setReadChecked(false)
  setRows(d.mappings.map(m=>({transactionType:m.reportType.transactionType,
   amountType:m.reportType.amountType??'',amountDescription:m.reportType.amountDescription??'',category:m.category})))
 }
 const recompute=async(d:Draft)=>{
  setReview(null);setReadChecked(false);setPreview(null)
  const next=await previewMapping(marketplaceId,reportId,d.mappingSha256)
  setPreview(next)
  if(next.evidenceSha256){
   try{setReview(await readCategoryReview(marketplaceId,reportId,d.mappingSha256,next.evidenceSha256))}
   catch(e){if(!(e instanceof CategoryHttpError)||e.status!==409)throw e
    setMessage('当前没有有效审核记录（可能尚未申请或记录已失效），如需申请请按当前证据提交。')}
   setReadChecked(true)
  }
 }
 const eligible=reason.trim().length>=10&&reason.length<=500&&!/[\x00-\x1f\x7f]/.test(reason)
 const evidence=preview?.evidenceSha256
 const submit=(action:'request'|'approve')=>void run(async()=>{
  if(!draft||!evidence)return
  const result=await writeCategoryReview(marketplaceId,reportId,draft.mappingSha256,evidence,action,reason)
  setReview(result);setReason('');setMessage('已保存本地审核记录，未激活会计分类。')
 })
 return <section className="core-tools" aria-label="费用分类核对与审核">
  <h3>费用分类核对与审核</h3>
  <p className="setup-hint">显式选择 Reports 原始类型组合，Finances 使用当前已配置的 V28 规则。
   本地审核不代表银行到账、会计批准或实际利润。请勿在理由中填写敏感资料。</p>
  <p>已从服务端完整读取 {lineCount} 行的 {sourceTypes.length} 个原始类型组合，不受明细表前 100 行限制。
   每个组合的行数已核对；最终仍由后端重新验证金额及证据。
   空白费用类型或描述表示原始 NULL；不自动猜测分类。</p>
  <fieldset disabled={busy}>
   <legend>报告类型映射（最多 200 项）</legend>
   <div className="table-scroll" role="region" tabIndex={0} aria-label="显式分类映射">
    <table><thead><tr><th scope="col">交易类型</th><th scope="col">金额类型</th><th scope="col">原始描述</th>
     <th scope="col">财务类别</th><th scope="col">操作</th></tr></thead>
     <tbody>{rows.map((row,i)=><tr key={i}>
      <td><input aria-label={'交易类型 '+(i+1)} maxLength={100} value={row.transactionType}
       onChange={e=>change(i,{transactionType:e.target.value})}/></td>
      <td><input aria-label={'金额类型 '+(i+1)} maxLength={100} value={row.amountType}
       onChange={e=>change(i,{amountType:e.target.value})}/></td>
      <td><input aria-label={'原始描述 '+(i+1)} maxLength={160} value={row.amountDescription}
       onChange={e=>change(i,{amountDescription:e.target.value})}/></td>
      <td><select aria-label={'财务类别 '+(i+1)} value={row.category} onChange={e=>change(i,{category:e.target.value})}>
       <option value="">请选择</option>{categories.map(c=><option key={c} value={c}>{categoryLabels[c]}</option>)}
      </select></td><td><button type="button" className="text-button"
       onClick={()=>{reset();setRows(v=>v.filter((_,n)=>n!==i))}}>删除</button></td>
     </tr>)}</tbody>
    </table>
   </div>
   <div className="sandbox-actions">
    <button type="button" className="refresh-button" disabled={rows.length>=200}
     onClick={()=>{reset();setRows(v=>[...v,blank()])}}>添加类型组合</button>
    <button type="button" className="text-button" onClick={()=>{reset();setRows(rowsFromVisibleLines(sourceTypes))}}>重置为完整类型（清空编辑）</button>
    <button type="button" className="primary-button" disabled={!rows.length||rows.some(r=>!r.category||!r.transactionType)}
     onClick={()=>void run(async()=>{reset();const d=await saveMapping(marketplaceId,reportId,editorMappings(rows));adopt(d);await recompute(d)})}>保存草稿并核算</button>
   </div>
   <label htmlFor="category-mapping-lookup">加载已保存映射 SHA-256（另一位 Owner 使用同一版本）</label>
   <input id="category-mapping-lookup" maxLength={64} value={lookup} onChange={e=>{reset();setLookup(e.target.value)}}/>
   <button type="button" className="refresh-button" disabled={!/^[0-9a-f]{64}$/.test(lookup)}
    onClick={()=>void run(async()=>{reset();const d=await loadMapping(marketplaceId,reportId,lookup);adopt(d);await recompute(d)})}>加载版本并核算</button>
   {draft&&<><p>映射版本：<code>{draft.mappingSha256}</code></p>
    <button type="button" className="refresh-button" onClick={()=>void run(()=>recompute(draft))}>重新核算当前证据</button></>}
   {preview&&<div className="core-tools">
    <p role="status"><strong>{states[preview.preview.state]??preview.preview.state}</strong></p>
    <p>未映射 Reports 行 {preview.preview.unmappedReportRows} · 未分类 Finances 末级 {preview.preview.unmappedFinanceLeaves} ·
      未使用映射 {preview.preview.unusedMappings} · 差异分组 {preview.preview.mismatchedBuckets}</p>
    {preview.preview.categoryTotals.length>0&&<div className="table-scroll" role="region" aria-label="分类原币合计" tabIndex={0}>
     <table><thead><tr><th>类别</th><th>方向</th><th>Reports</th><th>Finances</th></tr></thead>
      <tbody>{preview.preview.categoryTotals.map(c=><tr key={c.category+':'+c.direction}>
       <td>{categoryLabels[c.category]}</td><td>{c.direction===1?'正向':c.direction===-1?'负向':'零额'}</td>
       <td>{c.reports===null?'缺失':preview.preview.currency+' '+c.reports}</td>
       <td>{c.finances===null?'缺失':preview.preview.currency+' '+c.finances}</td></tr>)}</tbody>
     </table></div>}
    <p className="setup-hint">分类总额可能掩盖订单级差异，请以差异分组数为准。</p>
    {evidence&&<><p>当前证据：<code>{evidence}</code></p>
     {review&&<p role="status">{review.approvedBy?'独立审核已记录（未激活）':'等待独立 Owner 审核'} · 申请人 {review.requestedBy}
      {review.approvedBy&&' · 审批人 '+review.approvedBy}</p>}
     <label htmlFor="category-review-reason">审核理由（10–500 字）</label>
     <textarea id="category-review-reason" rows={3} maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/>
     <div className="sandbox-actions">
      <button type="button" className="refresh-button" disabled={!eligible||!readChecked||!!review}
       onClick={()=>submit('request')}>提交分类审核</button>
      <button type="button" className="primary-button" disabled={!eligible||!readChecked||review?.state!=='LOCAL_CATEGORY_REVIEW_REQUESTED'}
       onClick={()=>submit('approve')}>独立 Owner 批准</button>
     </div>
     <p className="setup-hint">服务端验证申请人、草稿创建人及独立审批人。过期或撤销后需重新核算；同证据撤销后的重开尚未实现。</p>
    </>}
   </div>}
  </fieldset>
  {busy&&<p role="status">正在处理当前分类证据…</p>}
  {message&&<p role="status">{message}</p>}
  {error&&<p className="setup-error" role="alert">{error}</p>}
 </section>
}
