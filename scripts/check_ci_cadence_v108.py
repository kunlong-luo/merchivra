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
assert ci.count("if: inputs.full_suite == true") >= 3  # Next is built on every main push; no duplicate full-only build
assert "Gradle Wrapper tests (fast on every main push)" in ci
ci_header = ci.split("jobs:", 1)[0]
assert "push:" in ci_header and "branches: [main]" in ci_header
assert "pull_request:" not in ci_header
assert "github.head_ref" not in ci
assert "visual-refresh-browser:" not in ci
assert "if: inputs.full_suite == true" in ci
assert "Next 16 App Router TS gate" in ci
assert "Build primary Next.js 16" in ci
assert "Build explicit Vite rollback" in ci
assert "run: pnpm run build:vite" in ci
assert "run: pnpm run build:vite" in pages
assert "run: pnpm run build:vite" in browser
assert "pnpm run api:check" in ci and "pnpm run test" in ci
assert "compose-deployment-config:" in ci
assert "pg18-migrations:" in ci
# Keep the long PG18 suite out of YAML run scalars; prevent accidental omission.
assert "run: bash scripts/run_pg18_full_ci.sh" in ci
pg18 = load("scripts/run_pg18_full_ci.sh")
assert "${{" not in pg18, "GitHub expressions must be resolved in workflow env, not Bash"
assert "PG18_SERVICE_CONTAINER_ID: ${{ job.services.postgres.id }}" in ci
assert 'docker exec -i "$PG18_SERVICE_CONTAINER_ID"' in pg18
for required in (
    "MERCHIVRA_V203_DISPOSABLE_ONLY=true python3 scripts/smoke_sale_lot_assignment_v203.py",
    "MERCHIVRA_V248_DISPOSABLE_ONLY=true python3 scripts/smoke_sale_lot_range_race_v248.py",
    "MERCHIVRA_V236_DISPOSABLE_ONLY=true MERCHIVRA_V250_DISPOSABLE_ONLY=true",
    "ReceiptCostCompositionPg18V250Test.actualPg18ReceiptCostsAndSaleRangesConserveAmounts",
    "python3 scripts/check_reports_service_junit.py",
    "MERCHIVRA_SPAPI_OAUTH_ENABLED",
    "ci_disposable_database_password",
):
    assert required in pg18, required
assert "ReceiptCostCompositionPg18V250Test.actualPg18ReceiptCostsAndSaleRangesConserveAmounts" not in ci

for name, contents in (("browser", browser), ("compose", chain)):
    header = contents.split("jobs:", 1)[0]
    assert "workflow_dispatch:" in header, name
    assert "workflow_call:" in header, name
    assert "pull_request:" not in header, name
    if name == "compose":
        assert "push:" not in header, name
    else:
        # V282/V283: browser contract changes warrant an independent run.
        # Exact allowlist; no generic frontend/source or unfiltered main push.
        trigger = header.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        assert trigger.strip() == """workflow_dispatch:
  workflow_call:
  push:
    branches: [main]
    paths:
      - .github/workflows/browser-layout-smoke.yml
      - scripts/smoke_browser_layout.mjs
      - scripts/chrome_devtools_port.mjs
      - scripts/chrome_devtools_port.test.mjs""", "unexpected browser trigger cadence"

assert "pull_request:" not in pages
assert "if: github.event_name" not in pages
assert "workflow_dispatch:" in milestone
assert "workflow_call:" not in milestone.split("jobs:", 1)[0]
assert "uses: ./.github/workflows/ci.yml" in milestone
assert "uses: ./.github/workflows/browser-layout-smoke.yml" in milestone
assert "uses: ./.github/workflows/mock-full-chain.yml" in milestone
assert "full_suite: true" in milestone
assert 'test "$GITHUB_REF_NAME" = "main"' in milestone
assert "真实" in policy and "PG18" in policy

print("PASS V283: direct-main quick CI; full PG18/Compose on milestone; Chromium on milestone or exact test-contract changes")
