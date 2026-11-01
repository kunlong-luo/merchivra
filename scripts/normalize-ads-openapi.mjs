import fs from 'node:fs'
// V66 contract from a running Spring Boot 4 controller, never DESIGN ONLY JSON.
const [source,destination]=process.argv.slice(2)
if(!source||!destination)throw Error('Usage: node normalize-ads-openapi.mjs input.json output.json')
const input=JSON.parse(fs.readFileSync(source,'utf8'))
const expected=['getAdsAuthorizationCapability','listAdsProfiles','registerAdsProfile',
 'listAdsReports','importAdsReport','getAdsReport','reviewAdsReport','listReviewedAdsCosts']
const allowed=['get','post','put','patch','delete']
const paths=Object.fromEntries(Object.entries(input.paths??{})
 .filter(([key])=>key.startsWith('/api/v1/ads/marketplaces/')))
const actual=Object.values(paths).flatMap(p=>Object.entries(p)
 .filter(([method])=>allowed.includes(method)).map(([,value])=>value.operationId))
for(const id of expected)
 if(actual.filter(x=>x===id).length!==1)throw Error('Missing or duplicate runtime Ads endpoint: '+id)
if(actual.length!==expected.length)throw Error('Extra Ads endpoints found: '+actual.join(','))
const out=structuredClone(input)
out.paths=paths
out.info={title:'Merchivra verified offline Ads report API',version:'66.1.0',
 description:'Only MOCK/MANUAL_UNVERIFIED; Ads OAuth and network access disabled.'}
out.servers=[{url:''}]
out.security=[{sessionCookie:[]}]
out.components??={}
out.components.securitySchemes={
 sessionCookie:{type:'apiKey',in:'cookie',name:'JSESSIONID'},
 csrfHeader:{type:'apiKey',in:'header',name:'X-CSRF-TOKEN'}
}
for(const ops of Object.values(out.paths))
 for(const [method,operation] of Object.entries(ops))
  if(allowed.includes(method))
   operation.security=['post','put','patch','delete'].includes(method)
    ?[{sessionCookie:[],csrfHeader:[]}]:[{sessionCookie:[]}]
delete out.externalDocs
function sorted(value){
 if(Array.isArray(value))return value.map(sorted)
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value)
  .sort(([a],[b])=>a.localeCompare(b,'en')).map(([k,v])=>[k,sorted(v)]))
 return value
}
fs.mkdirSync(destination.slice(0,destination.lastIndexOf('/'))||'.',{recursive:true})
fs.writeFileSync(destination,JSON.stringify(sorted(out),null,2)+'\n')
console.log('Verified '+actual.length+' runtime Ads operations')
