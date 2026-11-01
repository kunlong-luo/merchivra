// Contract extracted from running Spring Boot 4.1 + springdoc; never the DESIGN ONLY draft.
import fs from 'node:fs'
const [source,destination]=process.argv.slice(2)
if(!source||!destination)throw new Error('Usage: node normalize-finance-openapi.mjs input.json output.json')
const raw=JSON.parse(fs.readFileSync(source,'utf8'))
const expected=[
 'createProfitReport','listProfitReports','getProfitReport',
 'advanceProfitReport','resumeProfitReport','getProfitReportAudit',
 'exportProfitReportCsv','getSkuProfitPreview','exportSkuProfitPreviewCsv'
]
const selected=Object.fromEntries(Object.entries(raw.paths??{}).filter(([p])=>
 p.startsWith('/api/v1/finance/marketplaces/')&&
 (p.includes('/profit-reports')||p.includes('/sku-profit-preview'))))
const actual=Object.values(selected).flatMap(methods=>
 Object.values(methods).map(op=>op.operationId).filter(Boolean))
for(const op of expected){
 if(actual.filter(x=>x===op).length!==1)throw new Error('Controller contract missing or ambiguous: '+op)
}
if(actual.length!==expected.length)throw new Error('Unexpected endpoint in bounded finance group: '+actual)
const contracts=structuredClone(raw)
contracts.paths=selected
contracts.info={title:'Merchivra verified finance contribution API',
 version:'64.1.0',description:'Generated from LIVE Spring MVC Controller mappings; only local Session authentication.'}
contracts.servers=[{url:''}]
contracts.security=[{sessionCookie:[]}]
contracts.components??={}
contracts.components.securitySchemes={
 sessionCookie:{type:'apiKey',in:'cookie',name:'JSESSIONID'},
 csrfHeader:{type:'apiKey',in:'header',name:'X-CSRF-TOKEN'}
}
for(const path of Object.values(contracts.paths)){
 for(const [method,operation] of Object.entries(path)){
  if(!['get','post','put','delete','patch'].includes(method))continue
  operation.security=['post','put','delete','patch'].includes(method)
   ?[{sessionCookie:[],csrfHeader:[]}]:[{sessionCookie:[]}]
 }
}
delete contracts.externalDocs
function sorted(x){
 if(Array.isArray(x))return x.map(sorted)
 if(x&&typeof x==='object')return Object.fromEntries(
  Object.entries(x).sort(([a],[b])=>a.localeCompare(b,'en')).map(([k,v])=>[k,sorted(v)]))
 return x
}
fs.mkdirSync(destination.slice(0,destination.lastIndexOf('/'))||'.',{recursive:true})
fs.writeFileSync(destination,JSON.stringify(sorted(contracts),null,2)+'\n')
console.log('Verified '+actual.length+' LIVE controller routes')
