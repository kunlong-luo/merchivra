import {useMemo,useState,type FormEvent} from 'react'
import {useMutation,useQueryClient} from '@tanstack/react-query'
import ManualCostProofDossierPanelV230 from './ManualCostProofDossierPanelV230'
import LocalDocumentCheckPanelV231 from './LocalDocumentCheckPanelV231'
import {
 manualCostCategories,manualCostKinds,
 previewManualCostBatch,stageManualCostBatchV228,
 type ManualCostSource
} from './lib/manual-cost-batch-v228'

const categoryLabel:Record<string,string>={
 ADS_SPEND:'广告费用',INBOUND_FREIGHT:'头程运输',
 FBA_STORAGE:'FBA 仓储',FBM_SHIPPING:'自发货运费',
 RETURN_HANDLING:'退货处理',IMPORT_DUTY:'进口关税',
 PAYMENT_AND_FX:'支付／汇兑',OTHER_OPERATING:'其他经营费用'
}
const kindLabel:Record<string,string>={
 MANUAL_INVOICE:'手工发票引用',MANUAL_CARRIER:'手工物流引用',
 MANUAL_OTHER:'其他手工引用'
}
const reviewLabel:Record<string,string>={
 STAGED:'仅暂存 · 待独立审核',
 REVIEWED_UNVERIFIED:'内部已审核 · 仍未外部核证',
 REJECTED:'已拒绝 · 不得计入真实利润'
}

/** V228 only stages manual V91 facts. No verified cost/profit read/write. */
export default function ManualCostBatchPanelV228({
 tenantId,marketplaceId,currency
}:{tenantId:string;marketplaceId:string;currency:string}){
 const cache=useQueryClient()
 const [input,setInput]=useState('')
 const [error,setError]=useState('')
 const [results,setResults]=useState<ManualCostSource[]|null>(null)
 const [proofSourceId,setProofSourceId]=useState<string|null>(null)
 const preview=useMemo(()=>previewManualCostBatch(input,currency),
  [input,currency])
 const submitting=useMutation({
  mutationFn:()=>stageManualCostBatchV228(marketplaceId,currency,preview.rows),
  onMutate:()=>{setError('');setResults(null);setProofSourceId(null)},
  onSuccess:records=>{
   setResults(records);setError('')
   void cache.invalidateQueries({queryKey:['finance',tenantId]})
  },
  onError:(e:Error)=>{
   setResults(null)
   setError(e.message.includes('409')||e.message.includes('状态变化')
    ?'整批未确认：已有相同来源编号但金额／日期／摘要等原始事实冲突。服务端按整批回滚处理；请逐行核对，不得认为部分成功。'
    :e.message)
  }
 })
 const submit=(event:FormEvent)=>{
  event.preventDefault()
  if(!preview.errors.length&&preview.rows.length&&!submitting.isPending)
   submitting.mutate()
 }
 const edit=(next:string)=>{setInput(next);setError('');setResults(null);setProofSourceId(null)}
 return <section className="panel full-panel"
   aria-label="人工费用来源批量暂存（未核验，绝不入账）">
  <div className="panel-heading"><div>
   <span className="v180-section-kicker">V228 / MANUAL COST · UNVERIFIED</span>
   <h2>批量暂存费用原始引用（最多 20 条）</h2>
   <p>只创建本地 V91 原币费用参考，不拉取 Amazon/Ads，不自动审核、
    不分配 COGS，也不计入已实现净利润。</p>
  </div></div>
  <p className="v179-evidence-warning" role="note">
   <strong>MANUAL_UNVERIFIED · 非真实平台／银行费用凭证。</strong>
   每条原始来源和审核记录单独保存；完全相同的来源重放返回原 ID。
   已存在来源发生金额、业务日期或 SHA-256 冲突时，整个批次不应提交。
  </p>
  <details className="core-tools">
   <summary><strong>查看可填写字段与格式规则</strong>
    <span>从表格复制 6 列，以制表符分隔，不上传文件</span></summary>
   <p className="setup-hint">依次为：类别、来源类型、来源编号、
    声明的原文件 SHA-256（小写 64 位）、业务日期 YYYY-MM-DD、
    原币正数金额（最多六位小数）。当前 Marketplace 原币固定为
    <strong> {currency}</strong>，禁止跨币种转换。</p>
   <p className="setup-hint">类别：{manualCostCategories.map(x=>
    x+'（'+categoryLabel[x]+'）').join('、')}</p>
   <p className="setup-hint">来源类型：{manualCostKinds.map(x=>
    x+'（'+kindLabel[x]+'）').join('、')}</p>
   <p className="setup-hint">SHA-256 只能是你真实持有文件的声明摘要。
    不要使用示例随机值、空白摘要或测试资料伪装为外部证据。</p>
  </details>
  <form onSubmit={submit} className="core-tools">
   <label htmlFor="v228-manual-cost-lines">
    粘贴费用数据（TSV，逐行 6 列，不含表头；1–20 条）
   </label>
   <textarea id="v228-manual-cost-lines" rows={7}
     spellCheck={false} autoComplete="off" maxLength={12000}
     value={input} onChange={e=>edit(e.target.value)}
     placeholder="从表格复制：类别 ⇥ 来源类型 ⇥ 来源编号 ⇥ SHA-256 ⇥ 日期 ⇥ 金额"
     aria-invalid={preview.errors.length>0&&input.trim().length>0}
     aria-describedby="v228-manual-cost-rules"/>
   <p id="v228-manual-cost-rules" className="setup-hint">
    当前站点仅使用 {currency} 原币。禁止粘贴真实密码、授权令牌、
    银行账户、客户信息或发票全文。客户端先逐行核验，
    服务端仍独立执行 Owner/CSRF、原币与唯一归属检查。</p>
   {preview.errors.length>0&&input.trim().length>0&&
    <div role="alert" className="setup-error">
     <strong>本批不可提交 · 修正后再试</strong>
     <ul>{preview.errors.slice(0,20).map((e,i)=><li key={i}>{e}</li>)}</ul>
    </div>}
   {preview.errors.length===0&&preview.rows.length>0&&<>
    <p className="setup-hint" role="status">
     本批可检查 {preview.rows.length} 条（1–20） · {currency} 原币 ·
     本地 STAGED/MANUAL_UNVERIFIED，未调用任何真实第三方 API。</p>
    <div className="table-scroll" role="region" tabIndex={0}
      aria-label="待暂存费用原币逐条预览">
     <table><thead><tr>{['行','费用类别','来源编号','日期',
      '原币金额','摘要校验'].map(h=><th scope="col" key={h}>{h}</th>)}
     </tr></thead><tbody>{preview.rows.map((r,i)=><tr
      key={r.sourceKind+'|'+r.sourceKey}>
      <td>{i+1}</td><td>{categoryLabel[r.category]??r.category}</td>
      <td><span>{kindLabel[r.sourceKind]??r.sourceKind}</span>
       <div className="secondary-cell">{r.sourceKey}</div></td>
      <td>{r.businessDate}</td>
      <td>{r.currency} {r.amount}</td>
      <td title={r.documentSha256}>{r.documentSha256.slice(0,10)}… · 声明摘要</td>
     </tr>)}</tbody></table></div>
   </>}
   <div className="sandbox-actions">
    <button className="primary-button" type="submit"
     disabled={submitting.isPending||preview.errors.length>0||
      preview.rows.length===0||!marketplaceId}>
     {submitting.isPending?'正在进行整批暂存…':
      '暂存 '+preview.rows.length+' 条未核验来源'}
    </button>
    <button type="button" className="refresh-button"
     disabled={submitting.isPending||!input}
     onClick={()=>edit('')}>清空输入</button>
   </div>
  </form>
  {error&&<p role="alert" className="setup-error">
   <strong>批次未确认成功：</strong>{error}。
   不要认为已成功提交其中任何单行；保留原文并核对后重试。</p>}
  {results&&<section className="core-tools" aria-label="批量暂存返回的来源身份">
   <h3>已返回 {results.length} 条来源身份 · 未核验</h3>
   <p role="status" className="setup-hint">
    服务端返回原 UUID；完全一致的历史来源会原样重放，
    因此返回 {results.length} 条不代表新创建 {results.length} 条。
    无外部真伪核验、无财务过账、无真实净利润。</p>
   <div className="table-scroll" role="region" tabIndex={0}
     aria-label="安全暂存的费用来源身份和审核状态">
    <table><thead><tr>{['来源编号','来源 UUID','业务日期',
      '金额','本地审核状态','外部核验','证据缺口'].map(h=><th key={h}>{h}</th>)}
    </tr></thead><tbody>{results.map(r=><tr key={r.id}>
     <td>{r.sourceKey}</td><td title={r.id}>
      <code>{r.id.slice(0,8)}…</code></td>
     <td>{r.businessDate}</td><td>{r.currency} {r.amount}</td>
     <td>{reviewLabel[r.state]??r.state}</td><td>未核验</td>
     <td><button type="button" className="refresh-button"
      onClick={()=>setProofSourceId(r.id)}
      aria-label={'查看来源 '+r.sourceKey+' 的外部凭证缺口'}>
      查看缺口</button></td>
    </tr>)}</tbody></table></div>
   {proofSourceId&&results.some(r=>r.id===proofSourceId)&&<>
    <ManualCostProofDossierPanelV230 key={'proof-'+proofSourceId}
     tenantId={tenantId} marketplaceId={marketplaceId}
     sourceId={proofSourceId} currency={currency}/>
    <LocalDocumentCheckPanelV231 key={'bytes-'+proofSourceId}
     sourceId={proofSourceId}
     claimedSha256={results.find(r=>r.id===proofSourceId)?.documentSha256??''}/>
   </>}
  </section>}
 </section>
}
