import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {fetchCostLineIdentity,fetchCostLineLinks} from './lib/cost-line-identity'

/**
 * V181: read-only V94/V102 audit lineage, never proof of true duplicate debit.
 * Mounted only for a selected manual cost source already in a tenant-scoped
 * V162 page. All requests use same-origin authenticated Spring Session.
 */
export default function CostLineIdentityPanel({tenantId,marketplaceId,sourceId}:{
 tenantId:string;marketplaceId:string;sourceId:string
}){
 const [linkId,setLinkId]=useState('')
 const scope=['finance','manual-cost-identity',tenantId,marketplaceId,sourceId]
 const links=useQuery({queryKey:[...scope,'links'],
  queryFn:()=>fetchCostLineLinks(marketplaceId,sourceId),retry:false})
 const selected=(links.data??[]).some(l=>l.id===linkId)?linkId:''
 const identity=useQuery({queryKey:[...scope,'v102',selected],
  queryFn:()=>fetchCostLineIdentity(marketplaceId,sourceId,selected),
  enabled:!!selected,retry:false})
 return <section className="v181-line-identity" aria-label="费用来源冻结结算行身份">
  <h4 className="v179-evidence-section">结算报告行身份 · V181</h4>
  <p className="setup-hint">关联的是既有 V94 的导入行；V102 只对之后创建的关联冻结
   报告 ID、文档摘要和行级交易字段。旧关联没有原始拍摄快照，不能后来补造。</p>
  {links.isPending?<p className="setup-hint">读取本来源已关联的结算报告行…</p>:
   links.isError?<p role="alert" className="setup-error">
    {String(links.error)}</p>:
   !links.data?.length?<p className="setup-hint">尚无本地报告行关联；
    没有关联不代表该笔费用为零或没有重复。</p>:
   <div className="v181-line-list">
    {links.data.map(l=><button key={l.id} type="button"
      className="v181-line-option" aria-pressed={selected===l.id}
      onClick={()=>setLinkId(l.id)}>
      <strong>第 {l.lineNumber} 行 · {l.lineCurrency} {l.signedLineAmount}</strong>
      <span>关联 {l.similarity} · {l.state}</span>
     </button>)}
   </div>}
  {selected&&(identity.isPending?<p role="status" className="setup-hint">
   核对冻结的导入行身份…</p>:
   identity.isError?<p role="alert" className="setup-error">
    {String(identity.error)}</p>:
   identity.data&&<div className="v181-line-identity-result">
    {identity.data.captureStatus==='CAPTURE_NOT_AVAILABLE'?
     <p className="v179-evidence-warning" role="status">
      历史 V94 关联无 V102 创建时快照。不能使用当前报告行数据补造历史证据，
      也不能视为已核验费用。</p>:
     <>
      <div className="v179-evidence-tags"><span className="v179-source-tag">
       已冻结导入行元数据 · 外部真伪未认证</span></div>
      <dl className="v179-evidence-facts">
       <div><dt>Amazon 报告标识（导入记录）</dt>
        <dd><code>{identity.data.amazonReportId}</code></dd></div>
       <div><dt>报告文档标识</dt><dd><code>{identity.data.reportDocumentId}</code></dd></div>
       <div><dt>导入文档 SHA-256</dt><dd><code>{identity.data.reportDocumentSha256}</code></dd></div>
       <div><dt>来源行复合键</dt><dd><code>{identity.data.reportId} / {identity.data.lineNumber}</code></dd></div>
       <div><dt>交易种类 / 金额种类</dt><dd>
        {identity.data.transactionType??'来源未知'} / {identity.data.amountType??'来源未知'}</dd></div>
       <div><dt>费用描述</dt><dd>{identity.data.amountDescription??'来源未知'}</dd></div>
       <div><dt>关联订单参考</dt><dd><code>{
        identity.data.externalOrderId??'来源未提供'}</code></dd></div>
       <div><dt>原币行金额</dt><dd>
        {identity.data.currency} {identity.data.lineSignedAmount}</dd></div>
       <div><dt>本地捕获时间</dt><dd>{identity.data.capturedAt}</dd></div>
      </dl>
     </>}
    <p className="v179-evidence-warning">
     此记录只能证明“本地关联了哪条已导入的结算行、当时读到了什么”。
     即使 SHA-256 和金额一致，也没有证明跨系统外部来源身份、重复扣费、
     真实银行到账、费用可扣除性或最终净利润。所有认证标志始终为否。</p>
   </div>)}
 </section>
}
