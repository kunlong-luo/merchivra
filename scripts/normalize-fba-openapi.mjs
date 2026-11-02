// Extract only actual Spring MVC mappings; never the DESIGN ONLY draft.
import fs from 'node:fs'
const [source,destination]=process.argv.slice(2)
if(!source||!destination)throw new Error('Usage: node normalize-fba-openapi.mjs input.json output.json')
const raw=JSON.parse(fs.readFileSync(source,'utf8'))
const expected=[
 'listFbaInboundShipments','listFbaInboundTransit','getFbaInboundShipment','createFbaInboundShipment',
 'dispatchFbaInboundShipment','recordFbaInboundReceipt','startFbaInboundReview',
 'resolveFbaInboundVariance','finalizeFbaInboundReconciliation'
]
const methods=['get','post','put','patch','delete']
const selected=Object.fromEntries(Object.entries(raw.paths??{})
 .filter(([p])=>p.startsWith('/api/v1/fba/marketplaces/')&&p.includes('/shipments')
   && !p.includes('/purchase-links')))
const actual=Object.values(selected).flatMap(operations=>
 Object.entries(operations).filter(([method])=>methods.includes(method))
  .map(([,operation])=>operation.operationId))
for(const name of expected)
 if(actual.filter(x=>x===name).length!==1)throw Error('Missing/duplicate live FBA operation: '+name)
if(actual.length!==expected.length)throw Error('Unexpected FBA operations: '+actual.join(','))
const result=structuredClone(raw)
result.info={title:'Merchivra verified local FBA inbound API',version:'65.1.0',
 description:'Owner Session; MOCK/MANUAL_UNVERIFIED evidence only. No Amazon writes.'}
result.paths=selected
result.servers=[{url:''}]
result.security=[{sessionCookie:[]}]
result.components??={}
result.components.securitySchemes={
 sessionCookie:{type:'apiKey',in:'cookie',name:'JSESSIONID'},
 csrfHeader:{type:'apiKey',in:'header',name:'X-CSRF-TOKEN'}
}
for(const operations of Object.values(result.paths))
 for(const [method,operation] of Object.entries(operations))
  if(methods.includes(method))
   operation.security=['post','put','patch','delete'].includes(method)
    ?[{sessionCookie:[],csrfHeader:[]}]:[{sessionCookie:[]}]
delete result.externalDocs
function sort(x){
 if(Array.isArray(x))return x.map(sort)
 if(x&&typeof x==='object')
  return Object.fromEntries(Object.entries(x)
   .sort(([a],[b])=>a.localeCompare(b,'en')).map(([k,v])=>[k,sort(v)]))
 return x
}
fs.mkdirSync(destination.slice(0,destination.lastIndexOf('/'))||'.',{recursive:true})
fs.writeFileSync(destination,JSON.stringify(sort(result),null,2)+'\n')
console.log('Verified '+actual.length+' LIVE FBA shipment operations')
