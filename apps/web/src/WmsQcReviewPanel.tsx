import {useEffect,useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import type {LocalReturnIntake} from './lib/customer-return-intakes'
import {decideWmsQcReview,readWmsQcReview,requestWmsQcReview}
 from './lib/wms-return-qc-review'
import type {WmsQcCondition} from './lib/wms-return-qc-review'

const refPattern=/^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$/
const holds:Record<string,string>={
 STALE_LOCAL_HOLD:'本地退款/实收证据过期',
 REVOKED_SOURCE_HOLD:'签名来源已撤销',
 QC_REJECTED_HOLD:'独立复核已驳回',
 QC_PENDING_HOLD:'等待另一名 Owner 独立复核',
 QC_SIGNED_MISMATCH_HOLD:'QC 观察与签名数据仍有差异',
 LOCAL_SIGNED_MISMATCH_HOLD:'QC 与签名一致，但与本地记录不一致',
 OPERATOR_IDENTITY_UNVERIFIED_HOLD:'三方数据一致，但仓库运营商身份仍未核验'
}
const comparisons:Record<string,string>={
 QC_MATCHED_UNVERIFIED:'QC 记录与签名观察一致（未证实实际收货）',
 QC_QUANTITY_MISMATCH:'QC 件数与签名观察不一致',
 QC_CONDITION_MISMATCH:'QC 处置与签名观察不一致',
 QC_BOTH_MISMATCH:'QC 件数和处置均不一致'
}
export default function WmsQcReviewPanel({tenantId,mp,event,selected,
 sourceAvailable}:{tenantId:string;mp:string;event:string;
 selected:LocalReturnIntake;sourceAvailable:boolean}) {
 const qc=useQueryClient()
 const [quantity,setQuantity]=useState(selected.quantity)
 const [condition,setCondition]=useState<WmsQcCondition>(selected.condition)
 const [reference,setReference]=useState('')
 const [explanation,setExplanation]=useState('')
 const [reason,setReason]=useState('')
 useEffect(()=>{setQuantity(selected.quantity);setCondition(selected.condition);
  setReference('');setExplanation('');setReason('')},
  [selected.id,selected.quantity,selected.condition])
 const key=['finance','wms-qc-review-v175',tenantId,mp,event,selected.id]
 const review=useQuery({queryKey:key,
  queryFn:()=>readWmsQcReview(mp,event,selected.id),retry:false})
 const create=useMutation({
  mutationFn:()=>requestWmsQcReview(mp,event,selected.id,
   {qcQuantity:quantity,qcCondition:condition,evidenceReference:reference,
    explanation}),
  onSuccess:()=>{void qc.invalidateQueries({queryKey:key})}
 })
 const decide=useMutation({
  mutationFn:(ack:boolean)=>decideWmsQcReview(mp,event,selected.id,
   review.data!.id,ack,reason),
  onSuccess:()=>{setReason('');void qc.invalidateQueries({queryKey:key})}
 })
 const canRequest=!review.isLoading&&!review.data&&sourceAvailable&&
  selected.status==='ATTESTED_LOCAL'&&selected.evidenceCurrent&&
  Number.isInteger(quantity)&&quantity>=1&&quantity<=1000000&&
  refPattern.test(reference)&&explanation.trim().length>=10&&
  explanation.length<=500
 const canDecide=review.data?.state==='PENDING'&&reason.trim().length>=10&&
  reason.length<=500&&!decide.isPending
 return <section className="core-controls" aria-label="V175 人工 QC 差异复核">
  <h4>V175-A · QC 人工观察与独立复核</h4>
  <p className="setup-hint">这是有来源编号的人工 QC 观察，不是已认证的真实仓库实收。
   签名导入人、QC 提交人和最终复核人须是三名不同 Owner。
   “确认已阅读”仅确认复核过该观察，不认证运营商或货物，不触发库存、COGS 和利润变更。</p>
  {review.data?<div className="setup-hint">
   <p><strong>复核状态：</strong>{review.data.state==='PENDING'?'待另一人复核':
    review.data.state==='ACKNOWLEDGED'?'独立复核已确认阅读':'独立复核已驳回'}</p>
   <p><strong>差异分类：</strong>{comparisons[review.data.qcComparison]}</p>
   <p>本地 {review.data.localQuantity} 件，外部签名
    {review.data.externalQuantity} 件，人工 QC {review.data.qcQuantity} 件。
    QC 证据：{review.data.evidenceReference}</p>
   <p><strong>当前冻结原因：</strong>{holds[review.data.operationalHold]}</p>
   {review.data.decisionReason&&<p>复核说明：{review.data.decisionReason}</p>}
  </div>:<p className="setup-hint">尚未登记本次包裹的 V175 QC 复核单。
   只接受 V174 签名观察；历史 V173 签名需保留原证据，不自动升级。</p>}
  {review.error&&<p role="alert" className="setup-error">{String(review.error)}</p>}
  {!review.data&&<>
   <label htmlFor="v175-qc-ref">人工 QC 原始证据编号</label>
   <input id="v175-qc-ref" value={reference} maxLength={160}
    onChange={e=>setReference(e.target.value)} placeholder="QC-OBS-20261117-001"/>
   <label htmlFor="v175-qc-qty">人工观察件数（未经仓库认证）</label>
   <input id="v175-qc-qty" type="number" min={1} max={1000000}
    value={quantity} onChange={e=>setQuantity(Number(e.target.value))}/>
   <label htmlFor="v175-qc-condition">人工观察处置</label>
   <select id="v175-qc-condition" value={condition}
    onChange={e=>setCondition(e.target.value as WmsQcCondition)}>
    <option value="UNKNOWN">未知</option>
    <option value="SELLABLE_CLAIMED">声称可售</option>
    <option value="UNSELLABLE_CLAIMED">声称不可售</option>
   </select>
   <label htmlFor="v175-qc-note">证据说明（10–500 字）</label>
   <textarea id="v175-qc-note" value={explanation} maxLength={500}
    onChange={e=>setExplanation(e.target.value)}/>
   <button className="refresh-button" type="button"
    disabled={!canRequest||create.isPending}
    onClick={()=>create.mutate()}>追加不可变 QC 观察，交由另一 Owner 复核</button>
  </>}
  {review.data?.state==='PENDING'&&<>
   <label htmlFor="v175-qc-decision-reason">独立复核理由（10–500 字）</label>
   <textarea id="v175-qc-decision-reason" value={reason} maxLength={500}
    onChange={e=>setReason(e.target.value)}/>
   <button type="button" className="refresh-button" disabled={!canDecide}
    onClick={()=>decide.mutate(true)}>仅确认已阅读 QC 证据</button>
   <button type="button" className="refresh-button" disabled={!canDecide}
    onClick={()=>decide.mutate(false)}>驳回 QC 观察</button>
  </>}
  {(create.error||decide.error)&&<p role="alert" className="setup-error">
   {String(create.error||decide.error)}</p>}
  <p className="panel-caption">所有结论仍受签名来源撤销、本地证据过期及数量/处置差异动态冻结；
   ACKNOWLEDGED 不等于物理签收、可售入库或历史成本回转。</p>
 </section>
}
