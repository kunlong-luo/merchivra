// Ensure reviewed Orval contracts exist before dev/build/test/typecheck.
// Purchasing V114 and manual Owner cost batch V228 both use generated clients;
// outputs are ignored and are NEVER hand-edited.
import {createHash} from 'node:crypto'
import {execFileSync} from 'node:child_process'
import {existsSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs'
import {join} from 'node:path'
const root=process.cwd()
const specs=[
 {spec:'openapi/purchasing-v114.json',
  client:'src/generated/purchasing-v114.ts',
  hash:'src/generated/.purchasing-v114-hash',
  config:'orval.purchasing.config.ts'},
 {spec:'openapi/manual-cost-v228.json',
  client:'src/generated/manual-cost-v228.ts',
  hash:'src/generated/.manual-cost-v228-hash',
  config:'orval.manual-cost.config.ts'}
]
for(const item of specs){
 const bytes=readFileSync(join(root,item.spec))
 const expected=createHash('sha256').update(bytes).digest('hex')
 const hashFile=join(root,item.hash)
 const existing=existsSync(hashFile)?readFileSync(hashFile,'utf8').trim():''
 if(process.argv.includes('--force')
    || !existsSync(join(root,item.client)) || existing!==expected){
  const bin=join(root,'node_modules/.bin/orval'+
    (process.platform==='win32'?'.cmd':''))
  execFileSync(bin,['--config',item.config],{cwd:root,stdio:'inherit'})
  mkdirSync(join(root,'src/generated'),{recursive:true})
  writeFileSync(hashFile,expected+'\n')
 }
}
