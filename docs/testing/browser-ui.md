
# TEST-005 · Optional responsive Chromium UI smoke (synthetic data)

Status: lightweight browser-based **layout smoke**, **not** production end-to-end
testing, browser accessibility certification, or a real Amazon integration.

## What runs

The standalone GitHub Actions workflow
[Optional Chromium responsive UI smoke](../../.github/workflows/browser-layout-smoke.yml)
uses Node 24, runner-provided headless Google Chrome, and the built-in WebSocket
client to drive Chrome's DevTools Protocol. It does **not** install Impeccable,
Playwright, Selenium, a browser npm dependency, or a dedicated server library.

The script [`scripts/smoke_browser_layout.mjs`](../../scripts/smoke_browser_layout.mjs)
builds its own localhost-only HTTP stub and serves the compiled Vue frontend.
It returns explicit synthetic Session tenant, marketplace, Listing, Orders and
FBA responses. No actual login, SP-API, Seller OAuth, real secrets, external
browser target or production data is involved.

When passing, it checks:

- Login form and authenticated Core layout at 360, 390, 768 and 1280 CSS px;
  no page-level horizontal overflow under those viewport emulations.
- A real rendered Core table can receive keyboard focus; its horizontal
  overflow is contained locally on narrow screens. Login provides a skip link.
- Switch to Orders and FBA tables at 360px, asserting matching header/data
  column counts and the explicit **MOCK provenance** for Orders.
- Inventory uses distinct "未知" for absent quantity and `0` for observed zero.
- Full-page-viewport screenshots are generated for visual inspection and
  uploaded as a short-lived GitHub Actions artifact. **Passing automated
  checks does not mean somebody visually reviewed the screenshots.**

Run manually from the workflow page, or trigger when the workflow definition
changes. The normal Java/Vue CI remains quick and **does not** run browser
smoke on every push. With a locally installed Chrome and Node 24:

```bash
cd apps/web && npm ci && npm run build && cd ../..
CHROME_BIN=google-chrome node scripts/smoke_browser_layout.mjs apps/web/dist browser-smoke-artifacts
```

## Limits and follow-up

- Browser checks use deterministic mocked HTTP JSON; they cannot validate
  PostgreSQL, session cookies, CSRF, the real API response times or real seller
  authorization. The source flags remain `MOCK`/`UNVERIFIED`.
- Actual color-contrast measurements, screen-reader announcements, keyboard
  traversal across the complete navigation, 200% zoom, different engines,
  Android/iOS browsers, WCAG AA claims and manual screenshot signoff are still
  open tasks. This script only verifies explicitly coded layout and semantics.
- Do not add this to the default CI matrix until M3/M4 needs justify it.
- If Chrome or DevTools is unavailable in the runner, mark the workflow failed;
  never replace real browser validation with a claimed successful static check.


## Confirmed run · 2026-10-31

- [Completed successful Chrome 156 / Node 24 layout run](https://github.com/kunlong-luo/merchivra/actions/runs/41150873626), commit [fdff8d62](https://github.com/kunlong-luo/merchivra/commit/fdff8d62b4b6190882f3868413c8530cf341e804).
  GitHub Actions produced 11 PNG screenshots (artifact:
  [merchivra-synthetic-browser-screenshots](https://github.com/kunlong-luo/merchivra/actions/runs/41150873626#artifacts)).
  CJK Noto fonts were installed **only in the disposable CI runner** so that Chinese strings render as Chinese characters rather than missing-glyph squares. Nothing was added to the frontend lockfile.
- [Completed successful Java 25 Maven / Vue TypeScript-Vitest-Vite CI](https://github.com/kunlong-luo/merchivra/actions/runs/41150873629), same commit.
- Manual visual inspection of these CI screenshots confirmed readable Chinese at
  360px and 1280px, plus visible **table-local** overflow at 360px
  for Listing, Orders and FBA. The FBA screen shows `未知` separately from
  the observed reserved value `0`. The fixed Orders source is also asserted
  by the browser runner against synthetic `MOCK` rows, although hidden columns
  are reached via horizontal scrolling rather than in one screenshot.
- This constitutes **limited Chromium screenshot/layout review**; real
  keyboard traversal, screen reader, WCAG contrast, 200% zoom, Safari/Firefox,
  responsive performance and live backend Amazon checks still require
  separate acceptance and are not claimed completed.
