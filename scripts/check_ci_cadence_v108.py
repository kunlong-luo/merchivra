#!/usr/bin/env python3
"""V108: keep heavy smoke gates explicit; no production credentials are used.

This is a stdlib-only static regression guard. GitHub Actions itself validates
workflow YAML; the script protects our deliberately lean trigger cadence.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


ci = load(".github/workflows/ci.yml")
browser = load(".github/workflows/browser-layout-smoke.yml")
chain = load(".github/workflows/mock-full-chain.yml")
pages = load(".github/workflows/github-pages.yml")
milestone = load(".github/workflows/milestone-validation.yml")
policy = load("docs/roadmap/mvp-freeze-release-v108.md")

assert "workflow_call:" in ci and "workflow_dispatch:" in ci
assert "full_suite:" in ci
assert "if: inputs.full_suite == true" in ci
assert ci.count("if: inputs.full_suite == true") >= 4
assert "Gradle Wrapper tests (fast on every PR and main push)" in ci
assert "Next 16 App Router TS gate" in ci
assert "npm run api:check" in ci and "npm run test" in ci
assert "compose-deployment-config:" in ci
assert "pg18-migrations:" in ci

for name, contents in (("browser", browser), ("compose", chain)):
    header = contents.split("jobs:", 1)[0]
    assert "workflow_dispatch:" in header, name
    assert "workflow_call:" in header, name
    assert "pull_request:" not in header, name
    assert "push:" not in header, name

assert "pull_request:" not in pages.split("jobs:", 1)[0]
assert "workflow_dispatch:" in milestone
assert "workflow_call:" not in milestone.split("jobs:", 1)[0]
assert "uses: ./.github/workflows/ci.yml" in milestone
assert "uses: ./.github/workflows/browser-layout-smoke.yml" in milestone
assert "uses: ./.github/workflows/mock-full-chain.yml" in milestone
assert "full_suite: true" in milestone
assert "refs/heads/main" not in milestone or "main" in milestone
assert "Only main" not in policy  # scope has explicit rules, not live secrets
assert "真实" in policy and "PG18" in policy

print("PASS V108: quick PR/main checks; full PG18/Chromium/Compose on milestone")
