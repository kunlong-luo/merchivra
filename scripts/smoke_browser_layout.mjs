#!/usr/bin/env node
// Chromium layout smoke using built-in Node 24 WebSocket/CDP and a synthetic localhost API.
// No Amazon calls, production credentials, Playwright, or new npm dependencies.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, readFile, mkdir, writeFile, rm } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve, extname } from 'node:path'

const dist = resolve(process.argv[2] ?? 'apps/web/dist')
// When set, run the same synthetic API + Chrome assertions against the real
// Next App Router process instead of serving the Vite-built dist directory.
const nextTarget=process.env.MERCHIVRA_BROWSER_NEXT_URL??null
const output = resolve(process.argv[3] ?? 'browser-smoke-artifacts')
const tenant = '11111111-1111-4111-8111-111111111111'
const account = '22222222-2222-4222-8222-222222222222'
const market = '33333333-3333-4333-8333-333333333333'
let mode = 'guest'
// V104: mutable synthetic local PO for cancel/confirm without live operations.
const v104Order={
  id:'84222222-2222-4222-8222-222222222222',
  orderNumber:'PO-V104-CONFIRM-SYNTHETIC',
  supplierId:'22222222-2222-4222-8222-222222222222',
  status:'DRAFT',currency:'USD',
  createdBy:'11111111-1111-4111-8111-111111111111',
  approvedBy:null,notes:null,updatedAt:'2026-11-08T00:00:00Z'
}
let v104CancelWrites=0
let v104Cancelled=false
const respond = (res, value, code = 200) => {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(value))
}
const mock = new Map([
  ['/api/v1/seller-marketplaces', [{
    id: market, sellerAccountId: account, marketplaceId: 'ATVPDKIKX0DER',
    region: 'NA', currency: 'USD', timezone: 'America/Los_Angeles',
    connectionStatus: 'UNCONFIGURED'
  }]],
  ['/api/v1/seller-marketplaces/' + market + '/listings', [
    { id: '44444444-4444-4444-8444-444444444444', sellerSku: 'TEST-SKU-100',
      asin: 'B000TEST00', fnsku: null, mappingStatus: 'ACTIVE',
      internalSku: 'CORE-100', sourceUpdatedAt: '2026-10-30T12:00:00Z', dataOrigin: 'MOCK' },
    { id: '44444444-4444-4444-8444-444444444445', sellerSku: 'TEST-SKU-200',
      asin: null, fnsku: null, mappingStatus: 'UNMAPPED',
      internalSku: null, sourceUpdatedAt: null, dataOrigin: 'UNVERIFIED' }
  ]],
  ['/api/v1/seller-marketplaces/' + market + '/orders', [
    { id: '55555555-5555-4555-8555-555555555555',
      externalOrderId: 'TEST-ORDER-100', itemCount: 2,
      fulfillmentStatus: 'SHIPPED', refundStatus: 'NONE',
      financialStatus: 'NOT_OBSERVED', sourceUpdatedAt: null, dataOrigin: 'MOCK' }
  ]],
  ['/api/v1/seller-marketplaces/' + market + '/inventory/fba', [
    { id: '66666666-6666-4666-8666-666666666666', sellerSku: 'TEST-SKU-100',
      quality: 'UNKNOWN', fulfillable: null, reserved: 0,
      observedAt: null, ingestedAt: '2026-10-30T12:00:00Z', dataOrigin: 'UNVERIFIED' }
  ]]
])
const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css',
  '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon', '.woff2':'font/woff2' }
const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://127.0.0.1')
  // The React core screens have used V86/V87/V88 server-side pages since
  // those versions. Keep the synthetic browser fixture contract in sync; the
  // original uncapped array endpoints below remain for legacy consumers.
  const limit = Number(url.searchParams.get('limit') ?? '50')
  const search = url.searchParams.get('search') ?? ''
  const emptyCursorPage = items => ({
    items:items.slice(0,limit), nextCursor:null, hasMore:false, pageSize:limit
  })
  // Synthetic CSRF token exists only in this disposable localhost server.
  // V101 tests the real Spring Session/CSRF behavior separately.
  if(url.pathname==='/api/v1/auth/csrf')
    return respond(res,{token:'V104_DISPOSABLE_BROWSER_CSRF_ONLY_12345',
      headerName:'X-CSRF-TOKEN'})
  if(url.pathname==='/api/v1/purchasing/orders' && req.method==='GET')
    return respond(res,[{...v104Order,status:v104Cancelled?'CANCELLED':'DRAFT'}])
  if(url.pathname===`/api/v1/purchasing/orders/${v104Order.id}/cancel`
     && req.method==='POST'){
    if(mode==='guest'||req.headers['x-csrf-token']!=='V104_DISPOSABLE_BROWSER_CSRF_ONLY_12345')
      return respond(res,{error:'synthetic CSRF guard rejected write'},403)
    v104CancelWrites++
    v104Cancelled=true
    return respond(res,{order:{...v104Order,status:'CANCELLED'},lines:[]})
  }
  if (url.pathname === '/api/v1/seller-marketplaces/' + market + '/listings/page') {
    const field = url.searchParams.get('field') ?? 'SELLER_SKU'
    const mappingStatus = url.searchParams.get('status') ?? 'ALL'
    const items = mock.get('/api/v1/seller-marketplaces/' + market + '/listings')
      .filter(x => (mappingStatus === 'ALL' || x.mappingStatus === mappingStatus)
        && (field === 'ASIN' ? x.asin ?? '' : x.sellerSku).startsWith(search))
      .map(x => ({...x,activeMappingVersion:x.mappingStatus==='ACTIVE'?1:null,
        latestMappingVersion:x.mappingStatus==='ACTIVE'?1:null}))
    return respond(res, {...emptyCursorPage(items),field,searchPrefix:search,mappingStatus})
  }
  if (url.pathname === '/api/v1/seller-marketplaces/' + market + '/orders/page') {
    const items = mock.get('/api/v1/seller-marketplaces/' + market + '/orders')
      .filter(x => x.externalOrderId.startsWith(search))
    return respond(res, {...emptyCursorPage(items),searchPrefix:search})
  }
  if (url.pathname === '/api/v1/seller-marketplaces/' + market + '/inventory/fba/page') {
    const quality = url.searchParams.get('quality') ?? 'ALL'
    const origin = url.searchParams.get('origin') ?? 'ALL'
    const items = mock.get('/api/v1/seller-marketplaces/' + market + '/inventory/fba')
      .filter(x => x.sellerSku.startsWith(search)
        && (quality==='ALL'||x.quality===quality)
        && (origin==='ALL'||x.dataOrigin===origin))
    return respond(res, {...emptyCursorPage(items),
      searchPrefix:search,quality,origin})
  }
  if (url.pathname === '/api/v1/auth/me') {
    return mode === 'guest' ? respond(res, { error:'Not authenticated' }, 401)
      : respond(res, { userId: account, activeTenantId: tenant, availableTenantIds:[tenant],
          isOwner: mode !== 'staff' })
  }
  if (url.pathname === '/api/v1/ping') return respond(res, { service:'merchivra',status:'ok',stage:'MOCK_BROWSER' })
  if (url.pathname === '/api/v1/team/members') return respond(res, [
    {userId:account,loginName:'local-smoke-admin',status:'ACTIVE',reviewer:false},
    {userId:'88888888-8888-4888-8888-888888888888',loginName:'ops-reviewer',status:'ACTIVE',reviewer:true}
  ])
  if (url.pathname === '/api/v1/team/invitations') return respond(res, [
    {id:'99999999-9999-4999-8999-999999999999',
     loginName:'another-owner',status:'PENDING',expiresAt:'2026-11-01T00:00:00Z'}
  ])
  if (url.pathname === '/api/v1/orders-sync/' + market)
    return respond(res, {
      jobId:null,state:'NOT_STARTED',committedPages:0,leaseEpoch:0,
      windowAfter:null,windowBefore:null,lastSafeWatermark:null,
      nextAttemptAt:null,lastFailureCode:null
    })
  if (url.pathname === '/api/v1/fba/marketplaces/' + market + '/purchase-reconciliation')
    return respond(res,{
      truncated:false,shipmentScope:1,
      totals:{examinedLines:1,high:0,warning:1,informational:0,
        pendingIdentity:1,unlinked:0,localVariance:0},
      lines:[{
        shipmentId:'abcd1111-1111-4111-8111-111111111111',
        shipmentReference:'V75-LOCAL-FBA',shipmentState:'DRAFT',
        shipmentOrigin:'MOCK',
        inboundLineId:'abcd2222-2222-4222-8222-222222222222',
        sellerSku:'TEST-SKU-100',plannedQuantity:12,locallyReportedQuantity:0,
        localVarianceDecision:null,
        linkId:'abcd3333-3333-4333-8333-333333333333',
        linkState:'PROPOSED',
        proofReference:'V75 synthetic proof is NOT Amazon evidence',
        purchaseOrderId:'abcd4444-4444-4444-8444-444444444444',
        purchaseOrderNumber:'PO-V75-LOCAL',purchaseOrderState:'APPROVED',
        purchaseOrderedQuantity:20,purchaseLocalReceivedQuantity:4,
        asnId:null,asnState:null,asnReportedQuantity:null,
        asnLocalReceivedQuantity:null,
        findingCode:'PENDING_IDENTITY_REVIEW',severity:'WARNING',
        recommendedAction:'SECOND_OWNER_REVIEW'
      }],amazonVerified:false,fbaStockCredited:false
    })
  if (url.pathname === '/api/v1/fba/marketplaces/' + market + '/shipments'
      || url.pathname === '/api/v1/fba/marketplaces/' + market + '/shipments/page') {
    const shipments=[{
      id:'abcd1111-1111-4111-8111-111111111111',reference:'V75-LOCAL-FBA',
      origin:'MOCK',state:'DRAFT',createdAt:'2026-11-02T00:00:00Z',
      dispatchedAt:null,reviewStartedAt:null,reconciledAt:null
    }]
    if (url.pathname.endsWith('/page')) {
      const state=url.searchParams.get('state')??'ALL'
      const items=shipments.filter(x=>x.reference.startsWith(search)
        && (state==='ALL'||x.state===state))
      return respond(res,{...emptyCursorPage(items),searchPrefix:search,state})
    }
    return respond(res,shipments)
  }
  if (url.pathname === '/api/v1/fba/marketplaces/' + market + '/shipments/in-transit')
    return respond(res,[])
  if (url.pathname === '/api/v1/fba/marketplaces/' + market +
      '/shipments/abcd1111-1111-4111-8111-111111111111')
    return respond(res,{
      shipment:{id:'abcd1111-1111-4111-8111-111111111111',
        reference:'V75-LOCAL-FBA',origin:'MOCK',state:'DRAFT',
        createdAt:'2026-11-02T00:00:00Z',dispatchedAt:null,
        reviewStartedAt:null,reconciledAt:null},
      lines:[{id:'abcd2222-2222-4222-8222-222222222222',
        sellerSku:'TEST-SKU-100',plannedQuantity:12,
        reportedReceived:0,estimatedInTransit:null,delta:-12,
        decision:null,reviewedBy:null,reviewNote:null,reviewedAt:null}],
      receipts:[],amazonVerified:false,fbaStockCredited:false
    })
  if (url.pathname === '/api/v1/fba/marketplaces/' + market +
      '/shipments/abcd1111-1111-4111-8111-111111111111/purchase-links')
    return respond(res,[{
      id:'abcd3333-3333-4333-8333-333333333333',
      shipmentId:'abcd1111-1111-4111-8111-111111111111',
      inboundLineId:'abcd2222-2222-4222-8222-222222222222',
      sellerSku:'TEST-SKU-100',shipmentState:'DRAFT',shipmentOrigin:'MOCK',
      inboundPlannedQuantity:12,inboundReportedReceived:0,
      purchaseOrderId:'abcd4444-4444-4444-8444-444444444444',
      purchaseOrderLineId:'abcd5555-5555-4555-8555-555555555555',
      orderNumber:'PO-V75-LOCAL',purchaseState:'APPROVED',
      poOrderedQuantity:20,localPoReceived:4,
      asnId:null,asnStatus:null,asnQuantity:null,asnLocalReceived:null,
      state:'PROPOSED',proofReference:'V75 synthetic proof is NOT Amazon evidence',
      proposedBy:'abcd6666-6666-4666-8666-666666666666',
      confirmedBy:null,createdAt:'2026-11-02T00:00:00Z',confirmedAt:null
    }])
  if (url.pathname.endsWith('/purchase-links/lines/abcd2222-2222-4222-8222-222222222222/candidates'))
    return respond(res,[{
      purchaseOrderId:'abcd4444-4444-4444-8444-444444444444',
      purchaseOrderLineId:'abcd5555-5555-4555-8555-555555555555',
      orderNumber:'PO-V75-LOCAL',supplierName:'V75 mock supplier',
      orderedQuantity:20,localReceivedQuantity:4,asnId:null,
      asnStatus:null,asnQuantity:null,asnReceivedQuantity:0
    }])
  if (url.pathname === '/api/v1/purchasing/supplier-performance/scorecards'
      || url.pathname === '/api/v1/purchasing/supplier-performance/scorecards/page') {
    const scorecards=[{
      supplierId:'22222222-2222-4222-8222-222222222222',
      supplierName:'V73 Synthetic Supplier',
      completeAsnSampleCount:1,observedOnTimeAsnCount:1,
      inspectedReceiptSampleCount:0,acceptedQcUnits:0,inspectedQcUnits:0,
      activeVarianceCount:0,observedOnTimePercent:'100.0',
      inspectedAcceptancePercent:null,evidenceScore:null,
      scoringStatus:'INSUFFICIENT_EVIDENCE'
    }]
    return respond(res,url.pathname.endsWith('/page')
      ? emptyCursorPage(scorecards) : scorecards)
  }
  if (url.pathname === '/api/v1/purchasing/supplier-performance/followups'
      || url.pathname === '/api/v1/purchasing/supplier-performance/followups/page')
    return respond(res,url.pathname.endsWith('/page')?emptyCursorPage([]):[])
  if (url.pathname === '/api/v1/purchasing/delivery/overview')
    return respond(res,{
      asOfDateUtc:'2026-11-02',
      totals:{lineCount:1,pendingQuantity:18,reportedUnreceivedQuantity:0,
        awaitingQcReleaseQuantity:0,warningLines:1,overdueLines:0,
        unresolvedVarianceLines:0},
      lines:[{
        orderId:'11111111-1111-4111-8111-111111111111',orderNumber:'PO-V72-MOCK',
        orderStatus:'APPROVED',supplierId:'22222222-2222-4222-8222-222222222222',
        supplierName:'V72 Synthetic Supplier',currency:'USD',
        lineId:'33333333-3333-4333-8333-333333333333',
        productSkuId:'44444444-4444-4444-8444-444444444444',
        internalSku:'TEST-SKU-100',orderedQuantity:18,receivedQuantity:0,
        remainingQuantity:18,draftAsnQuantity:0,shippedAsnQuantity:0,
        reportedUnreceivedQuantity:0,unlinkedReceiptQuantity:0,
        awaitingQcReleaseQuantity:0,releasedQcAcceptedQuantity:0,qcUnknownQuantity:0,
        unresolvedVarianceCount:0,earliestReportedEta:null,latestReportedEta:null,
        replenishmentSellerSku:null,replenishmentPricingSource:null,
        deliveryState:'NO_ASN_RECORDED'
      }]
    })
  if (url.pathname === '/api/v1/replenishment/marketplaces/' + market + '/priorities')
    return respond(res, [{
      sellerSku:'TEST-SKU-100',stockState:'NO_OBSERVATION',
      demandStatus:'INCOMPLETE_COVERAGE',evidenceLevel:'STOCK_UNTRUSTED',
      riskCode:'STOCK_NOT_OBSERVED',priorityCode:'BLOCKED',
      stockSourceMode:null,demandSourceMode:'MOCK',observationId:null,
      policyUpdatedAt:'2026-11-02T00:00:00Z',fulfillable:null,observedAt:null,
      policyLeadTimeDays:14,observedUnits28d:null,observedDailyUnits28d:null,
      illustrativeCoverageDays:null,illustrativePolicyLeadUnits:null,
      readySupplierQuoteCount:0,shortestSupplierTermDays:null,
      longestSupplierTermDays:null,
      localDraftOrSubmittedUnits:36,localApprovedUnreceivedUnits:0,
      localReceivedUnits:0,localPendingOrderCount:1,existingLocalOrderNeedsReview:true
    }])
  if (url.pathname === '/api/v1/replenishment/marketplaces/' + market + '/supplier-quotes')
    return respond(res, [])
  if (url.pathname === '/api/v1/replenishment/marketplaces/' + market + '/demand-signals')
    return respond(res, [{
      sellerSku:'TEST-SKU-100',status:'INCOMPLETE_COVERAGE',sourceMode:'MOCK',
      windowStart:'2026-10-01T00:00:00Z',windowEnd:'2026-10-29T00:00:00Z',
      leadTimeDays:14,observedOrderCount:null,incompleteOrderCount:null,
      observedUnits7d:null,observedUnits28d:null,observedDailyUnits28d:null,
      illustrativeLeadTimeUnits:null
    }])
  if (url.pathname === '/api/v1/operations/marketplaces/' + market + '/rules')
    return respond(res, [
      {ruleCode:'UNMAPPED_LISTING',enabled:true,lowStockThreshold:null,version:0,updatedAt:null},
      {ruleCode:'INVENTORY_UNKNOWN',enabled:true,lowStockThreshold:null,version:0,updatedAt:null},
      {ruleCode:'LOW_STOCK',enabled:true,lowStockThreshold:3,version:0,updatedAt:null},
      {ruleCode:'REFUND_REVIEW',enabled:true,lowStockThreshold:null,version:0,updatedAt:null},
      {ruleCode:'FBA_INBOUND_REVIEW',enabled:true,lowStockThreshold:null,version:0,updatedAt:null},
      {ruleCode:'ADS_REPORT_REVIEW',enabled:true,lowStockThreshold:null,version:0,updatedAt:null},
      {ruleCode:'FBA_PO_EVIDENCE_CHANGED',enabled:true,lowStockThreshold:null,version:0,updatedAt:null},
      {ruleCode:'FBA_PO_EXCEPTION_OVERDUE',enabled:true,lowStockThreshold:null,version:0,updatedAt:null}
    ])
  if (url.pathname === '/api/v1/operations/marketplaces/' + market + '/rules/audit')
    return respond(res, [])
  if (url.pathname === '/api/v1/operations/summary')
    return respond(res, { open:1, acknowledged:0, done:0, cleared:0, snoozed:0 })
  if (url.pathname === '/api/v1/operations/findings'
      || url.pathname === '/api/v1/operations/findings/page') {
    const findings=[{
      id:'77777777-7777-4777-8777-777777777777',
      marketplaceId:market, ruleCode:'INVENTORY_UNKNOWN',
      sourceKey:'TEST-SKU-100', title:'FBA 库存数据不完整',
      severity:'WARNING', state:'OPEN', dataOrigin:'MOCK',
      lastSeenAt:'2026-10-31T00:00:00Z', assignedTo:null, snoozedUntil:null
    }]
    if (url.pathname.endsWith('/page')) {
      const filter=url.searchParams.get('filter')??'ACTIVE'
      // V95 page is priority-ordered, permission-filtered, cursor-based.
      // This fixture has one visible, unsnoozed MOCK finding on the first page.
      return respond(res,emptyCursorPage(findings.filter(x=>filter==='ALL'
        || x.state==='OPEN'||x.state==='ACKNOWLEDGED')))
    }
    return respond(res,findings)
  }
  if (url.pathname.startsWith('/api/v1/')) {
    if (url.pathname === '/api/v1/seller-marketplaces' && mode === 'connected')
      return respond(res, mock.get(url.pathname).map(x=>({ ...x, connectionStatus:'CONNECTED' })))
    if (mock.has(url.pathname)) return respond(res, mock.get(url.pathname))
    if (url.pathname.endsWith('/pages')) return respond(res,
      { status:'NOT_STARTED', nextPage:0, totalPages:2, leaseEpoch:0, pages:[] })
    return respond(res, [])
  }
  if(nextTarget){
    // Preserve the exact synthetic mock API above while forwarding only
    // document and Next static-resource requests to the local Next server.
    // Never proxy these test fixtures to a remote host/production API.
    if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(nextTarget))
      throw Error('Next smoke upstream must be local loopback')
    try {
      const forwarded=new Headers()
      // Next Router's RSC/Flight navigation depends on these request headers.
      // Forward only router protocol fields to disposable local Next; never
      // forward browser cookies, Authorization or other sensitive headers.
      for(const name of ['accept','rsc','next-router-state-tree',
        'next-router-prefetch','next-url','next-router-segment-prefetch']){
        const value=req.headers[name]
        if(typeof value==='string')forwarded.set(name,value)
      }
      const upstream=await fetch(nextTarget+url.pathname+url.search,{
        headers:forwarded
      })
      const bytes=Buffer.from(await upstream.arrayBuffer())
      res.writeHead(upstream.status,{
        'Content-Type':upstream.headers.get('content-type')??'text/plain',
        'Cache-Control':upstream.headers.get('cache-control')??'no-store'
      })
      res.end(bytes)
    } catch(error) {
      console.error('Next fixture proxy failed',String(error))
      res.writeHead(502).end('Next preview unavailable')
    }
    return
  }
  const local = resolve(dist, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname))
  if (!local.startsWith(dist + '/')) return res.writeHead(403).end()
  try {
    const bytes = await readFile(local)
    res.writeHead(200, { 'Content-Type': mime[extname(local)] ?? 'application/octet-stream' })
    res.end(bytes)
  } catch { res.writeHead(404).end('Missing test asset') }
})
const sleep = ms => new Promise(r => setTimeout(r, ms))
let chrome, socket, profile
async function connect(port) {
  const response = await fetch('http://127.0.0.1:' + port + '/json/new?browser-smoke',
    { method:'PUT' })
  assert.ok(response.ok, 'Chrome target failed: ' + response.status)
  const tab = await response.json()
  const ws = new WebSocket(tab.webSocketDebuggerUrl)
  await new Promise((ok, fail) => {
    ws.addEventListener('open', ok, { once:true })
    ws.addEventListener('error', fail, { once:true })
  })
  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', event => {
    const msg = JSON.parse(event.data)
    if (!msg.id || !pending.has(msg.id)) return
    const item = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? item.fail(new Error(msg.error.message)) : item.ok(msg.result)
  })
  const send = (method, params = {}) => new Promise((ok, fail) => {
    const id = ++seq
    pending.set(id, { ok, fail })
    ws.send(JSON.stringify({ id, method, params }))
  })
  return { ws, send }
}
async function evaluate(send, expr) {
  const value = await send('Runtime.evaluate', {
    expression:expr, awaitPromise:true, returnByValue:true
  })
  if (value.exceptionDetails) throw new Error(value.exceptionDetails.text)
  return value.result.value
}
async function waitFor(send, expression, label) {
  for (let i=0; i<90; i++) {
    try { if (await evaluate(send, expression)) return } catch { /* navigating */ }
    await sleep(140)
  }
  throw new Error('Timed out: ' + label)
}
async function screenshot(send, name, table=false) {
  if (table) {
    await evaluate(send,
      "document.querySelector('.core-data-scroll')?.scrollIntoView({block:'center'})")
    await sleep(120)
  }
  const result = await send('Page.captureScreenshot', {
    format:'png', fromSurface:true, captureBeyondViewport:false
  })
  await writeFile(join(output, name), Buffer.from(result.data, 'base64'))
}
async function layout(send, width, label, hasTable=false) {
  const data = await evaluate(send, "(() => {" +
    "const t=document.querySelector('.core-data-scroll');" +
    "return {width:innerWidth, page:document.documentElement.scrollWidth," +
    "body:document.body.scrollWidth, tableWidth:t?.clientWidth??0," +
    "scrollWidth:t?.scrollWidth??0, tabbable:!!t && t.tabIndex===0," +
    "skip:!!document.querySelector('a.skip-link[href=\"#main-content\"]')};" +
    "})()")
  assert.equal(data.width, width, label+' viewport')
  assert.ok(data.page <= width+2, label+' document horizontal overflow: '+JSON.stringify(data))
  assert.ok(data.body <= width+2, label+' body horizontal overflow: '+JSON.stringify(data))
  if (hasTable) {
    assert.ok(data.tabbable && data.skip, label+' keyboard navigation unavailable')
    if (width <= 390)
      assert.ok(data.scrollWidth > data.tableWidth, label+' lacks local table scrolling')
  }
  console.log(label+': '+JSON.stringify(data))
}
async function select(send, name) {
  await evaluate(send, "(() => {const b=[...document.querySelectorAll('.core-subtab')]" +
    ".find(x=>x.textContent.trim()==="+JSON.stringify(name)+");" +
    "if(!b)throw Error('Missing tab');b.click();})()")
  const panel = name === '订单' ? 'core-panel-orders' : 'core-panel-inventory'
  await waitFor(send,
    "document.querySelectorAll('#"+panel+" .core-data-scroll tbody tr').length===1",
    name+' rows')
  const cols = await evaluate(send, "(() => {const t=document.querySelector('.core-data-scroll table');" +
    "return [t.querySelectorAll('thead th').length,t.querySelectorAll('tbody tr:first-child td').length]})()")
  assert.equal(cols[0], cols[1], name+' header/data columns')
}
async function main() {
  if(!nextTarget)assert.ok(existsSync(join(dist,'index.html')), 'Build the Vite baseline first')
  await mkdir(output,{recursive:true})
  await new Promise(ok => server.listen(0,'127.0.0.1',ok))
  const url = 'http://127.0.0.1:' + server.address().port + (nextTarget?'/core':'/')
  profile = await mkdtemp(join(tmpdir(),'merchivra-chrome-'))
  chrome = spawn(process.env.CHROME_BIN || 'google-chrome', [
    '--headless=new','--no-sandbox','--disable-dev-shm-usage',
    '--disable-extensions','--disable-background-networking',
    '--no-first-run','--remote-debugging-port=0',
    '--user-data-dir='+profile,'about:blank'
  ], { stdio:['ignore','ignore','pipe'] })
  let log=''
  chrome.stderr.on('data',x => { log+=(String(x)).slice(0,2000) })
  let port
  for(let i=0;i<140;i++){
    try { port=Number((await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]) }
    catch { /* startup */ }
    if(port)break
    await sleep(100)
  }
  assert.ok(port,'Chrome startup failed: '+log.slice(-600))
  const conn=await connect(port)
  socket=conn.ws
  const send=conn.send
  await send('Page.enable')
  await send('Runtime.enable')
  for(const width of [360,390,768,1280]){
    await send('Emulation.setDeviceMetricsOverride',{
      width,height:900,deviceScaleFactor:1,mobile:width<768
    })
    mode='guest'
    await send('Page.navigate',{url:url+'?guest='+width})
    await waitFor(send,"!!document.querySelector('form.auth-card #auth-username')",
      'login '+width)
    await layout(send,width,'guest-'+width)
    if(width===360)await screenshot(send,'guest-360.png')
    if(width===360) {
      mode='staff'
      await send('Page.navigate',{url:url+'?staff='+width})
      await waitFor(send,
        "document.querySelectorAll('.core-data-scroll tbody tr').length===2",
        'V16 read-only staff core data')
      assert.equal(await evaluate(send,
        "document.querySelectorAll('.nav-link').length"),1,
        'staff navigation must only show core data')
      assert.equal(await evaluate(send,
        "!!document.querySelector('.team-workspace, .core-tools, .orders-sync-panel')"),false,
        'staff must not see admin tools or sync actions')
      await layout(send,width,'scoped-staff-360')
      await screenshot(send,'scoped-staff-360.png')
    }
    mode='core'
    await send('Page.navigate',{url:url+'?core='+width})
    await waitFor(send,"document.querySelectorAll('.core-data-scroll tbody tr').length===2",
      'listing '+width)
    await layout(send,width,'core-listings-'+width,true)
    await screenshot(send,'core-listings-'+width+'.png')
    await screenshot(send,'core-listings-table-'+width+'.png',true)
    if(nextTarget&&width===360){
      await evaluate(send,
        "document.querySelector('.next-ui-mobile-trigger')?.click()")
      await waitFor(send,
        "!!document.querySelector('.next-ui-nav-sheet') && "+
        "[...document.querySelectorAll('.next-ui-nav-sheet .nav-link')]"+
        ".some(a=>a.textContent.includes('订单管理') && "+
        "a.getAttribute('href')==='/orders')",
        'V103 Base UI mobile navigation opens with native deep links')
      await screenshot(send,'v103-base-ui-mobile-navigation-360.png')
      await evaluate(send,
        "document.querySelector('.next-ui-nav-close')?.click()")
      await waitFor(send,
        "!document.querySelector('.next-ui-nav-sheet')",
        'V103 mobile dialog closes without leaving an overlay')
    }
    // V100: test actual Next App Router navigation, not history.pushState
    // on an unchanged SPA. All data below is from local synthetic API fixtures.
    if(nextTarget&&width===360){
      await evaluate(send,`(() => {const b=[...document.querySelectorAll('.nav-link')]
        .find(x=>x.textContent.includes('订单管理'));if(!b)throw Error('orders nav');b.click()})()`)
      try{
        await waitFor(send,`location.pathname==='/orders' &&
          document.querySelector('.core-subtab.selected')?.textContent?.includes('订单') &&
          document.querySelector('.core-data-scroll tbody tr')?.textContent?.includes('TEST-ORDER-100')`,
          'V100 real Core Orders route')
      }catch(error){
        const state=await evaluate(send,`(() => ({
          path:location.pathname,
          selected:document.querySelector('.core-subtab.selected')?.textContent,
          rows:document.querySelectorAll('.core-data-scroll tbody tr').length,
          title:document.querySelector('#main-content h1')?.textContent,
          nav:[...document.querySelectorAll('.nav-link')].map(x=>({
            label:x.textContent,active:x.getAttribute('aria-current')
          })),
          main:document.querySelector('#main-content')?.textContent?.slice(0,1300),
          html:document.body.innerText.slice(0,1300)
        }))()`)
        console.error('V100 Next route failure snapshot:',JSON.stringify(state))
        throw error
      }
      assert.equal(await evaluate(send,
        "!!document.querySelector('.mock-banner')"),false,
        'Next /orders must not use the separate mock Gateway')
      await screenshot(send,'v100-next-real-orders-360.png',true)
      await evaluate(send,"history.back()")
      await waitFor(send,`location.pathname==='/core' &&
        document.querySelector('.core-subtab.selected')?.textContent?.includes('Listing')`,
        'V100 browser back restores /core')
      await evaluate(send,`(() => {const b=[...document.querySelectorAll('.nav-link')]
        .find(x=>x.textContent.includes('FBA 库存'));if(!b)throw Error('FBA nav');b.click()})()`)
      await waitFor(send,`location.pathname==='/inventory' &&
        document.querySelector('.core-subtab.selected')?.textContent?.includes('FBA 库存') &&
        document.querySelector('.core-data-scroll tbody tr')?.textContent?.includes('TEST-SKU-100')`,
        'V100 real FBA inventory route')
      await screenshot(send,'v100-next-real-fba-360.png',true)
      await evaluate(send,"history.back()")
      await waitFor(send,`location.pathname==='/core' &&
        document.querySelector('.core-subtab.selected')?.textContent?.includes('Listing')`,
        'V100 browser back from FBA')
      await evaluate(send,"history.forward()")
      await waitFor(send,`location.pathname==='/inventory' &&
        document.querySelector('.core-subtab.selected')?.textContent?.includes('FBA 库存')`,
        'V100 browser forward restores FBA')
      await send('Page.reload')
      await waitFor(send,`location.pathname==='/inventory' &&
        document.querySelector('.core-subtab.selected')?.textContent?.includes('FBA 库存') &&
        document.querySelector('.core-data-scroll tbody tr')?.textContent?.includes('TEST-SKU-100')`,
        'V100 hard refresh preserves real FBA deep link')
      await send('Page.navigate',{url:url+'?core=again'})
      await waitFor(send,`location.pathname==='/core' &&
        document.querySelector('.core-subtab.selected')?.textContent?.includes('Listing') &&
        document.querySelectorAll('.core-data-scroll tbody tr').length===2`,
        'V100 return to core baseline')
    }
    if(width===360){
      await evaluate(send,
        "document.querySelector('.core-tools summary')?.click()")
      await waitFor(send,
        "document.querySelector('.orders-sync-panel')?.textContent?.includes('NOT_STARTED')",
        'V11A local sync status')
      assert.equal(await evaluate(send,
        "document.querySelector('.orders-sync-panel')?.textContent?.includes('MOCK ONLY')"),
        true, 'V11A source safety label')
      await evaluate(send,
        "document.querySelector('.orders-sync-panel')?.scrollIntoView({block:'center'})")
      await screenshot(send,'v11-local-orders-worker-360.png')
    }
    if(width===360){
      await select(send,'订单')
      // Both route and source must belong to the same concrete order row.
      // Next may remount the Core section during App Router transitions;
      // a generic row-count check alone can race with that remount.
      await waitFor(send,
        (nextTarget?"location.pathname==='/orders' && ":"")+
        "document.querySelector('.core-subtab.selected')?.textContent?.includes('订单') && "+
        "[...document.querySelectorAll('#core-panel-orders .core-data-scroll tbody tr')]"+
        ".some(row=>row.textContent.includes('TEST-ORDER-100') && "+
        "row.textContent.includes('模拟测试'))",
        'Core Orders route, fixture identity and MOCK source provenance together')
      await layout(send,width,'core-orders-360',true)
      await screenshot(send,'core-orders-360.png',true)
      await select(send,'FBA 库存')
      // Verify unknown and observed zero from separate exact cells of the
      // same SKU row, not via loose text fragments or a stale previous table.
      await waitFor(send,
        (nextTarget?"location.pathname==='/inventory' && ":"")+
        "document.querySelector('.core-subtab.selected')?.textContent?.includes('FBA 库存') && "+
        "(() => {const row=[...document.querySelectorAll("+
        "'#core-panel-inventory .core-data-scroll tbody tr')]"+
        ".find(r=>r.textContent.includes('TEST-SKU-100'));"+
        "if(!row)return false;const cells=[...row.querySelectorAll('td')]"+
        ".map(c=>c.textContent.trim());"+
        "return cells.includes('未知')&&cells.includes('0')})()",
        'Core FBA route preserves UNKNOWN and observed 0 in one fixture row')
      await layout(send,width,'core-inventory-360',true)
      await screenshot(send,'core-inventory-360.png',true)
      await waitFor(send,
        "[...document.querySelectorAll('[aria-label=\"V74 SKU 补货风险优先级表\"] tbody tr')]" +
        ".some(x=>x.textContent.includes('TEST-SKU-100'))",
        'V74 replenishment risk view')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"V74 SKU 补货风险优先级表\"]')" +
        "?.textContent?.includes('阻断 · 不可量化')"),
        true, 'unknown stock must block numerical risk')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"V74 SKU 补货风险优先级表\"]')" +
        "?.textContent?.includes('已有 1 张进行中采购单')"),
        true, 'existing local PO must display manual dedupe review')
      await screenshot(send,'v74-replenishment-risk-360.png')
      await waitFor(send,
        "document.querySelector('[aria-label=\"V76 采购与 FBA 本地货件差异核对表\"] tbody tr')" +
        "?.textContent?.includes('PO-V75-LOCAL')",
        'V76 bounded reconciliation board')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"V76 采购 ASN FBA 本地证据差异工作台\"]')" +
        "?.textContent?.includes('第二 Owner')"),
        true,'Pending V75 proof must be visible as a V76 human-review finding')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"V76 采购 ASN FBA 本地证据差异工作台\"]')" +
        "?.textContent?.includes('不代表 Amazon 平台签收')"),
        true,'Local reconciliation must not imply official Amazon receipt')
      await screenshot(send,'v76-reconciliation-board-360.png')

      try {
      await waitFor(send,
        "Boolean(document.querySelector('[aria-label=\"货件历史表\"] tbody tr button'))",
        'V75 synthetic local shipment list')
      } catch (error) {
        console.error('V75 browser fixture diagnostic:',
          await evaluate(send,
            "({section:document.body.textContent.slice(0,1200)," +
            "tables:document.querySelectorAll('table').length})"))
        throw error
      }
      await evaluate(send,
        "document.querySelector('[aria-label=\"货件历史表\"] tbody tr button')?.click()")
      await waitFor(send,
        "document.querySelector('[aria-label=\"FBA 与采购证据关联表\"] tbody tr')" +
        "?.textContent?.includes('PO-V75-LOCAL')",
        'V75 explicit local PO identity proof')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"V75 FBA 货件与采购 ASN 明确关联\"]')" +
        "?.textContent?.includes('待另一名 Owner 确认')"),
        true,'V75 proof must distinguish proposal from independently confirmed evidence')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"V75 FBA 货件与采购 ASN 明确关联\"]')" +
        "?.textContent?.includes('绝非 Amazon')"),
        true,'V75 local proof never claims external Amazon verification')
      await screenshot(send,'v75-explicit-po-asn-link-360.png')


      await evaluate(send, "(() => {const btn=[...document.querySelectorAll('.nav-link')]" +
        ".find(b=>b.textContent.includes('运营自动化'));if(!btn)throw Error('Missing operations navigation');btn.click();})()")
      await waitFor(send,
        "document.querySelector('.ops-workspace tbody tr')?.textContent?.includes('模拟测试')",
        'operations triage')
      assert.equal(await evaluate(send,
        "document.querySelector('.ops-workspace tbody tr')?.textContent?.includes('FBA 库存数据不完整')"),
        true, 'operations rule label')
      await waitFor(send,
        "[...document.querySelectorAll('[aria-label=\"站点异常规则表\"] tbody tr')].some(x=>x.textContent.includes('库存'))",
        'V69 Marketplace rule settings')
      await layout(send,width,'operations-360')
      await screenshot(send,'operations-360.png')
      // V72: real React purchasing panel must preserve missing carrier ETA
      // instead of fabricating an arrival. This is a synthetic API fixture.
      await evaluate(send,
        "(() => {const b=[...document.querySelectorAll('.nav-link')]" +
        ".find(x=>x.textContent.includes('采购与供应商'));" +
        "if(!b)throw Error('Missing purchasing navigation');b.click();})()")
      await waitFor(send,
        "[...document.querySelectorAll('[aria-label=\"采购单交付追踪表\"] tbody tr')]" +
        ".some(x=>x.textContent.includes('PO-V72-MOCK'))",
        'V72 supplier delivery board')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"采购单交付追踪表\"] tbody tr')" +
        "?.textContent?.includes('已审批但没有有效供应商 ASN')"),
        true,'Missing ASN must not be reported as a shipped or late delivery')
      await waitFor(send,
        "document.querySelector('[aria-label=\"供应商履约样本评分表\"] tbody tr')" +
        "?.textContent?.includes('未评分：样本不足')",
        'V73 supplier evidence scorecard with insufficient samples')
      assert.equal(await evaluate(send,
        "document.querySelector('[aria-label=\"供应商履约评分与采购人工催办\"]')" +
        "?.textContent?.includes('手动扫描待跟进采购')"),
        true,'V73 human-only follow-up workflow must be visible')
      await layout(send,width,'supplier-delivery-360')
      await screenshot(send,'v72-local-supplier-delivery-360.png')
      // V15 Owner-only management surface must render independent reviewers,
      // never an invitation token in the list response.
      await evaluate(send,
        "(() => {const b=[...document.querySelectorAll('.nav-link')]" +
        ".find(x=>x.textContent.includes('团队与权限'));if(!b)throw Error('Missing V15 navigation');b.click();})()")
      try {
        await waitFor(send,
          "[...document.querySelectorAll('.team-workspace tbody tr')].some(x=>x.textContent.includes('ops-reviewer'))",
          'V15 team role list')
      } catch (e) {
        const state=await evaluate(send,
          "({body:document.querySelector('#main-content')?.textContent?.slice(0,1800),team:document.querySelector('.team-workspace')?.textContent?.slice(0,1200)})")
        console.error('V15 browser debug:',JSON.stringify(state))
        throw e
      }
      assert.equal(await evaluate(send,
        "document.querySelector('.team-workspace')?.textContent?.includes('生成邀请')"),
        true,'V15 invite UI should exist')
      await layout(send,width,'team-owner-360')
      await screenshot(send,'team-owner-360.png')
      if(nextTarget){
        // V104: Base UI requires explicit approval and returns keyboard focus.
        // A cancelled purchase action must not issue even a local synthetic POST.
        await evaluate(send,`(() => {const link=[...document.querySelectorAll('.nav-link')]
          .find(x=>x.textContent.includes('采购与供应商'));if(!link)throw Error('Missing purchasing link');link.click()})()`)
        await waitFor(send,
          "document.querySelector('[aria-label=\\\"采购单列表\\\"] tbody tr')?.textContent?.includes('PO-V104-CONFIRM-SYNTHETIC')",
          'V104 synthetic purchase order ready')
        const openCancel=`(() => {const row=[...document.querySelectorAll('[aria-label="采购单列表"] tbody tr')]
          .find(r=>r.textContent.includes('PO-V104-CONFIRM-SYNTHETIC'));
          const button=[...row.querySelectorAll('button')]
            .find(b=>b.textContent.trim()==='取消');
          if(!button)throw Error('No cancellable PO');button.click()})()`
        await evaluate(send,openCancel)
        await waitFor(send,
          "document.querySelector('[role=\\\"alertdialog\\\"]')?.textContent?.includes('确认取消采购单')",
          'V104 Base UI alert dialog visible')
        assert.equal(v104CancelWrites,0,'opening a dialog must not mutate purchase orders')
        assert.equal(await evaluate(send,
          "!!document.activeElement?.closest('.v104-confirm-popup')"),
          true,'V104 action dialog should capture keyboard focus')
        await screenshot(send,'v104-purchase-confirm-360.png')
        await evaluate(send,"document.querySelector('.v104-confirm-cancel')?.click()")
        await waitFor(send,
          "!document.querySelector('[role=\\\"alertdialog\\\"]')",
          'V104 dismissal returns to purchase order')
        assert.equal(v104CancelWrites,0,'cancel action must never write')
        await evaluate(send,openCancel)
        await waitFor(send,
          "!!document.querySelector('.v104-confirm-approve')",
          'V104 confirmation reopen')
        await evaluate(send,"document.querySelector('.v104-confirm-approve')?.click()")
        await waitFor(send,
          "document.querySelector('.core-workspace')?.textContent?.includes('采购单状态已更新：CANCELLED')",
          'V104 confirmed local PO cancel updates status')
        assert.equal(v104CancelWrites,1,'one explicit confirmation must send one local POST')
        await layout(send,width,'v104-confirm-360')
      }
      // Synthetic connected Marketplace must expose a LIVE-gated operations
      // panel outside the local Demo tools. This does not call Amazon.
      mode='connected'
      await send('Page.navigate',{url:url+'?connected='+width})
      await waitFor(send,
        "document.querySelector('.orders-sync-panel')?.textContent?.includes('V11C LIVE')",
        'connected Marketplace LIVE Orders panel')
      assert.equal(await evaluate(send,
        "document.querySelector('.orders-sync-panel')?.textContent?.includes('LIVE · 权限强校验')"),
        true, 'LIVE must be explicitly distinguished from MOCK')
      assert.equal(await evaluate(send,
        "document.querySelectorAll('.orders-sync-panel').length"),1,
        'LIVE checkpoint must be a single panel, not a Mock duplicate')
      await layout(send,width,'connected-live-orders-360')
      await evaluate(send,
        "document.querySelector('.orders-sync-panel')?.scrollIntoView({block:'center'})")
      await screenshot(send,'connected-live-orders-360.png')
    }
  }
  console.log('Chromium responsive UI smoke PASS (synthetic backend only).')
}
try { await main() }
catch(e) { console.error(e);process.exitCode=1 }
finally {
  socket?.close()
  chrome?.kill('SIGTERM')
  server.close()
  if(profile)await rm(profile,{recursive:true,force:true,maxRetries:5}).catch(()=>{})
}
