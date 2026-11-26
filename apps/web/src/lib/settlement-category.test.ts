import {afterEach,describe,expect,it,vi} from 'vitest'
import {categoryReview,savedPreview,rowsFromVisibleLines,editorMappings,writeCategoryReview,categoryTypes,getCategoryTypes} from './settlement-category'
const report='11111111-1111-4111-8111-111111111111',market='22222222-2222-4222-8222-222222222222'
const mapping='a'.repeat(64),evidence='b'.repeat(64)
const requestedBy='33333333-3333-4333-8333-333333333333'
const record={mappingSha256:mapping,evidenceSha256:evidence,state:'LOCAL_CATEGORY_REVIEW_REQUESTED',
 requestedBy,approvedBy:null,mappingActivated:false,bankVerified:false,ledgerPosted:false,realizedNetProfit:null}
describe('V279 category workflow boundaries',()=>{
 afterEach(()=>vi.unstubAllGlobals())
 it('accepts complete discovery, rejects truncated or duplicate coverage and partial HOLD',()=>{
  const a={transactionType:'Order',amountType:null,amountDescription:'Principal',rowCount:100}
  const b={transactionType:'Order',amountType:'ItemFees',amountDescription:'Commission',rowCount:1}
  const full={reportId:report,state:'REPORT_TYPES_READY',expectedRows:101,actualRows:101,
   types:[a,b],mappingApproved:false,bankVerified:false}
  expect(categoryTypes.parse(full).types).toHaveLength(2)
  expect(()=>categoryTypes.parse({...full,types:[a]})).toThrow()
  expect(()=>categoryTypes.parse({...full,types:[a,{...a,rowCount:1}]})).toThrow()
  expect(()=>categoryTypes.parse({...full,state:'REPORT_TYPE_LIMIT_EXCEEDED'})).toThrow()
  expect(categoryTypes.parse({...full,state:'REPORT_TYPE_LIMIT_EXCEEDED',types:[]}).types).toEqual([])
 })
 it('loads full types from scoped no-store GET and rejects wrong report',async()=>{
  const data={reportId:report,state:'REPORT_TYPES_READY',expectedRows:1,actualRows:1,
   types:[{transactionType:'Order',amountType:null,amountDescription:null,rowCount:1}],
   mappingApproved:false,bankVerified:false}
  const fetchMock=vi.fn().mockResolvedValue({ok:true,json:async()=>data})
  vi.stubGlobal('fetch',fetchMock)
  expect((await getCategoryTypes(market,report)).types).toHaveLength(1)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/finance/marketplaces/'+market+'/reports/'+report+'/category-types')
  expect(fetchMock.mock.calls[0][1].cache).toBe('no-store')
  await expect(getCategoryTypes(market,market)).rejects.toThrow('身份')
 })
 it('does not guess classifications from visible lines and preserves null',()=>{
  const line={lineNumber:1,transactionType:'Order',amountType:null,amountDescription:'Principal',
   orderId:null,signedAmount:'20.00',currency:'USD'}
  const rows=rowsFromVisibleLines([line,{...line,lineNumber:2}])
  expect(rows).toHaveLength(1);expect(rows[0].category).toBe('')
  expect(()=>editorMappings(rows)).toThrow()
  const edited={...rows[0],category:'PRODUCT_SALES'}
  expect(editorMappings([edited])[0].reportType.amountType).toBeNull()
  expect(()=>editorMappings([edited,edited])).toThrow()
 })
 it('rejects false activation and self approval',()=>{
  expect(categoryReview.parse(record).state).toBe(record.state)
  expect(()=>categoryReview.parse({...record,mappingActivated:true})).toThrow()
  expect(()=>categoryReview.parse({...record,realizedNetProfit:'20'})).toThrow()
  expect(()=>categoryReview.parse({...record,state:'LOCAL_CATEGORY_REVIEW_RECORDED_NOT_ACTIVATED',approvedBy:requestedBy})).toThrow()
 })
 it('rejects evidence for incomplete previews',()=>{
  const preview={state:'CATEGORY_MAPPING_INCOMPLETE',sourceState:'SOURCE_AMOUNTS_AGREE_UNVERIFIED',currency:'USD',
   unmappedReportRows:1,unmappedFinanceLeaves:0,unusedMappings:0,mismatchedBuckets:0,categoryTotals:[],
   mappingApproved:false,bankVerified:false,ledgerPosted:false,realizedNetProfit:null}
  expect(savedPreview.parse({mappingSha256:mapping,preview,evidenceSha256:null,mappingApproved:false})).toBeTruthy()
  expect(()=>savedPreview.parse({mappingSha256:mapping,preview,evidenceSha256:evidence,mappingApproved:false})).toThrow()
 })
 it('posts exact evidence path with csrf and only a reason; mismatched response rejected',async()=>{
  const fetchMock=vi.fn().mockImplementation(async (url:string)=>({ok:true,json:async()=>url.endsWith('/auth/csrf')?
   {token:'1234567890abcdef',headerName:'X-CSRF-TOKEN'}:record}))
  vi.stubGlobal('fetch',fetchMock)
  await writeCategoryReview(market,report,mapping,evidence,'request','Review exact categories')
  const [url,options]=fetchMock.mock.calls[1]
  expect(url).toContain('/marketplaces/'+market+'/reports/'+report+'/category-mapping-drafts/'+mapping+'/reviews/'+evidence+'/request')
  expect(options.headers['X-CSRF-TOKEN']).toBe('1234567890abcdef')
  expect(JSON.parse(options.body)).toEqual({reason:'Review exact categories'})
  expect(options.cache).toBe('no-store')
  await expect(writeCategoryReview(market,report,mapping,'c'.repeat(64),'request','Review exact categories')).rejects.toThrow('身份')
 })
})
