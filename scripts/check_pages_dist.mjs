#!/usr/bin/env node
// Checks the PUBLIC static artifact, without opening an API or shipping secrets.
import assert from 'node:assert/strict'
import {readdir,readFile} from 'node:fs/promises'
import {join,resolve} from 'node:path'

const dir=resolve(process.argv[2]??'apps/web/dist')
const files=await readdir(dir)
assert(files.includes('index.html'),'GitHub Pages needs an index.html')
const html=await readFile(join(dir,'index.html'),'utf8')
assert(html.includes('/merchivra/assets/'),
  'Public Vite assets must use the /merchivra/ GitHub Pages base')
assert(!html.includes('http://127.0.0.1:8080'),'Must not include local backend URLs')
assert(!html.includes('MERCHIVRA_BOOTSTRAP_PASSWORD'),'Must not ship bootstrap details')
const js=(await readdir(join(dir,'assets'))).filter(x=>x.endsWith('.js'))
assert(js.length>0,'Public preview needs a bundled React entry')
const joined=(await Promise.all(js.map(f=>readFile(join(dir,'assets',f),'utf8')))).join('')
assert(joined.includes('PUBLIC SHOWCASE')||joined.includes('SYNTHETIC'),
 'Public bundle must contain conspicuous synthetic-data warning')
assert(!joined.includes('/api/v1/auth/login'),
 'Public bundle must not include the self-hosted login implementation')
assert(!joined.includes('/api/v1/seller-accounts/'),
 'Public bundle must not include Seller authorization clients')
console.log('PASS GitHub Pages public static: /merchivra assets, mock warning, no login/OAuth clients')
