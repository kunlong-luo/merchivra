#!/usr/bin/env python3
"""V109: stdlib-only guard for authoritative documentation entry points.

This does not claim to audit every historical Vxx document, every on-page
fragment or any remote URL. It checks navigable local links and prevents
reintroduction of known, dangerous current-state contradictions. Safe for
quick main-push CI: read-only, no credentials, network or production database.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ACTIVE_FILES = (
    "AGENTS.md",
    "README.md",
    "README.zh-CN.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "DESIGN.md",
    "docs/README.md",
    "docs/current-status.md",
    "docs/roadmap/current-plan.md",
    "docs/governance/docs-audit-v191.md",
    "docs/governance/docs-audit-2026-11-22.md",
    "docs/governance/document-control.md",
    "docs/governance/engineering-standards.md",
    "docs/development.md",
    "docs/architecture/overview.md",
    "docs/architecture/deployment.md",
    "docs/design/navigation-and-page-states.md",
    "docs/architecture/diagram-index.md",
    "docs/product/core-flows.md",
    "docs/architecture/adrs/template.md",
    "docs/governance/g2-review-2026-10-30.md",
    "docs/governance/review-report-2026-10-30.md",
    "docs/operations/operations.md",
    "docs/architecture/open-source-erp-benchmark-2026-11-08.md",
    "docs/purchasing/local-purchase-workflow.md",
    "docs/purchasing/purchase-order-pages-v110.md",
    "docs/api/README.md",
    "docs/deployment/local-spark-db.md",
    "docs/roadmap.md",
    "docs/roadmap/mvp-freeze-release-v108.md",
    "docs/governance/docs-audit-v109.md",
    "docs/requirements/traceability.md",
    "docs/testing/ci-policy.md",
    "docs/testing/release-readiness-v68.md",
)

# Markdown's basic inline-link form. We explicitly do not scan code/HTML
# or remote anchors; on-disk existence is the intended quick gate.
INLINE = re.compile(r"(?<!!)\[[^\]\n]+\]\(([^)\n]+)\)")


def load(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


errors: list[str] = []
tested = 0
for relative in ACTIVE_FILES:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"{relative}: entry document missing")
        continue
    source = path.read_text(encoding="utf-8")
    for found in INLINE.finditer(source):
        raw = found.group(1).strip()
        # Optional markdown title: [label](file.md "title")
        href = raw.split(' "', 1)[0].strip().strip("<>")
        parts = urlsplit(href)
        if parts.scheme or parts.netloc or href.startswith(("#", "//", "mailto:")):
            continue
        target = unquote(parts.path)
        if not target:
            continue
        if target.startswith("/"):
            errors.append(f"{relative}: absolute local link: {href}")
            continue
        resolved = (path.parent / target).resolve()
        if not resolved.is_relative_to(ROOT):
            errors.append(f"{relative}: path escapes repository: {href}")
        elif not resolved.exists():
            errors.append(f"{relative}: broken relative link: {href}")
        tested += 1

build = load("apps/server/build.gradle")
frontend = json.loads(load("apps/web/package.json"))
arch = load("docs/architecture/overview.md")
hub = load("docs/README.md")
ci = load("docs/testing/ci-policy.md")
security = load("SECURITY.md")

if "version '4.1.2'" not in build or "4.1.2" not in arch:
    errors.append("Spring Boot 4.1.2 baseline out of sync")
if "languageVersion = JavaLanguageVersion.of(25)" not in build or "Java 25" not in arch:
    errors.append("Java 25 Gradle baseline out of sync")
if frontend["dependencies"].get("next") != "16.4.5":
    errors.append("Next.js 16.4.5 expectation must be reviewed against package.json")
if "PostgreSQL 18" not in arch or "PostgreSQL 18" not in load("docs/development.md"):
    errors.append("PostgreSQL 18 current requirement missing")
if 'Web / Vue 3' in arch or 'Maven、PostgreSQL 17' in arch:
    errors.append("Retired current architecture baseline reintroduced")
if "不存在已完成的 Amazon ERP 业务系统" in hub:
    errors.append("Design-only 2026-10-30 statement reintroduced in current docs hub")
if "milestone-validation.yml" not in ci or "full_suite=true" not in ci:
    errors.append("V108 risk-tiered full milestone CI navigation missing")
if "pre-alpha" not in security.lower() or "not suitable for production" not in security.lower():
    errors.append("Security entry must continue to state production is forbidden")
if "G1/G2/G3" not in load("docs/governance/docs-audit-v109.md"):
    errors.append("Doc audit must preserve unsigned business/architecture gates")


# V191 authoritative-current-docs gate. Version-specific historical specs must
# remain traceable, but cannot override today's Next/pnpm/main-only/NO-GO rules.
runtime = load("docs/current-status.md")
active_plan = load("docs/roadmap/current-plan.md")
hub = load("docs/README.md")
development = load("docs/development.md")
contributing = load("CONTRIBUTING.md")
deploy = load("docs/deployment/local-spark-db.md")
architecture = load("docs/architecture/overview.md")
ci_policy = load("docs/testing/ci-policy.md")
design = load("DESIGN.md")
package_lock = ROOT / "apps/web/pnpm-lock.yaml"
old_lock = ROOT / "apps/web/package-lock.json"

if frontend.get("packageManager") != "pnpm@12.20.0":
    errors.append("Frontend packageManager must pin pnpm 12.20.0")
if not package_lock.is_file() or old_lock.exists():
    errors.append("Use only apps/web/pnpm-lock.yaml; do not recreate package-lock.json")
if not frontend["scripts"].get("dev", "").startswith("next dev "):
    errors.append("Default development web must be Next.js")
if frontend["scripts"].get("build") != "next build":
    errors.append("Default production build must be Next.js")
if any(key.endswith(":vite") for key in frontend["scripts"]):
    errors.append("Retired Vite ERP scripts must not return")
if frontend["scripts"].get("build:pages") != "vite build":
    errors.append("Public Pages showcase must remain separate")
for filepath in (
    "AGENTS.md", "README.md", "README.zh-CN.md",
    "CONTRIBUTING.md", "DESIGN.md", "docs/README.md", "docs/current-status.md", "docs/roadmap/current-plan.md",
    "docs/development.md", "docs/architecture/overview.md",
    "docs/governance/engineering-standards.md", "docs/governance/document-control.md",
    "docs/deployment/local-spark-db.md", "docs/testing/ci-policy.md",
):
    content = load(filepath)
    if re.search(r"\bnpm\s+(?:ci|install|run|test|dev|build)\b", content):
        errors.append(f"{filepath}: retired npm CLI in current documentation")
for heading in ("current-status.md", "roadmap/current-plan.md", "development.md",
                "testing/ci-policy.md", "governance/docs-audit-v191.md",
                "governance/docs-audit-2026-11-22.md",
                "governance/engineering-standards.md",
                "governance/document-control.md"):
    if heading not in hub:
        errors.append(f"Docs hub does not link the canonical document: {heading}")
for context, content in (("runtime", runtime), ("plan", active_plan),
                         ("contributing", contributing), ("development", development),
                         ("CI", ci_policy)):
    if "main" not in content or "pnpm" not in content:
        errors.append(f"V191 main-only/pnpm policy missing: {context}")
for context, content in (("runtime", runtime), ("plan", active_plan),
                         ("architecture", architecture), ("CI", ci_policy)):
    if "#104" not in content or "NO-GO" not in content:
        errors.append(f"V191 safety gate missing: {context}")
# DOC-196: prevent a second contradictory Agent instruction stack.
agents = load("AGENTS.md")
engineering = load("docs/governance/engineering-standards.md")
governance = load("docs/governance/document-control.md")
audit_196 = load("docs/governance/docs-audit-2026-11-22.md")
if len(agents) > 15000 or "唯一生效的 Agent 指令入口" not in agents:
    errors.append("AGENTS.md must be a concise, single canonical Agent policy")
# V231: The old mixed-case legacy alias has no runtime purpose. It was
# deliberately deleted; do not recreate a second Agent instruction file.
if (ROOT / "Agent.md").exists():
    errors.append("Retired Agent.md alias reintroduced: use AGENTS.md only")
if (ROOT / "docs/purchasing/supplier-followup-return-trace-v120.md").exists():
    errors.append("Obsolete V120 pending-PR duplicate reintroduced")
for path, content in (
    ("AGENTS.md", agents), ("engineering", engineering),
    ("governance", governance), ("docs hub", hub),
):
    if "main" not in content or "NO-GO" not in content:
        errors.append(f"DOC-196 policy context missing: {path}")
for path, content in (("AGENTS.md", agents), ("engineering", engineering)):
    if "pnpm 12.20.0" not in content or "Next.js 16.4.5" not in content:
        errors.append(f"DOC-196 runtime policy missing: {path}")
if "直接提交" not in governance or "文档控制" not in governance:
    errors.append("Document control must specify direct main and canonical authority")
# V288: require a durable historical snapshot instead of copying the old log.
if not re.search(r"github\.com/kunlong-luo/merchivra/blob/[0-9a-f]{40}/docs/current-status\.md", runtime):
    errors.append("Current facts must retain an exact historical status snapshot")
if "A3" not in audit_196:
    errors.append("Docs audit must retain historical validation gaps")
def task_fields(content):
    task = re.search(r"^- Task: (V[0-9]+)$", content, re.M)
    state = re.search(r"^- State: ([A-Z_]+)$", content, re.M)
    return (task.group(1) if task else None, state.group(1) if state else None)
task, state = task_fields(active_plan)
if not task or state not in {"READY","IN_PROGRESS","IMPLEMENTED_PENDING_VERIFICATION","FAILED","BLOCKED","VERIFIED"}:
    errors.append("Active task ID or state missing/invalid")
if task_fields(runtime) != (task, state):
    errors.append("Current facts and plan task/state disagree")
if len(runtime.encode("utf-8")) > 24000:
    errors.append("Current facts must be concise; archive exact historical evidence")
for token in ("Basis:", "验收", "下一动作"):
    if token not in runtime or token not in active_plan:
        errors.append(f"Missing handoff field: {token}")
if "realizedNetProfit=null" not in agents or "realizedNetProfit=null" not in engineering:
    errors.append("Canonical Agent and engineering rules must preserve unknown net-profit semantics")

if "realizedNetProfit" not in runtime or "realizedNetProfit" not in active_plan:
    errors.append("Financial unknown / net-profit evidence gate missing")
if "Node 24" not in runtime or "PostgreSQL 18" not in runtime:
    errors.append("V191 pinned runtime baseline missing")
if "Next.js" not in development or "Next.js" not in design or "Next.js" not in deploy:
    errors.append("Active developer, design and deployment docs must reflect Next default")
for file in ("docs/roadmap.md", "docs/roadmap/mvp-freeze-release-v108.md",
             "docs/requirements/implementation-acceptance-rtm-v148.md",
             "docs/governance/docs-audit-v109.md"):
    if "历史归档" not in load(file):
        errors.append(f"{file}: old current status not marked historical")
# GitHub's Dependabot ecosystem key 'npm' covers pnpm; do not ban that name.
for file in ("apps/web/Dockerfile.next",
             ".github/workflows/ci.yml", ".github/workflows/browser-layout-smoke.yml",
             ".github/workflows/github-pages.yml"):
    content = load(file)
    if file.startswith("apps/") and "pnpm install --frozen-lockfile" not in content:
        errors.append(f"{file}: missing reproducible pnpm build")
    if file.startswith(".github/") and "pnpm install --frozen-lockfile" not in content:
        errors.append(f"{file}: missing frozen pnpm install")
    if re.search(r"\bnpm\s+(?:ci|install|run|test)\b", content):
        errors.append(f"{file}: legacy npm CLI in active CI/Docker")

# V231: explicitly track the retained source/ops/docs boundaries. No
# generic deletion by timestamp or test status; historical evidence stays.
root_readme = load("README.md")
zh_readme = load("README.zh-CN.md")
for directory in ("apps/server", "apps/web", ".github/workflows", "deploy",
                  "ops", "scripts", "docs", "docs/roadmap", "docs/governance",
                  "docs/finance", "docs/testing", "docs/architecture",
                  "docs/purchasing"):
    if not (ROOT / directory).is_dir():
        errors.append(f"Required runtime or evidence directory missing: {directory}")
for directory in ("apps/", "deploy/", "ops/", "scripts/", ".github/",
                  "docs/"):
    if directory not in root_readme or directory not in zh_readme:
        errors.append(f"Root README directory map missing: {directory}")
for current in ("docs/current-status.md", "docs/roadmap/current-plan.md",
                "docs/governance/project-scorecard-v231-2026-11-24.md",
                "docs/finance/v230-external-source-proof-dossier-and-owner-workflow.md"):
    if current not in root_readme and current not in zh_readme:
        errors.append(f"Root READMEs omit current reference: {current}")

# Directory governance: keep a single retired-path mapping and one link scan.
RETIRED_DOC_DIRS = {
    "docs/ops": "docs/operations/operations.md",
    "docs/ux": "docs/design/navigation-and-page-states.md",
    "docs/diagrams": "docs/architecture/diagram-index.md",
    "docs/processes": "docs/product/core-flows.md",
    "docs/templates": "docs/architecture/adrs/template.md",
}
for old, new in RETIRED_DOC_DIRS.items():
    if (ROOT / old).exists():
        errors.append(f"Retired documentation directory reintroduced: {old}")
    if not (ROOT / new).is_file():
        errors.append(f"Canonical documentation missing: {new}")
for relative in [*ROOT.glob("*.md"), *(ROOT / "docs").rglob("*.md")]:
    for found in INLINE.finditer(relative.read_text(encoding="utf-8")):
        href = found.group(1).strip().split(' "', 1)[0].strip().strip("<>")
        parts = urlsplit(href)
        if parts.scheme or parts.netloc or not parts.path:
            continue
        resolved = (relative.parent / unquote(parts.path)).resolve()
        for old in RETIRED_DOC_DIRS:
            if resolved.is_relative_to(ROOT / old):
                errors.append(f"{relative.relative_to(ROOT)}: stale retired path: {href}")

# V240: merged file aliases must not be reintroduced or linked locally.
RETIRED_DOC_FILES = {
    "docs/finance/v181-settlement-identity-fingerprint-v1.md": "docs/finance/v181-immutable-settlement-line-identity.md",
    "docs/testing/test-plan.md": "docs/testing/ci-policy.md",
    "docs/architecture/mature-tool-first-api-client-2026-11-01.md": "docs/architecture/dependency-adoption.md",
}
for old, new in RETIRED_DOC_FILES.items():
    if (ROOT / old).exists():
        errors.append(f"Retired duplicate document reintroduced: {old}")
    if not (ROOT / new).is_file():
        errors.append(f"Canonical merged document missing: {new}")
for relative in [*ROOT.glob("*.md"), *(ROOT / "docs").rglob("*.md")]:
    for found in INLINE.finditer(relative.read_text(encoding="utf-8")):
        href = found.group(1).strip().split(' "', 1)[0].strip().strip("<>")
        parts = urlsplit(href)
        if not parts.scheme and not parts.netloc and parts.path:
            resolved = (relative.parent / unquote(parts.path)).resolve()
            if any(resolved == ROOT / old for old in RETIRED_DOC_FILES):
                errors.append(f"{relative.relative_to(ROOT)}: stale merged document: {href}")

# All actual first-level docs domains must be named in the directory tables.
for folder in sorted((ROOT / "docs").iterdir()):
    if not folder.is_dir() or folder.name.startswith("."):
        continue
    for name, content in (("README.md", root_readme),
                          ("README.zh-CN.md", zh_readme), ("docs/README.md", hub)):
        rows = "\n".join(line for line in content.splitlines() if line.startswith("|"))
        if f"`{folder.name}/`" not in rows and f"`docs/{folder.name}/`" not in rows:
            errors.append(f"{name}: missing documentation directory role: {folder.name}/")

if errors:
    for error in errors:
        print("FAIL DOCS:", error)
    raise SystemExit(f"{len(errors)} authoritative documentation error(s)")
print(f"PASS DOC-196 documentation: {len(ACTIVE_FILES)} entry docs; "
      f"{tested} relative local links; current runtime/release boundaries")
