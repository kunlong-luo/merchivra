import {useState} from 'react'
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query'
import {issueWmsKeyChallenge,listWmsKeyChallenges,verifyWmsKeyChallenge}
 from './lib/wms-key-possession'

/** Challenge is key control only; never real WMS business/physical attestation. */
export default function WmsKeyPossessionPanel({tenantId,sourceId,approved}:{
 tenantId:string;sourceId:string;approved:boolean
}){
 const cache=useQueryClient()
 const [signature,setSignature]=useState('')
 const key=['finance','wms-key-possession-v175b',tenantId,sourceId]
 const data=useQuery({queryKey:key,enabled:!!sourceId,
  queryFn:()=>listWmsKeyChallenges(sourceId),retry:false})
 const issue=useMutation({mutationFn:()=>issueWmsKeyChallenge(sourceId),
  onSuccess:()=>{setSignature('');void cache.invalidateQueries({queryKey:key})}})
 const active=data.data?.find(x=>x.status==='AWAITING_SIGNATURE')
 const verify=useMutation({mutationFn:()=>verifyWmsKeyChallenge(
  sourceId,active!.id,signature),onSuccess:()=>{
   setSignature('');void cache.invalidateQueries({queryKey:key})}})
 return <section className="core-controls" aria-label="V175-B 公钥持有权挑战">
  <h4>V175-B · 独立公钥持有权挑战</h4>
  <p className="setup-hint">每次挑战包含 256 位随机 nonce、租户/仓库/来源/挑战 ID 和 10 分钟失效时刻。
   将以下 UTF-8 原文（含末尾换行）交由公钥对应的私钥持有人签名，切勿将私钥上传系统。
   另一名独立 Owner 执行验签。**这只证明密钥控制权，不认证真实运营商身份或货物签收。**</p>
  <button className="refresh-button" type="button"
   disabled={!approved||issue.isPending||!!active}
   onClick={()=>issue.mutate()}>创建一次性挑战（最多每日 5 次）</button>
  {data.isError&&<p className="setup-error" role="alert">{String(data.error)}</p>}
  {active&&<>
   <p className="setup-hint">挑战 {active.id}，到期 {active.expiresAtUtc}</p>
   <label htmlFor="v175b-canonical">需要签名的完整规范原文</label>
   <textarea id="v175b-canonical" rows={9} readOnly
    aria-label="规范化公钥挑战内容" value={active.canonicalPayload}/>
   <label htmlFor="v175b-detached">Ed25519 detached 签名 Base64</label>
   <textarea id="v175b-detached" rows={2} maxLength={120} value={signature}
    onChange={e=>setSignature(e.target.value)}/>
   <button className="refresh-button" type="button"
    disabled={!approved||verify.isPending||signature.length<84||signature.length>120}
    onClick={()=>verify.mutate()}>由独立 Owner 验证单次签名</button>
  </>}
  {(issue.error||verify.error)&&<p role="alert" className="setup-error">
   {String(issue.error||verify.error)}</p>}
  {data.data?.map(x=><p className="setup-hint" key={x.id}>
   挑战 {x.id.slice(0,8)}：{x.status==='KEY_POSSESSION_ONLY'?
    '已证明租户登记密钥持有人控制了私钥（非运营商身份）':
    x.status==='AWAITING_SIGNATURE'?'等待签名':
    x.status==='EXPIRED'?'已过期':'来源密钥撤销或未批准'}；
   真实 WMS 身份：未核验；实物收货：未核验；库存/COGS：不允许过账。
  </p>)}
 </section>
}
