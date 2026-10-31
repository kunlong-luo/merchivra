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
const output = resolve(process.argv[3] ?? 'browser-smoke-artifacts')
const tenant = '11111111-1111-4111-8111-111111111111'
const account = '22222222-2222-4222-8222-222222222222'
const market = '33333333-3333-4333-8333-333333333333'
let mode = 'guest'
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
  if (url.pathname === '/api/v1/auth/me') {
    return mode === 'guest' ? respond(res, { error:'Not authenticated' }, 401)
      : respond(res, { userId: account, activeTenantId: tenant, availableTenantIds:[tenant] })
  }
  if (url.pathname === '/api/v1/ping') return respond(res, { status:'ok' })
  if (url.pathname === '/api/v1/orders-sync/' + market)
    return respond(res, {
      jobId:null,state:'NOT_STARTED',committedPages:0,leaseEpoch:0,
      windowAfter:null,windowBefore:null,lastSafeWatermark:null,
      nextAttemptAt:null,lastFailureCode:null
    })
  if (url.pathname === '/api/v1/operations/summary')
    return respond(res, { open:1, acknowledged:0, done:0, cleared:0 })
  if (url.pathname === '/api/v1/operations/findings')
    return respond(res, [{
      id:'77777777-7777-4777-8777-777777777777',
      marketplaceId:market, ruleCode:'INVENTORY_UNKNOWN',
      sourceKey:'TEST-SKU-100', title:'FBA 库存数据不完整',
      severity:'WARNING', state:'OPEN', dataOrigin:'MOCK',
      lastSeenAt:'2026-10-31T00:00:00Z'
    }])
  if (url.pathname.startsWith('/api/v1/')) {
    if (mock.has(url.pathname)) return respond(res, mock.get(url.pathname))
    if (url.pathname.endsWith('/pages')) return respond(res,
      { status:'NOT_STARTED', nextPage:0, totalPages:2, leaseEpoch:0, pages:[] })
    return respond(res, [])
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
  assert.ok(existsSync(join(dist,'index.html')), 'Build Vue first')
  await mkdir(output,{recursive:true})
  await new Promise(ok => server.listen(0,'127.0.0.1',ok))
  const url = 'http://127.0.0.1:' + server.address().port + '/'
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
    mode='core'
    await send('Page.navigate',{url:url+'?core='+width})
    await waitFor(send,"document.querySelectorAll('.core-data-scroll tbody tr').length===2",
      'listing '+width)
    await layout(send,width,'core-listings-'+width,true)
    await screenshot(send,'core-listings-'+width+'.png')
    await screenshot(send,'core-listings-table-'+width+'.png',true)
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
      await layout(send,width,'core-orders-360',true)
      assert.equal(await evaluate(send,
        "[...document.querySelectorAll('.core-data-scroll tbody tr:first-child td')]" +
        ".some(x=>x.textContent.includes('模拟测试'))"),true,
        'Order dataOrigin must be present')
      await screenshot(send,'core-orders-360.png',true)
      await select(send,'FBA 库存')
      await layout(send,width,'core-inventory-360',true)
      const cells=await evaluate(send,
        "[...document.querySelectorAll('.core-data-scroll tbody tr:first-child td')]" +
        ".map(x=>x.textContent.trim())")
      assert.ok(cells.includes('未知'),'Unknown inventory must stay unknown')
      assert.ok(cells.includes('0'),'Observed zero must stay zero')
      await screenshot(send,'core-inventory-360.png',true)
      await evaluate(send, "(() => {const btn=[...document.querySelectorAll('.nav-link')]" +
        ".find(b=>b.textContent.includes('运营自动化'));if(!btn)throw Error('Missing operations navigation');btn.click();})()")
      await waitFor(send,
        "document.querySelector('.ops-workspace tbody tr')?.textContent?.includes('模拟测试')",
        'operations triage')
      assert.equal(await evaluate(send,
        "document.querySelector('.ops-workspace tbody tr')?.textContent?.includes('FBA 库存数据不完整')"),
        true, 'operations rule label')
      await layout(send,width,'operations-360')
      await screenshot(send,'operations-360.png')
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
