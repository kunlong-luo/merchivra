import {afterEach,describe,expect,it,vi} from 'vitest'
import {compareManualBank,importManualBankCsv,manualBankBatch,manualBankComparison}
 from './manual-bank'
import {bankManualReview,decideBankManualReview} from './bank-manual-review'
const mp='22222222-2222-4222-8222-222222222222'
const report='11111111-1111-4111-8111-111111111111'
const batch={
 id:'33333333-3333-4333-8333-333333333333',
 marketplaceId:mp,statementRef:'BANKREF-2026-001',
 fileSha256:'a'.repeat(64),lineCount:1,sourceOrigin:'MANUAL_UNVERIFIED',
 importedBy:'44444444-4444-4444-8444-444444444444',
 importedAt:'2026-11-01T00:00:00Z'
}
const payout={
 settlementRef:'SETTLE-2026-001',settlementCurrency:'USD',
 declaredNet:'100.00',bankCurrency:'USD',observedCredit:'98.00',
 withheldFee:'2.00',declaredFxRate:null,expectedGross:'100.00',
 sourceDifference:'0',state:'MANUAL_AMOUNT_MATCH_UNVERIFIED',
 candidateCount:1,sourceReviewApproved:true,bankVerified:false,
 ledgerPosted:false,candidates:[{
  entryRef:'ENTRY-2026-001',valueDate:'2026-10-30',currency:'USD',
  credit:'98.00',withheldFee:'2.00',declaredFxRate:null
 }]
}
const review={
 state:'REVIEWED',comparisonState:'MANUAL_AMOUNT_MATCH_UNVERIFIED',
 requestedBy:'55555555-5555-4555-8555-555555555555',
 reviewedBy:'66666666-6666-4666-8666-666666666666',
 version:1,evidenceCurrent:true,bankVerified:false,ledgerPosted:false
}
describe('manual bank source never certifies bank receipt',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('refuses invented LIVE bank origin and confirmed payout flags',()=>{
  expect(manualBankBatch.parse(batch).sourceOrigin).toBe('MANUAL_UNVERIFIED')
  expect(()=>manualBankBatch.parse({...batch,sourceOrigin:'LIVE'})).toThrow()
  expect(()=>manualBankComparison.parse({...payout,bankVerified:true})).toThrow()
  expect(()=>manualBankComparison.parse({...payout,ledgerPosted:true})).toThrow()
  expect(()=>bankManualReview.parse({...review,bankVerified:true})).toThrow()
 })
 it('reads bank differences only through current Session marketplace and report',async()=>{
  const mock=vi.fn().mockResolvedValue({ok:true,json:async()=>payout})
  vi.stubGlobal('fetch',mock)
  expect((await compareManualBank(mp,report)).state).toBe('MANUAL_AMOUNT_MATCH_UNVERIFIED')
  const [url,options]=mock.mock.calls[0]
  expect(url).toContain('/finance/marketplaces/'+mp+'/reports/'+report+'/bank-comparison')
  expect(url).not.toContain('tenantId')
  expect(options.credentials).toBe('same-origin')
  expect(options).not.toHaveProperty('body')
 })
 it('rejects invalid upload source and oversized files before network',async()=>{
  const mock=vi.fn()
  vi.stubGlobal('fetch',mock)
  const empty={size:0} as File
  await expect(importManualBankCsv(mp,'BANKREF-2026-001',empty)).rejects.toThrow()
  const huge={size:65537} as File
  await expect(importManualBankCsv(mp,'BANKREF-2026-001',huge)).rejects.toThrow()
  await expect(importManualBankCsv(mp,'personal account #123',huge)).rejects.toThrow()
  expect(mock).not.toHaveBeenCalled()
 })
 it('imports via CSRF without uploading browser tenant or banking credentials',async()=>{
  const file={size:256} as File
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:batch
  }))
  vi.stubGlobal('fetch',mock)
  expect((await importManualBankCsv(mp,batch.statementRef,file)).lineCount).toBe(1)
  const [url,req]=mock.mock.calls[1]
  expect(url).toContain('/bank-payout-imports/'+batch.statementRef)
  expect(url).not.toContain('tenantId')
  expect(req.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(req.headers['Content-Type']).toContain('text/plain')
  expect(req.body).toBe(file)
 })
 it('manual approval is not a bank settlement and requires CSRF',async()=>{
  const mock=vi.fn().mockImplementation(async (url:string)=>({
   ok:true,json:async()=>url.endsWith('/auth/csrf')?
    {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:review
  }))
  vi.stubGlobal('fetch',mock)
  const approved=await decideBankManualReview(mp,report,
   'approve','Reviewed sanitized source and independent balance')
  expect(approved.bankVerified).toBe(false)
  const [url,req]=mock.mock.calls[1]
  expect(url).toContain('/manual-bank-review/approve')
  expect(req.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(req.body).not.toContain('bankAccount')
 })
})
