import {z} from 'zod'
import {merchivraFetch} from './merchivra-mutator'

const uuid=z.string().uuid()
const root=(mp:string)=>'/api/v1/fba/marketplaces/'+encodeURIComponent(mp)+
 '/purchase-reconciliation/permissions'
export const grantsSchema=z.object({
 view:z.boolean(),assign:z.boolean(),review:z.boolean(),resolve:z.boolean(),
 configure:z.boolean(),manage:z.boolean()
})
export const exceptionProfileSchema=z.object({
 ownerId:uuid,displayName:z.string(),grants:grantsSchema,
 version:z.number().int().nonnegative(),customized:z.boolean()
})
export type ExceptionGrants=z.infer<typeof grantsSchema>
export type ExceptionProfile=z.infer<typeof exceptionProfileSchema>
const auditSchema=z.object({
 ownerId:uuid,actorId:uuid,oldVersion:z.number().int().nonnegative(),
 newVersion:z.number().int().positive(),oldGrants:z.string().nullable(),
 newGrants:z.string().length(6),reason:z.string(),
 occurredAt:z.string().datetime({offset:true})
})
async function read<T>(url:string,schema:z.ZodType<T>) {
 const response=await merchivraFetch<{data:unknown}>(url,{method:'GET'})
 return schema.parse(response.data)
}
export const getMyExceptionGrants=(mp:string)=>read(root(mp)+'/me',exceptionProfileSchema)
export const getExceptionOwners=(mp:string)=>read(root(mp)+'/owners',
 z.array(exceptionProfileSchema).max(100))
export const getExceptionPermissionAudit=(mp:string)=>read(root(mp)+'/audit',
 z.array(auditSchema).max(100))
export const saveExceptionOwnerGrants=async(mp:string,ownerId:string,input:{
 grants:ExceptionGrants;expectedVersion:number;reason:string
})=>{
 const response=await merchivraFetch<{data:unknown}>(
  root(mp)+'/owners/'+encodeURIComponent(ownerId),{
   method:'POST',headers:{'Content-Type':'application/json'},
   body:JSON.stringify(input)
  })
 return exceptionProfileSchema.parse(response.data)
}
