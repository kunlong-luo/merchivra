import {useEffect,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalReturnIntake} from './lib/customer-return-intakes'
import {canonicalWmsObservation,importWmsObservation,readWmsReconciliation}
 from './lib/wms-return-reconciliation'
import type {WmsObservedCondition} from './lib/wms-return-reconciliation'
import WmsQcReviewPanel from './WmsQcReviewPanel'

const safe=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/
const states:Record<string,string>={
 NO_EVIDENCE:'尚无外部签名观察证据',
 MATCHED_UNVERIFIED:'签名来源数量、处置一致（运营商/实收未认证）',
 QUANTITY_MISMATCH:'外部数量与本地核对不符',
 CONDITION_MISMATCH:'外部处置与本地核对不符',
 BOTH_MISMATCH:'外部数量和处置均不一致'
}
const holds:Record<string,string>={
 PENDING_EXTERNAL_EVIDENCE:'缺少来源证据',
 STALE_LOCAL_HOLD:'退款/本地证据已过期，暂停采信',
 REVOKED_SOURCE_HOLD:'来源公钥已撤销，暂停采信',
 MISMATCH_QUARANTINE_HOLD:'差异隔离，禁止库存和 COGS',
 IDENTITY_UNVERIFIED_HOLD:'两侧数据一致，但来源运营商仍未独立认证'
}
/** Displays signed discrepancies; NEVER claims true warehouse operator identity. */
export default function WmsReturnReconciliationPanel({tenantId,mp,event,selected,
 sourceId,sourceApproved}:{tenantId:string;mp:string;event:string;
 selected:LocalReturnIntake;sourceId:string;sourceApproved:boolean}) {
 const qc=useQueryClient()
 const [quantity,setQuantity]=useState(selected.quantity)
 const [condition,setCondition]=useState<WmsObservedCondition>(selected.condition)
 const [externalRef,setExternalRef]=useState('')
 const [observedAt,setObservedAt]=useState(new Date().toISOString())
 const [signature,setSignature]=useState('')
 useEffect(()=>{setQuantity(selected.quantity);setCondition(selected.condition);
  setExternalRef('');setSignature('')},[selected.id,selected.quantity,selected.condition])
 const queryKey=['finance','wms-reconcile-v174',tenantId,mp,event,selected.id]
 const reconciliation=useQuery({queryKey,
  queryFn:()=>readWmsReconciliation(mp,event,selected.id),retry:false})
 const mutation=useMutation({mutationFn:()=>importWmsObservation(mp,event,selected.id,{
  sourceId,externalReceiptId:externalRef,observedAt,
  externalQuantity:quantity,externalCondition:condition,signatureBase64:signature}),
  onSuccess:()=>{setSignature('');void qc.invalidateQueries({queryKey})}})
 const text=sourceId&&safe.test(externalRef)&&Number.isInteger(quantity)&&
  quantity>=1&&quantity<=1000000
  ?canonicalWmsObservation(tenantId,mp,event,selected,sourceId,externalRef,
   quantity,condition,observedAt):''
 const canSubmit=selected.status==='ATTESTED_LOCAL'&&selected.evidenceCurrent&&
  sourceApproved&&!!text&&signature.length>=80&&signature.length<=120&&
  reconciliation.data?.reconciliation==='NO_EVIDENCE'
 return <section className="core-controls" aria-label="V174 外部仓储签名数量和处置对账">
  <h4>V174 · 外部签名实收观察与差异隔离</h4>
  <p className="setup-hint">只有合作方私钥可签署外部观察数据。外部报告件数和处置可以与 V172 本地记录不同；系统保留签名原文摘要、计算差异并隔离，**不允许覆盖本地数字或直接冲减 COGS**。</p>
  {reconciliation.data&&<div className="setup-hint">
   <p><strong>当前核对：</strong>{states[reconciliation.data.reconciliation]||
    reconciliation.data.reconciliation}</p>
   <p><strong>财务冻结：</strong>{holds[reconciliation.data.operationalHold]||
    reconciliation.data.operationalHold}</p>
   {reconciliation.data.externalReceiptId&&<p>
    来源回执 {reconciliation.data.externalReceiptId}，
    本地 {reconciliation.data.localQuantity} 件，
    外部 {reconciliation.data.externalQuantity} 件，
    件数差 {reconciliation.data.quantityDelta}。
    仓储运营商身份、物理实收和 COGS 回转始终未核准。
   </p>}
  </div>}
  {reconciliation.error&&<p role="alert" className="setup-error">
   {String(reconciliation.error)}</p>}
  <label htmlFor="v174-external-id">外部签名回执唯一编号</label>
  <input id="v174-external-id" value={externalRef}
   onChange={e=>setExternalRef(e.target.value)} maxLength={160}
   placeholder="WMS-OBS-20261117-001"/>
  <label htmlFor="v174-external-qty">外部签名报告件数</label>
  <input id="v174-external-qty" type="number" min={1} max={1000000}
   value={quantity} onChange={e=>setQuantity(Number(e.target.value))}/>
  <label htmlFor="v174-external-condition">外部报告的可售状态（待认证）</label>
  <select id="v174-external-condition" value={condition}
   onChange={e=>setCondition(e.target.value as WmsObservedCondition)}>
   <option value="UNKNOWN">未知</option>
   <option value="SELLABLE_CLAIMED">外部声称可售</option>
   <option value="UNSELLABLE_CLAIMED">外部声称不可售</option>
  </select>
  <label htmlFor="v174-observed">外部观察时刻（ISO UTC）</label>
  <input id="v174-observed" value={observedAt}
   onChange={e=>setObservedAt(e.target.value)}/>
  <label htmlFor="v174-payload">V174 固定 V2 UTF-8 签名载荷（含末尾换行）</label>
  <textarea id="v174-payload" value={text} readOnly rows={10}
   aria-label="V174 外部观察需签名的固定文本"/>
  <label htmlFor="v174-signature">外部私钥产生的 Ed25519 Base64 签名</label>
  <textarea id="v174-signature" value={signature} rows={2}
   maxLength={120} onChange={e=>setSignature(e.target.value)}/>
  <button className="refresh-button" type="button"
   disabled={!canSubmit||mutation.isPending||reconciliation.isLoading}
   onClick={()=>mutation.mutate()}>验签并保存不可变外部差异观察</button>
  {mutation.error&&<p className="setup-error" role="alert">{String(mutation.error)}</p>}
  <WmsQcReviewPanel tenantId={tenantId} mp={mp} event={event} selected={selected}
   sourceAvailable={!!reconciliation.data?.signatureValid}/>
  <p className="panel-caption">回执身份跨 V173/V174 和已轮换公钥全局防重。同一个 V172 包裹只可占用一次外部回执。签名有效仅证明租户登记密钥持有人，不证明货物实际入库或真实利润。</p>
 </section>
}
