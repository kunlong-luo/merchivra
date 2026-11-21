#!/usr/bin/env node
/**
 * V153/V154/V155: execute the ACTUAL effective Compose healthcheck expression
 * against offline fake Spring and Actuator replies. No network, credentials
 * or Docker runtime is required beyond docker compose config --format json.
 */
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {runInNewContext} from 'node:vm'

const compose=JSON.parse(readFileSync(0,'utf8'))
const cmd=compose.services?.['next-web']?.healthcheck?.test
assert.deepEqual(cmd?.slice(0,3),['CMD','node','-e'])
assert.equal(cmd?.length,4)
const program=cmd[3]
assert.equal(typeof program,'string')
const PING_URL='http://127.0.0.1:3000/api/v1/ping'
const HEALTH_URL='http://server:8080/actuator/health'
const correctPing={service:'merchivra',status:'ok',stage:'M0'}
const ready={status:'UP'}
const cases=[
  {label:'Spring identity and PostgreSQL-backed Actuator UP',expected:0,requests:2},
  // Spring Boot Actuator uses vendor-specific JSON by default; never
  // confuse correct v2/v3 vendor JSON with arbitrary/HTML/JSONP payloads.
  {label:'Actuator v3 vendor JSON accepted',healthType:'application/vnd.spring-boot.actuator.v3+json',expected:0,requests:2},
  {label:'Actuator v2 vendor JSON accepted',healthType:'application/vnd.spring-boot.actuator.v2+json',expected:0,requests:2},
  {label:'Actuator vendor JSON charset and case accepted',healthType:'APPLICATION/VND.SPRING-BOOT.ACTUATOR.V3+JSON; charset=UTF-8',expected:0,requests:2},
  {label:'Standard JSON charset accepted',healthType:'application/json; charset=utf-8',expected:0,requests:2},
  {label:'Non-Spring vendor JSON rejected',healthType:'application/vnd.other.actuator.v3+json',expected:1,requests:2},
  {label:'text-json rejected',healthType:'text/json',expected:1,requests:2},
  {label:'JSONP content type rejected',healthType:'application/jsonp',expected:1,requests:2},
  {label:'Unreviewed Actuator v4 rejected',healthType:'application/vnd.spring-boot.actuator.v4+json',expected:1,requests:2},
  {label:'Next or nginx HTML 200 cannot fake Spring',pingType:'text/html',expected:1,requests:1},
  {label:'wrong Spring identity',pingJson:{service:'other',status:'ok',stage:'M0'},expected:1,requests:1},
  {label:'missing Spring stage',pingJson:{service:'merchivra',status:'ok'},expected:1,requests:1},
  {label:'wrong Spring stage',pingJson:{service:'merchivra',status:'ok',stage:'MOCK'},expected:1,requests:1},
  {label:'Spring Ping degraded',pingJson:{service:'merchivra',status:'degraded',stage:'M0'},expected:1,requests:1},
  {label:'Spring 503',pingStatus:503,expected:1,requests:1},
  {label:'Spring 401',pingStatus:401,expected:1,requests:1},
  {label:'Spring redirect',pingStatus:302,expected:1,requests:1},
  {label:'Spring empty JSON',pingJson:null,expected:1,requests:1},
  {label:'Spring malformed JSON',badJsonAt:'ping',expected:1,requests:1},
  {label:'Spring disconnected',errorAt:'ping',expected:1,requests:1},
  {label:'PostgreSQL disconnected returns Actuator DOWN',healthJson:{status:'DOWN'},healthStatus:503,expected:1,requests:2},
  {label:'PostgreSQL down but gateway reports HTTP 200',healthJson:{status:'DOWN'},expected:1,requests:2},
  {label:'Actuator missing status',healthJson:{},expected:1,requests:2},
  {label:'Actuator invalid HTML 200',healthType:'text/html',expected:1,requests:2},
  {label:'Actuator redirect',healthStatus:302,expected:1,requests:2},
  {label:'Actuator unauthorized',healthStatus:401,expected:1,requests:2},
  {label:'Actuator unavailable',healthStatus:503,expected:1,requests:2},
  {label:'Actuator malformed JSON',badJsonAt:'health',expected:1,requests:2},
  {label:'Actuator transport error',errorAt:'health',expected:1,requests:2},
  {label:'Actuator null response',healthJson:null,expected:1,requests:2},
]
for(const scenario of cases){
  const requested=[]
  let finish
  const done=new Promise(resolve=>{finish=resolve})
  const fetch=async(url,options)=>{
    requested.push(url)
    assert.ok(url===PING_URL||url===HEALTH_URL,
      'Only fixed internal proxy and Spring DNS requests are permitted')
    assert.equal(options.redirect,'manual')
    assert.equal(options.cache,'no-store')
    assert.ok(options.signal instanceof AbortSignal)
    assert.ok(requested.length<=2,'No retries or unexpected extra requests')
    const isHealth=url===HEALTH_URL
    if(isHealth)assert.equal(requested[0],PING_URL,
      'Cannot probe private Actuator until Spring identity is validated')
    if(scenario.errorAt===(isHealth?'health':'ping'))throw Error('offline injected transport error')
    const status=isHealth?(scenario.healthStatus??200):(scenario.pingStatus??200)
    return {
      status,
      headers:{get(key){
        assert.equal(key,'content-type')
        return (isHealth?scenario.healthType:scenario.pingType)??'application/json;charset=utf-8'
      }},
      async json(){
        if(scenario.badJsonAt===(isHealth?'health':'ping'))
          throw new SyntaxError('offline invalid JSON')
        return isHealth?(scenario.healthJson===undefined?ready:scenario.healthJson):
                        (scenario.pingJson===undefined?correctPing:scenario.pingJson)
      }
    }
  }
  runInNewContext(program,{fetch,AbortSignal,process:{exit(code){finish(code)}}},
    {timeout:1500})
  const timeout=new Promise((_,reject)=>{
    setTimeout(()=>reject(new Error('Health probe omitted exit: '+scenario.label)),1500).unref?.()
  })
  const result=await Promise.race([done,timeout])
  assert.equal(result,scenario.expected,scenario.label)
  assert.deepEqual(requested,scenario.requests===2?[PING_URL,HEALTH_URL]:[PING_URL],
    'Health probe did not run only expected endpoints: '+scenario.label)
}
console.log('PASS V155: 30 offline Compose healthcheck cases; Spring identity AND private Actuator UP required')
