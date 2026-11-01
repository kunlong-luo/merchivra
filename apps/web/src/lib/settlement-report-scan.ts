import {z} from 'zod'
import {csrfHeader} from './auth'

const state=z.enum(['NOT_STARTED','READY','RUNNING','RETRY','SUCCEEDED','PAUSED'])
export const reportScanStatus=z.object({
  jobId:z.string().uuid().nullable(),state,
  generation:z.number().int().nonnegative(),completedPages:z.number().int().nonnegative(),
  importedReports:z.number().int().nonnegative(),retryCount:z.number().int().nonnegative(),
  rowOffset:z.number().int().nonnegative(),
  nextAttemptAt:z.string().nullable(),lastFailureCode:z.string().nullable(),
  updatedAt:z.string().nullable()
})
export type ReportScanStatus=z.infer<typeof reportScanStatus>
const base=(mp:string)=>'/api/v1/settlement-report-scan/'+encodeURIComponent(mp)
async function request(mp:string,method:'GET'|'POST',action=''){
  const res=await fetch(base(mp)+action,{
    method,credentials:'same-origin',cache:'no-store',
    headers:{Accept:'application/json',...(method==='POST'?await csrfHeader():{})}
  })
  if(res.status===404)throw new Error('NOT_ENABLED')
  if(res.status===403)throw new Error('当前店铺没有 Owner 权限')
  if(res.status===409)throw new Error('任务正在运行、尚未到重试时间或授权尚不满足')
  if(!res.ok)throw new Error('结算报告发现任务 HTTP '+res.status)
  return reportScanStatus.parse(await res.json())
}
export const getReportScan=(mp:string)=>request(mp,'GET')
export const startReportScan=(mp:string)=>request(mp,'POST','/start')
export const stepReportScan=(mp:string)=>request(mp,'POST','/step')
