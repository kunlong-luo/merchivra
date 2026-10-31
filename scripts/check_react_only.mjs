import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const web = join(root, 'apps', 'web')
const pkg = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8'))
const names = Object.keys({ ...pkg.dependencies, ...pkg.devDependencies })
const forbidden = names.filter(name =>
  /^vue$|^@vue\/|vue-query|pinia|vue-router|nuxt|plugin-vue|vue-tsc/i.test(name))
function allFiles(dir) {
  return readdirSync(dir).flatMap(name => {
    const file = join(dir, name)
    return statSync(file).isDirectory() ? allFiles(file) : [file]
  })
}
const legacy = allFiles(join(web, 'src')).filter(path => /\.vue$/.test(path))
if (forbidden.length || legacy.length) {
  console.error('Forbidden Vue dependencies:', forbidden)
  console.error('Forbidden Vue components:', legacy)
  process.exit(1)
}
console.log('React-only frontend guard passed')
