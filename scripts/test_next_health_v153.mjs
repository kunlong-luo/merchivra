#!/usr/bin/env node
/**
 * V153: test the ACTUAL effective Compose healthcheck JS, offline.
 * Every response is mocked; this script must not contact a running service.
 * Input: 'docker compose ... config --format json' on stdin.
 */
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {runInNewContext} from 'node:vm'

const config=JSON.parse(readFileSync(0,'utf8'))
const cmd=config.services?.['next-web']?.healthcheck?.test
assert.deepEqual(cmd?.slice(0,3),['CMD','node','-e'])
assert.equal(cmd?.length,4)
const probe=cmd[3]
assert.equal(typeof probe,'string')
const cases=[
  {label:'real Spring PingController',status:200,json:{service:'merchivra',status:'ok',stage:'M0'},expected:0},
  {label:'Next or Nginx HTML with HTTP 200',status:200,type:'text/html',json:{service:'merchivra',status:'ok',stage:'M0'},expected:1},
  {label:'unexpected backend identity',status:200,json:{service:'other',status:'ok',stage:'M0'},expected:1},
  {label:'missing stage',status:200,json:{service:'merchivra',status:'ok'},expected:1},
  {label:'wrong stage',status:200,json:{service:'merchivra',status:'ok',stage:'MOCK'},expected:1},
  {label:'Spring no longer ready',status:200,json:{service:'merchivra',status:'degraded',stage:'M0'},expected:1},
  {label:'backend 503',status:503,json:{service:'merchivra',status:'ok',stage:'M0'},expected:1},
  {label:'backend 401',status:401,json:{service:'merchivra',status:'ok',stage:'M0'},expected:1},
  {label:'redirect to login',status:302,json:{service:'merchivra',status:'ok',stage:'M0'},expected:1},
  {label:'empty JSON',status:200,json:null,expected:1},
  {label:'malformed JSON body',status:200,rejectJson:true,expected:1},
  {label:'backend disconnected',rejectFetch:true,expected:1},
]
for(const c of cases){
  let seen=0
  let resolveExit
  const done=new Promise(resolve=>{resolveExit=resolve})
  const fetch=async(url,options)=>{
    seen++
    assert.equal(url,'http://127.0.0.1:3000/api/v1/ping')
    assert.equal(options.redirect,'manual')
    assert.equal(options.cache,'no-store')
    assert.ok(options.signal instanceof AbortSignal)
    if(c.rejectFetch)throw new Error('synthetic connection failure')
    return {
      status:c.status,
      headers:{get(key){
        assert.equal(key,'content-type')
        return c.type??'application/json;charset=UTF-8'
      }},
      async json(){
        if(c.rejectJson)throw new SyntaxError('synthetic invalid JSON')
        return c.json
      }
    }
  }
  runInNewContext(probe,{fetch,AbortSignal,process:{exit(code){resolveExit(code)}}},
    {timeout:1500})
  const timeout=new Promise((_,reject)=>{
    setTimeout(()=>reject(new Error('Probe omitted process.exit for '+c.label)),1500).unref?.()
  })
  const exitCode=await Promise.race([done,timeout])
  assert.equal(exitCode,c.expected,'Unexpected readiness for '+c.label)
  assert.equal(seen,1,'Expected exactly one bounded local fetch: '+c.label)
}
console.log('PASS V153: 12 offline Node healthcheck cases; only real Spring JSON is healthy')
