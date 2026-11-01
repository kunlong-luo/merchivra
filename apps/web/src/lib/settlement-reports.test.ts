import {afterEach,describe,expect,it,vi} from 'vitest'
import {listSettlementReports,reviewSettlementReport} from './settlement-reports'

const report={
  id:'11111111-1111-4111-8111-111111111111',
  marketplaceId:'22222222-2222-4222-8222-222222222222',
  reportId:'RPT-12345678',documentId:'DOC-12345678',
  documentSha256:'a'.repeat(64),settlementReference:'SETTLE-00000001',
  statementCurrency:'USD',declaredNet:null,lineCount:2,
  importedAt:'2026-11-01T00:00:00Z'
}

describe('read-only settlement report source',()=>{
  afterEach(()=>vi.unstubAllGlobals())
  it('does not accept arbitrary seller URLs, tenant parameters or client payloads',async()=>{
    const spy=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>[report]})
    vi.stubGlobal('fetch',spy)
    const result=await listSettlementReports(report.marketplaceId)
    expect(result).toHaveLength(1)
    expect(result[0].documentSha256).toHaveLength(64)
    const [url,request]=spy.mock.calls[0]
    expect(url).toBe('/api/v1/finance/marketplaces/'+report.marketplaceId+'/reports')
    expect(url).not.toContain('tenantId')
    expect(request).not.toHaveProperty('body')
    expect(request.credentials).toBe('same-origin')
    expect(request.cache).toBe('no-store')
  })
  it('never reclassifies a financial source as verified bank payout',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({
      rowCount:2,signedLineTotal:'17.5',currency:'USD',
      distinctOrderReferences:1,bankVerified:true,reconciledToFinances:false
    })}))
    await expect(reviewSettlementReport(report.marketplaceId,report.id)).rejects.toThrow()
  })
  it('reports unauthorized access as error, never empty financial data',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:403}))
    await expect(listSettlementReports(report.marketplaceId)).rejects.toThrow('Owner')
  })
})
