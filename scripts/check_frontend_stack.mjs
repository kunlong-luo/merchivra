import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const frontend = join(root, 'apps', 'web')
const manifest = JSON.parse(readFileSync(join(frontend, 'package.json'), 'utf8'))
const lock = JSON.parse(readFileSync(join(frontend, 'package-lock.json'), 'utf8'))
const forbidden = /^(vue$|vue-|@vue\/|@vitejs\/plugin-vue|@tanstack\/vue-query|pinia$|vue-router$|nuxt$)/
const dependencies = Object.keys({ ...manifest.dependencies, ...manifest.devDependencies })
const locked = Object.keys(lock.packages ?? {}).map(key => key.replace(/^node_modules\//, ''))
function walk(dir) {
  return readdirSync(dir).flatMap(entry => {
    const path = join(dir, entry)
    return statSync(path).isDirectory() ? walk(path) : [path]
  })
}
const vueSourceFiles = walk(join(frontend, 'src')).filter(path => path.endsWith('.vue'))
const unwanted = [...new Set([...dependencies, ...locked].filter(name => forbidden.test(name)))]
if (unwanted.length || vueSourceFiles.length) {
  console.error('Vue dependencies found:', unwanted)
  console.error('Vue SFC found:', vueSourceFiles)
  process.exitCode = 1
} else {
  console.log('React-only check passed: no Vue dependencies or .vue source files')
}
