// Ensure Orval purchasing client exists before any build/test/typecheck,
// including clean CI and Docker/Next standalone builds. Keep generator output
// ignored but reproducible from the reviewed OpenAPI contract.
import {createHash} from 'node:crypto'
import {execFileSync} from 'node:child_process'
import {existsSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs'
import {join} from 'node:path'
const root=process.cwd()
const spec=join(root,'openapi/purchasing-v114.json')
const client=join(root,'src/generated/purchasing-v114.ts')
const hashFile=join(root,'src/generated/.purchasing-v114-hash')
const hash=createHash('sha256').update(readFileSync(spec)).digest('hex')
const old=existsSync(hashFile)?readFileSync(hashFile,'utf8').trim():''
if(process.argv.includes('--force')||!existsSync(client)||old!==hash){
 const bin=join(root,'node_modules/.bin/orval'+(process.platform==='win32'?'.cmd':''))
 execFileSync(bin,['--config','orval.purchasing.config.ts'],{
  cwd:root,stdio:'inherit'
 })
 mkdirSync(join(root,'src/generated'),{recursive:true})
 writeFileSync(hashFile,hash+'\n')
}
