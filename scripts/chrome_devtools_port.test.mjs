import {test} from 'node:test'
import assert from 'node:assert/strict'
import {portFromChromeStderr} from './chrome_devtools_port.mjs'

test('Chrome 157 loopback CDP announcement survives noisy DBus stderr',()=>{
  const log='Failed to connect to the bus: Could not parse server address\n' +
    'DevTools listening on ws://127.0.0.1:41161/devtools/browser/uuid\n'
  assert.equal(portFromChromeStderr(log),41161)
})
test('do not connect to non-local, malformed or out-of-range announced ports',()=>{
  for(const log of [
    '',null,'DevTools listening on ws://0.0.0.0:41161/devtools/browser/',
    'DevTools listening on ws://example.com:41161/devtools/browser/',
    'DevTools listening on ws://127.0.0.1:0/devtools/browser/',
    'DevTools listening on ws://127.0.0.1:65536/devtools/browser/',
    'some unrelated DBus warning'
  ])assert.equal(portFromChromeStderr(log),null)
})
