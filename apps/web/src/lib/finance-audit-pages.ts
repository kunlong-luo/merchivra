import {z} from 'zod'

/** One shared immutable 1–100 row case-audit page envelope. */
export const financeAuditPage=<T extends z.ZodType>(audit:T)=>z.object({
 items:z.array(audit).max(100),
 nextCursor:z.string().min(1).nullable(),
 hasMore:z.boolean(),
 pageSize:z.number().int().min(1).max(100)
}).refine(x=>x.hasMore===(x.nextCursor!==null),{
 message:'审核审计页游标与剩余状态不一致'
})
export const financeAuditUrl=(base:string,cursor:string|null=null)=>{
 const params=new URLSearchParams({limit:'50'})
 if(cursor)params.set('cursor',cursor)
 return base+'/audit/page?'+params.toString()
}
