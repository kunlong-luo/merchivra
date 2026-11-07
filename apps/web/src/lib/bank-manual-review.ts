import {z} from 'zod'
import {financeAuditPage,financeAuditUrl} from './finance-audit-pages'
import {csrfHeader} from './auth'

export const bankManualReview=z.object({
 state:z.enum(['NOT_REQUESTED','PENDING','REVIEWED','REJECTED','STALE']),
 comparisonState:z.string(),
 requestedBy:z.string().uuid().nullable(),reviewedBy:z.string().uuid().nullable(),
 version:z.number().int().nonnegative(),evidenceCurrent:z.boolean(),
 bankVerified:z.literal(false),ledgerPosted:z.literal(false)
})
export const bankManualReviewAudit=z.object({
 action:z.enum(['REQUESTED','REOPENED','REVIEWED','REJECTED']),
 version:z.number().int().positive(),actorId:z.string().uuid(),
 reason:z.string().min(10).max(500),occurredAt:z.string()
})
export type BankManualReview=z.infer<typeof bankManualReview>
const root=(mp:string,report:string)=>
 '/api/v1/finance/marketplaces/'+encodeURIComponent(mp)+
 '/reports/'+encodeURIComponent(report)+'/manual-bank-review'
async function read<T>(url:string,schema:z.ZodType<T>) {
 const res=await fetch(url,{credentials:'same-origin',cache:'no-store',
  headers:{Accept:'application/json'}})
 if(!res.ok)throw new Error(res.status===409?'银行来源不唯一或尚不符合审核条件':
  res.status===403?'没有当前站点的 Owner 权限':
  '人工银行审核读取失败（HTTP '+res.status+'）')
 return schema.parse(await res.json())
}
export const getBankManualReview=(mp:string,report:string)=>
 read(root(mp,report),bankManualReview)
export const listBankManualAudit=(mp:string,report:string)=>
 read(root(mp,report)+'/audit',z.array(bankManualReviewAudit).max(100))
export async function decideBankManualReview(mp:string,report:string,
 action:'request'|'approve'|'reject',reason:string) {
 if(reason.trim().length<10||reason.length>500||/[\x00-\x1F\x7F]/.test(reason))
  throw new Error('审核原因需 10–500 字且无控制字符')
 const res=await fetch(root(mp,report)+'/'+action,{
  method:'POST',credentials:'same-origin',cache:'no-store',
  headers:{...(await csrfHeader()),'Content-Type':'application/json',
   Accept:'application/json'},
  body:JSON.stringify({reason})
 })
 if(!res.ok)throw new Error(
  res.status===403?'上传人、申请人不能审核自己的银行参考':
  res.status===409?'来源变化、金额不一致或审核已处理，请刷新后检查':
  '人工银行复核失败（HTTP '+res.status+'）')
 return bankManualReview.parse(await res.json())
}

export const listBankManualAuditPage=(mp:string,report:string,cursor:string|null=null)=>
 read(financeAuditUrl(root(mp,report),cursor),financeAuditPage(bankManualReviewAudit))
