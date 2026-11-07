// V67/V69: only actual booted Spring MVC operations, not DESIGN ONLY docs/api.
import fs from 'node:fs'
const [source,target]=process.argv.slice(2)
if(!source||!target)throw Error('Usage: normalize-ops-openapi.mjs input.json output.json')
const raw=JSON.parse(fs.readFileSync(source,'utf8'))
const expected=['listOperationFindings','getOperationsSummary','scanOperationsNow',
 'setOperationFindingState','claimOperationFinding','releaseOperationFinding',
 'snoozeOperationFinding','unsnoozeOperationFinding',
 'getOperationFindingAttentionAudit',
 'listOperationRules','saveOperationRule','listOperationRuleAudit',
 'listOperationFindingsPage']
const paths=Object.fromEntries(Object.entries(raw.paths??{})
 .filter(([p])=>p==='/api/v1/operations/findings'||
  p.startsWith('/api/v1/operations/findings/')||
  p.startsWith('/api/v1/operations/marketplaces/')||
  p==='/api/v1/operations/summary'||p==='/api/v1/operations/scan'))
const methods=['get','post','put','patch','delete']
const actual=Object.values(paths).flatMap(ops=>Object.entries(ops)
 .filter(([method])=>methods.includes(method)).map(([,operation])=>operation.operationId))
for(const id of expected)
 if(actual.filter(x=>x===id).length!==1)throw Error('Missing/duplicate operations controller: '+id)
if(actual.length!==expected.length)throw Error('Unexpected operations endpoints: '+actual.join(','))
const result=structuredClone(raw)
result.paths=paths
result.info={title:'Merchivra V95 owner operations work queue and rule settings',version:'95.1.0',
 description:'V10/V67/V69/V95 advisory only, MOCK/UNVERIFIED or source-labeled; no Amazon calls.'}
result.servers=[{url:''}]
result.security=[{sessionCookie:[]}]
result.components??={}
result.components.securitySchemes={
 sessionCookie:{type:'apiKey',in:'cookie',name:'JSESSIONID'},
 csrfHeader:{type:'apiKey',in:'header',name:'X-CSRF-TOKEN'}
}
for(const group of Object.values(result.paths))
 for(const [method,operation] of Object.entries(group))
  if(methods.includes(method))operation.security=['get'].includes(method)
   ?[{sessionCookie:[]}]:[{sessionCookie:[],csrfHeader:[]}]
delete result.externalDocs
function sort(o){
 if(Array.isArray(o))return o.map(sort)
 if(o&&typeof o==='object')return Object.fromEntries(Object.entries(o)
  .sort(([a],[b])=>a.localeCompare(b,'en')).map(([k,v])=>[k,sort(v)]))
 return o
}
fs.mkdirSync(target.slice(0,target.lastIndexOf('/'))||'.',{recursive:true})
fs.writeFileSync(target,JSON.stringify(sort(result),null,2)+'\n')
console.log('Verified '+actual.length+' runtime V95 ops routes')
