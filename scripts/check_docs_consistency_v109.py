#!/usr/bin/env python3
"""V109: stdlib-only guard for authoritative documentation entry points.

This does not claim to audit every historical Vxx document, every on-page
fragment or any remote URL. It checks navigable local links and prevents
reintroduction of known, dangerous current-state contradictions. Safe for
quick PR CI: read-only, no credentials, network or production database.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ACTIVE_FILES = (
    "README.md",
    "README.zh-CN.md",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "DESIGN.md",
    "docs/README.md",
    "docs/development.md",
    "docs/architecture/overview.md",
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

if errors:
    for error in errors:
        print("FAIL DOCS:", error)
    raise SystemExit(f"{len(errors)} authoritative documentation error(s)")
print(f"PASS V109 documentation: {len(ACTIVE_FILES)} entry docs; "
      f"{tested} relative local links; current runtime/release boundaries")
