import {beforeEach,describe,expect,it,vi} from 'vitest'
import {stageManualCostSourceBatch} from '../generated/manual-cost-v228'
import {previewManualCostBatch,stageManualCostBatchV228} from './manual-cost-batch-v228'

vi.mock('../generated/manual-cost-v228',()=>({
 stageManualCostSourceBatch:vi.fn()
}))

const MP='00000000-0000-4000-8000-00000000f228'
const SOURCE='00000000-0000-4000-8000-00000000f229'
const USER='00000000-0000-4000-8000-00000000f230'
const SHA='a'.repeat(64)
const line=(key='V228-INVOICE-0001',sha=SHA,amount='12.340000',
 date='2026-11-24')=>[
 'INBOUND_FREIGHT','MANUAL_INVOICE',key,sha,date,amount
].join('\t')
const parsed=()=>previewManualCostBatch(line(),'USD').rows

describe('V228 bounded Owner manual fee TSV / Orval contract',()=>{
 beforeEach(()=>vi.clearAllMocks())

 it('parses a full row without amount floating point or inferred FX',()=>{
  const preview=previewManualCostBatch(line(),'USD')
  expect(preview.errors).toEqual([])
  expect(preview.rows).toEqual([{
   category:'INBOUND_FREIGHT',sourceKind:'MANUAL_INVOICE',
   sourceKey:'V228-INVOICE-0001',documentSha256:SHA,
   businessDate:'2026-11-24',amount:'12.340000',currency:'USD'
  }])
 })

 it('blocks empty, over-20, malformed rows and duplicate kind/source identifiers',()=>{
  expect(previewManualCostBatch('','USD').errors.length).toBeGreaterThan(0)
  expect(previewManualCostBatch(Array(21).fill(line()).join('\n'),'USD')
   .errors[0]).toContain('最多 20')
  expect(previewManualCostBatch(line()+'\n'+line(),'USD').rows).toEqual([])
  expect(previewManualCostBatch(line()+'\n'+line(),'USD').errors[0]).toContain('重复')
  for(const text of [
   line('V228-INVOICE-0001','0'.repeat(64)),
   line('V228-INVOICE-0001',SHA,'0'),
   line('V228-INVOICE-0001',SHA,'0.000000'),
   line('V228-INVOICE-0001',SHA,'-3'),
   line('V228-INVOICE-0001',SHA,'12.3456789'),
   line('V228-INVOICE-0001',SHA,'12.34','2026-02-30'),
   line('x'),
   line().replace('INBOUND_FREIGHT','NOT_A_CATEGORY'),
  ]) {
   const preview=previewManualCostBatch(text,'USD')
   expect(preview.errors.length).toBeGreaterThan(0)
   expect(preview.rows).toEqual([])
  }
  expect(previewManualCostBatch(line(),'').rows).toEqual([])
 })

 it('uses generated Orval operation, checks no-store and full immutable replay facts',async()=>{
  const body=parsed()
  vi.mocked(stageManualCostSourceBatch).mockResolvedValue({
   status:200,
   headers:new Headers({'Cache-Control':'no-store'}),
   data:[{
    id:SOURCE,marketplaceId:MP,
    ...body[0],amount:'12.34',origin:'MANUAL_UNVERIFIED',
    state:'REVIEWED_UNVERIFIED',createdBy:USER,reviewedBy:USER,
    reviewedAt:'2026-11-24T10:00:00Z'
   }]
  } as Awaited<ReturnType<typeof stageManualCostSourceBatch>>)
  const actual=await stageManualCostBatchV228(MP,'USD',body)
  expect(actual[0].id).toBe(SOURCE)
  expect(actual[0].state).toBe('REVIEWED_UNVERIFIED')
  expect(stageManualCostSourceBatch).toHaveBeenCalledWith(MP,body)
 })

 it('never requests a batch with a different currency or repeated source key',async()=>{
  const body=parsed()
  await expect(stageManualCostBatchV228(MP,'EUR',body))
   .rejects.toThrow('不同原币')
  await expect(stageManualCostBatchV228(MP,'USD',[body[0],body[0]]))
   .rejects.toThrow('来源编号重复')
  await expect(stageManualCostBatchV228(MP,'USD',[]))
   .rejects.toThrow('1–20')
  expect(stageManualCostSourceBatch).not.toHaveBeenCalled()
 })

 it('fails closed on missing no-store or unexpected marketplace/amount',async()=>{
  const body=parsed()
  const expected={
   id:SOURCE,marketplaceId:MP,...body[0],origin:'MANUAL_UNVERIFIED',
   state:'STAGED',createdBy:USER,reviewedBy:null,reviewedAt:null
  }
  vi.mocked(stageManualCostSourceBatch).mockResolvedValueOnce({
   status:200,headers:new Headers(),data:[expected]
  } as Awaited<ReturnType<typeof stageManualCostSourceBatch>>)
  await expect(stageManualCostBatchV228(MP,'USD',body))
   .rejects.toThrow('安全缓存')

  vi.mocked(stageManualCostSourceBatch).mockResolvedValueOnce({
   status:200,headers:new Headers({'Cache-Control':'no-store'}),
   data:[{...expected,marketplaceId:'00000000-0000-4000-8000-00000000f231'}]
  } as Awaited<ReturnType<typeof stageManualCostSourceBatch>>)
  await expect(stageManualCostBatchV228(MP,'USD',body))
   .rejects.toThrow('原始证据不一致')

  vi.mocked(stageManualCostSourceBatch).mockResolvedValueOnce({
   status:200,headers:new Headers({'Cache-Control':'no-store'}),
   data:[{...expected,amount:'12.350000'}]
  } as Awaited<ReturnType<typeof stageManualCostSourceBatch>>)
  await expect(stageManualCostBatchV228(MP,'USD',body))
   .rejects.toThrow('原始证据不一致')
 })
})
