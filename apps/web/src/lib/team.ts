import { z } from 'zod'
import { csrfHeader } from './auth'

const memberSchema = z.object({
  userId:z.string().uuid(), loginName:z.string(), status:z.enum(['ACTIVE','REVOKED']),
  reviewer:z.boolean(),role:z.enum(['OWNER','OPERATOR_READ']).default('OWNER'),sellerAccountId:z.string().uuid().nullable().optional()
})
const invitationSchema = z.object({
  id:z.string().uuid(),loginName:z.string(),
  status:z.enum(['PENDING','ACCEPTED','REVOKED']),expiresAt:z.string(),role:z.enum(['OWNER','OPERATOR_READ']).default('OWNER'),sellerAccountId:z.string().uuid().nullable().optional()
})
const issuedSchema = z.object({
  invitationId:z.string().uuid(),loginName:z.string(),token:z.string().min(40),
  expiresAt:z.string()
})
export type TeamMember = z.infer<typeof memberSchema>
export type TeamInvitation = z.infer<typeof invitationSchema>
export type IssuedInvite = z.infer<typeof issuedSchema>

async function api(path:string,method:'GET'|'POST'='GET',body?:unknown) {
  const response = await fetch('/api/v1/'+path,{
    method, credentials:'same-origin',cache:'no-store',
    ...(method==='POST' ? {
      headers:{ ...(await csrfHeader()), ...(body ? {'Content-Type':'application/json'} : {}) },
      ...(body ? {body:JSON.stringify(body)} : {})
    }: {})
  })
  if(response.status===404) throw new Error('团队管理未启用或记录不可访问')
  if(response.status===403) throw new Error('缺少管理员权限或操作被拒绝')
  if(response.status===409) throw new Error('数据已经变化，请刷新后重试')
  if(!response.ok) throw new Error('团队操作失败（HTTP '+response.status+'）')
  return response.status===204 ? null : response.json()
}

export const listTeamMembers = async () => z.array(memberSchema).parse(await api('team/members'))
export const listTeamInvites = async () => z.array(invitationSchema).parse(await api('team/invitations'))
export const createTeamInvite = async (loginName:string,role:'OWNER'|'OPERATOR_READ'='OWNER',sellerAccountId?:string) =>
  issuedSchema.parse(await api('team/invitations','POST',{loginName,role,sellerAccountId:sellerAccountId ?? null}))
export const revokeTeamInvite = async (id:string) =>
  api('team/invitations/'+encodeURIComponent(id)+'/revoke','POST')
export const setTeamReviewer = async (id:string,enabled:boolean) =>
  api('team/members/'+encodeURIComponent(id)+'/reviewer','POST',{enabled})
export const revokeTeamOwner = async (id:string) =>
  api('team/members/'+encodeURIComponent(id)+'/revoke','POST')
export const acceptTeamInvitation = async (token:string,password:string) =>
  api('auth/invitations/accept','POST',{token,password})
