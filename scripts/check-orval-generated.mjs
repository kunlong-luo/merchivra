// Cross-platform generated-client drift gate: detects both edited and new files.
// Run after npm run api:generate from apps/web.
import {execFileSync} from 'node:child_process'
const changed=execFileSync('git',
 ['status','--porcelain','--untracked-files=all','--','src/generated'],
 {cwd:process.cwd(),encoding:'utf8'})
if(changed.trim()){
 console.error('Generated Orval client differs from committed sources:')
 console.error(changed.trim())
 console.error('Run npm run api:generate, review and commit the updated outputs.')
 process.exitCode=1
}
