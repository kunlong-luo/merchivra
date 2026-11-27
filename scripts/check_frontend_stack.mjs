#!/usr/bin/env node
import {readFileSync,readdirSync,statSync,existsSync} from 'node:fs'
import {join,resolve} from 'node:path'

const root=resolve(import.meta.dirname,'..')
const web=join(root,'apps','web')
const manifest=JSON.parse(readFileSync(join(web,'package.json'),'utf8'))
const lockfile=join(web,'pnpm-lock.yaml')
if(manifest.packageManager!=='pnpm@12.20.0' || !existsSync(lockfile)){
  throw new Error('Expected the pinned pnpm package manager and an installed lockfile')
}
if(existsSync(join(web,'package-lock.json'))){
  throw new Error('The retired lockfile must not be committed')
}
const lock=readFileSync(lockfile,'utf8')
if(!/^lockfileVersion:\s*['"]?9[.]0/m.test(lock) || !lock.includes('importers:')){
  throw new Error('Invalid pnpm-lock.yaml structure')
}
const forbidden=/^(vue$|vue-|@vue\/|@vitejs\/plugin-vue|@tanstack\/vue-query|pinia$|vue-router$|nuxt$)/
const deps=Object.keys({...manifest.dependencies,...manifest.devDependencies})
const locked=[...lock.matchAll(/^  ['"]?((?:@[^/\s'"]+\/)?[^@\s'":]+)@/gm)].map(x=>x[1])
function walk(dir){
  return readdirSync(dir).flatMap(entry=>{
    const path=join(dir,entry)
    return statSync(path).isDirectory()?walk(path):[path]
  })
}
const vue=walk(join(web,'src')).filter(p=>p.endsWith('.vue'))
const rejected=[...new Set([...deps,...locked].filter(x=>forbidden.test(x)))]
const relevant=[
  'apps/web/Dockerfile.next',
  '.github/workflows/ci.yml','.github/workflows/github-pages.yml',
  '.github/workflows/browser-layout-smoke.yml','scripts/check_ci_cadence_v108.py',
  'scripts/check_next_runtime_v122.py','scripts/check-orval-generated.mjs',
]
const old=[]
for(const path of relevant){
  if(/\bnpm\s+(?:ci|install|run|test)\b/.test(readFileSync(join(root,path),'utf8')))old.push(path)
}
if(rejected.length||vue.length||old.length){
  throw new Error(JSON.stringify({unwantedPackages:rejected,vueFiles:vue,oldCommands:old}))
}
console.log('PASS V191: pnpm@12.20.0 frozen-lockfile, no old lockfile/CLI or Vue dependencies')
