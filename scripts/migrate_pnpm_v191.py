#!/usr/bin/env python3
"""V191 one-time pnpm-only migration; executed by isolated GitHub Actions, never at runtime."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "apps/web"
PNPM = "12.20.0"

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, body):
    (ROOT / path).write_text(body, encoding="utf-8")

def replace_once(body, old, new, label):
    count = body.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one occurrence, found {count}")
    return body.replace(old, new)

package = json.loads(read("apps/web/package.json"))
assert package.get("packageManager") is None
package["packageManager"] = f"pnpm@{PNPM}"
for key, value in list(package["scripts"].items()):
    package["scripts"][key] = value.replace("npm run ", "pnpm run ")
write("apps/web/package.json", json.dumps(package, ensure_ascii=False, indent=2) + "\n")

write("apps/web/pnpm-workspace.yaml",
"""# Standalone front-end project; only explicitly reviewed build hooks run.
packages:
  - '.'
allowBuilds:
  esbuild: true
  sharp: true
  '@swc/core': true
""")

for file in ("apps/web/Dockerfile", "apps/web/Dockerfile.next"):
    content = read(file)
    content = replace_once(content,
        "COPY package.json package-lock.json ./",
        "RUN corepack enable\nCOPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./", file + " locked dependencies")
    content = replace_once(content,
        "RUN npm ci --no-audit --no-fund",
        "RUN pnpm install --frozen-lockfile", file + " install")
    content = content.replace("RUN npm run ", "RUN pnpm run ")
    write(file, content)

cached_node = """      - uses: actions/setup-node@v7
        with:
          node-version: '24'
          cache: npm
          cache-dependency-path: apps/web/package-lock.json
          package-manager-cache: false"""
pnpm_setup = """      - uses: pnpm/setup@v3
        with:
          working-directory: apps/web
          runtime: node@24
          cache: true
          require-lockfile: true
          install: false"""
node_without_cache = """      - uses: actions/setup-node@v7
        with:
          node-version: '24'"""
for file in (".github/workflows/ci.yml",
             ".github/workflows/browser-layout-smoke.yml",
             ".github/workflows/github-pages.yml"):
    content = read(file)
    content = replace_once(content, cached_node, pnpm_setup, file + " package cache")
    if file.endswith("ci.yml"):
        content = replace_once(content, node_without_cache, pnpm_setup, file + " PG18 toolchain")
    content = content.replace("npm ci --no-audit --no-fund", "pnpm install --frozen-lockfile")
    content = content.replace("npm run ", "pnpm run ")
    if re.search(r"\bnpm\s+(?:ci|install|run|test)\b", content):
        raise RuntimeError(f"{file}: an old package-manager command remains")
    write(file, content)

# Old package-lock is deleted only AFTER pnpm import and frozen install succeed.
guard = r"""#!/usr/bin/env node
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
  'apps/web/Dockerfile','apps/web/Dockerfile.next',
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
"""
write("scripts/check_frontend_stack.mjs", guard)

for file in ("scripts/check_ci_cadence_v108.py","scripts/check_next_runtime_v122.py",
             "scripts/check-orval-generated.mjs"):
    content=read(file)
    content=content.replace("npm ci --no-audit --no-fund","pnpm install --frozen-lockfile")
    content=content.replace("npm run ","pnpm run ")
    write(file,content)

# Update currently actionable docs. Keep old financial audit and version-history
# documents as historical evidence, instead of changing what old CI actually ran.
docs=(
"README.md","README.zh-CN.md","docs/development.md",
"docs/testing/ci-policy.md","docs/deployment/local-spark-db.md",
"docs/deployment/github-pages-public-demo-v98.md",
)
for file in docs:
    content=read(file)
    content=re.sub(r"\bnpm ci\b","pnpm install --frozen-lockfile",content)
    content=re.sub(r"\bnpm run\b","pnpm run",content)
    content=re.sub(r"\bnpm test\b","pnpm test",content)
    content=content.replace("package-lock.json","pnpm-lock.yaml")
    content=content.replace("lockfile v3","pnpm lockfile")
    content=content.replace("npm-registry-resolved","registry-resolved")
    if file=="docs/development.md":
        content=content.replace("**Node.js 24.11+ (24.x)**, npm, Docker Compose",
                                "**Node.js 24.11+ (24.x)**, pnpm 12.20.0, Docker Compose")
        content=content.replace("Node.js 24.11+ (24.x)**, npm, Docker Compose",
                                "Node.js 24.11+ (24.x)**, pnpm 12.20.0, Docker Compose")
        content=content.replace("CI and the Node 24 Docker build both use **pnpm install --frozen-lockfile**",
                                "CI and the Node 24 Docker build both use **pnpm install --frozen-lockfile**")
    write(file,content)

for file in ("AGENTS.md","Agent.md"):
    current=read(file)
    notice=("> **V191 pnpm-only (2026-11-21, overrides historical package-manager references):** "
            "Node 24 + pinned pnpm 12.20.0 is the only frontend package-management tool. "
            "Run pnpm install --frozen-lockfile, pnpm dev, pnpm build, pnpm api:check, "
            "pnpm test; Docker and CI use pnpm-lock.yaml only. "
            "Do not recreate the retired package-lock or use historical npm commands. "
            "Direct commits to main, no development branches. Seller/Ads OAuth OFF; "
            "production #104 NO-GO.\n\n")
    write(file,notice+current)

# GitHub calls its Dependabot JS ecosystem "npm" even for pnpm-lock.yaml.
# This is an API schema identifier, not permission to use another CLI.
dep=read(".github/dependabot.yml")
dep=dep.replace("  - package-ecosystem: npm",
                "  # GitHub Dependabot ecosystem key for JavaScript packages, including pnpm.\n  - package-ecosystem: npm")
write(".github/dependabot.yml",dep)
print("V191 migration files rewritten; pnpm import/frozen install still required")
