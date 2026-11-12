// Extract only actual Spring MVC mappings; never the DESIGN ONLY draft.
import fs from 'node:fs'
const [source,destination]=process.argv.slice(2)
if(!source||!destination)throw new Error('Usage: node normalize-fba-openapi.mjs input.json output.json')
const raw=JSON.parse(fs.readFileSync(source,'utf8'))
const expected=[
 'listFbaInboundShipments','listFbaInboundShipmentPage','listFbaInboundTransit','getFbaInboundShipment','createFbaInboundShipment',
 'dispatchFbaInboundShipment','recordFbaInboundReceipt','startFbaInboundReview',
 'resolveFbaInboundVariance','finalizeFbaInboundReconciliation',
 'listFbaPurchaseLinks','listFbaPurchaseCandidates','proposeFbaPurchaseLink',
 'confirmFbaPurchaseLink','revokeFbaPurchaseLink'
]
const methods=['get','post','put','patch','delete']
const selected=Object.fromEntries(Object.entries(raw.paths??{})
 .filter(([p])=>p.startsWith('/api/v1/fba/marketplaces/')&&p.includes('/shipments')))
const actual=Object.values(selected).flatMap(operations=>
 Object.entries(operations).filter(([method])=>methods.includes(method))
  .map(([,operation])=>operation.operationId))
for(const name of expected)
 if(actual.filter(x=>x===name).length!==1)throw Error('Missing/duplicate live FBA operation: '+name)
if(actual.length!==expected.length)throw Error('Unexpected FBA operations: '+actual.join(','))
const result=structuredClone(raw)
result.info={title:'Merchivra verified local FBA inbound API',version:'145.0.0',
 description:'Owner Session; MOCK/MANUAL_UNVERIFIED evidence only. No Amazon writes.'}
result.paths=selected
result.servers=[{url:''}]
result.security=[{sessionCookie:[]}]
result.components??={}
// Keep only DTOs reachable from the reviewed local shipment/link operations.
// V145 includes the existing V75 provenance endpoints in the generated client.
const modelNames=new Set(['Create','Detail','Line','LineInput',
  'Receipt','ReceiptEvent','Resolve','Shipment','ShipmentPage','TransitRow',
  'Candidate','Linked','Propose','Reason'])
result.components.schemas=Object.fromEntries(Object.entries(result.components.schemas??{})
  .filter(([name])=>modelNames.has(name)))
// Fail closed if extending the endpoint set leaves a dangling DTO reference.
function verifySchemaRefs(value){
 if(Array.isArray(value))return value.forEach(verifySchemaRefs)
 if(!value||typeof value!=='object')return
 if(typeof value.$ref==='string'&&value.$ref.startsWith('#/components/schemas/')){
  const name=value.$ref.slice('#/components/schemas/'.length)
  if(!Object.hasOwn(result.components.schemas,name))
   throw Error('Unresolved local FBA schema: '+name)
 }
 Object.values(value).forEach(verifySchemaRefs)
}
verifySchemaRefs(result.paths)
verifySchemaRefs(result.components.schemas)
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
console.log('Verified '+actual.length+' actual local FBA shipment operations')
