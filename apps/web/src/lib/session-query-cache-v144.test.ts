import {afterEach,describe,expect,it,vi} from 'vitest'
import {QueryClient} from '@tanstack/react-query'
import {purgeBusinessSessionCache} from './session-query-cache'

function client(){return new QueryClient({defaultOptions:{queries:{retry:false}}})}
afterEach(()=>vi.clearAllMocks())

describe('V144 session boundary: all business tenant data must vanish',()=>{
 it('purges every known and unknown module domain, even for the same tenant',async()=>{
  const q=client()
  const tenant='11111111-1111-4111-8111-111111111111'
  const other='22222222-2222-4222-8222-222222222222'
  const viewer={userId:other,activeTenantId:tenant,isOwner:false}
  q.setQueryData(['auth','me'],viewer)
  const cached=[
   ['purchasing',tenant,'warehouse-exact','live-secret'],
   ['purchasing',tenant,'supplier-picker','active','',null],
   ['purchasing',tenant,'stock-item','warehouse','sku'],
   ['catalog',tenant,'for-purchase'],
   ['core',tenant,'orders-v86'],
   ['fba-inbound',tenant,'details'],
   ['operations',tenant,'findings'],
   ['finance',tenant,'payouts'],
   ['team',tenant,'members'],
   ['ads-reports',tenant,'report'],
   ['future-new-module',tenant,'sensitive-query'],
   ['api','ping'],
  ] as const
  for(const key of cached)q.setQueryData([...key],{owner:'former-account'})
  const mutation=q.getMutationCache().build(q,{
   mutationKey:['purchasing',tenant,'dangerous-post']
  })
  mutation.setState({...mutation.state,
   data:{owner:'former-account'},status:'success'})
  expect(q.getQueryCache().getAll().length).toBe(cached.length+1)
  expect(q.getMutationCache().getAll()).toHaveLength(1)
  await purgeBusinessSessionCache(q)
  expect(q.getQueryCache().getAll().map(x=>x.queryKey)).toEqual([['auth','me']])
  expect(q.getQueryData(['auth','me'])).toEqual(viewer)
  for(const key of cached)expect(q.getQueryData([...key])).toBeUndefined()
  expect(q.getMutationCache().getAll()).toHaveLength(0)
  q.clear()
 })

 it('cancels in-flight prior-identity results before they can repopulate queries',async()=>{
  const q=client()
  const onAbort=vi.fn()
  let resolveOld:(value:string)=>void=()=>{}
  const pending=q.fetchQuery({
   queryKey:['purchasing','same-tenant','old-page'],
   queryFn:({signal})=>new Promise<string>((resolve,reject)=>{
    resolveOld=resolve
    signal.addEventListener('abort',()=>{onAbort();reject(new Error('cancelled'))})
   })
  })
  await purgeBusinessSessionCache(q)
  resolveOld('sensitive-old-owner-result')
  await pending.catch(()=>{})
  expect(onAbort).toHaveBeenCalledOnce()
  expect(q.getQueryData(['purchasing','same-tenant','old-page'])).toBeUndefined()
  expect(q.getQueryCache().find({queryKey:['purchasing','same-tenant','old-page']})).toBeUndefined()
  q.clear()
 })

 it('keeps authenticated user observer so Root can refetch next principal',async()=>{
  const q=client()
  q.setQueryData(['auth','me'],{userId:'old'})
  q.setQueryData(['purchasing','tenant1'],{private:'old'})
  await purgeBusinessSessionCache(q)
  expect(q.getQueryData(['auth','me'])).toEqual({userId:'old'})
  q.setQueryData(['auth','me'],{userId:'next'})
  expect(q.getQueryData(['auth','me'])).toEqual({userId:'next'})
  expect(q.getQueryData(['purchasing','tenant1'])).toBeUndefined()
  q.clear()
 })
})
