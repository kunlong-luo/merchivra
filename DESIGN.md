# Merchivra · UI Design System

Status: 2026-10-31 manual implementation of public design-review methods (Impeccable-inspired). **Impeccable is not installed or executed.** Reference: https://impeccable.style/docs/audit/ and https://impeccable.style/docs/polish/.

## Product and users

An open-source, self-hosted Amazon Seller ERP for operations staff. Prioritize operator comprehension, predictable decisions and traceability over decorative visuals. The current product has an authenticated tenant-scoped **Core** workbench plus separate fictional **Demo/Mock** pages. Never imply that mocked metrics are real, that connected API means connected Amazon, or that an unavailable finance metric equals zero.

## Visual foundation

- Existing brand: sober midnight/navy sidebar, cool-blue interactive accent, clear white data surfaces, pale gray canvas. Avoid gradients on every card, glassmorphism, giant empty hero banners, stacked nested cards and ornamental status chips.
- Type: native UI Chinese + Latin stack. Page title 26–35px; section heading 16px; body 13–14px; tables 12px. Numeric financial/quantity cells use tabular numerals; status uses text **plus** color.
- Spacing: 4/8/12/16/24/32px rhythm, 8–12px corner radius, very subtle shadow, borders separating tables. Apply `--ui-*` tokens in `apps/web/src/style.css` when touching existing declarations.
- Semantics: distinguish `0`, unknown, missing, pending authorization, stale and Mock. Never convert missing values to zero. Keep currency per Marketplace.
- Color: interactive blue is not used to suggest verified Seller connection. The green connectivity dot in the top bar only indicates **API health**, not Amazon.
- Density: ERP data tables must remain scannable; avoid inflated paddings, oversized feature cards, and hardcoded row heights. Numeric columns line up visually.

## Interaction and state rules

- Keyboard: all interactive controls need visible `:focus-visible`, explicit accessible labels, and a skip-to-content link for authenticated pages. Do not hide focus outlines.
- Empty/loading/error states are distinct and say what actually happened. Error feedback uses `role=alert`; operations that succeed use `role=status` without printing secrets.
- Buttons: primary action only for the current task; destructive operations require clear outcomes; disabled means genuinely unavailable. Touch controls target 40–44px where possible; avoid horizontal page scrolling, allowing **table-local** horizontal scroll if needed.
- Respect `prefers-reduced-motion`; transitions are enhancements, not required to interpret system state.
- Never blur the trust boundary of the Core workbench (session-verified tenant resources) versus Mock fictional datasets. Sidebar environment copy must follow active view.
- Make navigation/task hierarchy explicit: selected view, selected Marketplace and currently selected Core data subtype. Label each filter in text; do not rely on an icon.
- Preserve existing Vue 3 + TypeScript + Vite architecture, real auth/session, CSRF, API contracts, field semantics and test behaviors.

## Reviewed 2026-10-31

Code-only design review, **not** a browser screenshot inspection or automated Impeccable detector run.

| Finding | Severity | Change |
|---|---|---|
| Sidebar always says local Demo even when viewing session-scoped Core | High | Copy now tracks active view; API health explicitly labeled |
| No keyboard skip-to-main action | Medium | Added skip link and focusable main target |
| Low-contrast secondary text, table headers and status pills | Medium | Raised contrast and standardized information hierarchy |
| Mobile touch areas and narrow-page header could be clearer | Medium | 40–44px controls, adaptive topbar and wrapped heading actions |
| No consistent keyboard focus / reduced-motion handling | Medium | Unified focus indicator and reduced-motion CSS |
| No common visual design guidelines for agents | Medium | This document plus AGENTS.md guardrails |

## Acceptance checks

Run `npm ci`, `npm run build` and the existing Vitest/typecheck workflow. At M3/M4, manually check at 360/390/768/1280px, keyboard traversal, screen-reader descriptions, zoom to 200%, dark-mode expectations and browser screenshots; **these manual checks have not been completed in this pass**. Do not claim real Lighthouse or WCAG AA verification based solely on code changes.

## Working without an Impeccable plugin

Read the publicly available `critique`, `audit`, `typeset` and `polish` documentation for techniques, then inspect existing components and commit small changes through normal repo tools. Do **not** install npm packages, import Impeccable hooks or ship Impeccable binaries/assets unless the maintainer explicitly opts in. Changes must remain reviewable in a normal Git diff.


## Iteration 2 · Core workbench (2026-10-31)

Second manual code audit using the public Impeccable audit/polish criteria (no Impeccable runtime or detector run):

- P1 · Core Orders had seven table headers but only six body cells: the **Source** cell was missing and timestamps appeared under the wrong header. Fixed the column alignment and explicit MOCK/UNVERIFIED provenance.
- P1 · "No connected Marketplace" wrongly described an initial **unconfigured** tenant. The onboarding state now asks for local shop and Marketplace configuration, while explicitly noting that this is not Amazon authorization.
- P2 · Local shop form and Mock replay overshadowed the read-only data task. Data is now first when a site exists, with setup/replay in native keyboard-accessible `details`. Initial tenant onboarding continues to show the setup form directly.
- P2 · Introduced on-page-only search for loaded Listing / Order / FBA rows with visible `filtered / loaded` counts. Filtering is client-side and **does not claim to search beyond the first 50 server rows**. Query resets on Marketplace or category change.
- P2 · Loading, empty, no-match, error/retry feedback now distinguishes upstream availability from missing records. Unknown FBA quantity remains unknown; statuses retain unrecognized upstream codes rather than inventing meanings.
- P2 · Added table-local keyboard scroll, text status labels, FBA mock-page progress, semantic column headers, and a proper setup audit loading state. Tests cover pure filtering and provenance/time formatting.

Acceptance evidence must include the actual GitHub Actions Vue typecheck/Vitest/Vite result. Manual responsive/browser screenshots, keyboard traversal, real ARIA screen-reader and WCAG evaluation are still pending.


## Iteration 3 · Responsive CSS and Chromium smoke (2026-10-31)

- Core, Seller Setup, Sandbox and Demo table containers are keyboard-focusable
  and explicitly labelled. All column header cells have `scope="col"`.
  Native table semantics are retained; Core category selectors remain buttons
  with `aria-pressed` instead of incorrectly claiming WAI tab keyboard behavior.
- At 360/390px, the Core table uses local horizontal scrolling; the full
  page should not overflow. Controls remain readable and touchable.
- New opt-in [TEST-005](docs/testing/browser-ui.md) checks real Chrome-rendered
  login/Core layouts at 360, 390, 768 and 1280 CSS pixels, table column counts,
  source labels and UNKNOWN vs zero. It captures screenshots for manual review.
  The test uses only localhost fixtures and Chrome installed on the runner;
  no Impeccable/Playwright install or live Amazon/network Seller data.
- Treat screenshot capture and CDP assertions as a **limited UI smoke**, not
  manual aesthetic approval, 200% zoom, WCAG, assistive-technology or
  cross-browser acceptance. Only claim passing after a completed workflow run.


## Verified acceptance · Responsive Chromium smoke, 2026-10-31

The [TEST-005 browser smoke](docs/testing/browser-ui.md) successfully ran
against the Vue build under Chrome 156 on GitHub Actions
([Chrome run](https://github.com/kunlong-luo/merchivra/actions/runs/41150873626) /
[fast CI](https://github.com/kunlong-luo/merchivra/actions/runs/41150873629)).
Generated 360/390/768/1280 synthetic UI captures were saved as workflow
artifacts. The 360px Listing, Orders and FBA table-region screenshots and
1280px Listing view have been manually inspected for visible Chinese copy and
layout/scroll boundaries. Noto CJK fonts exist **in the test runner only**.

This is a small, actual browser-based acceptance step, not completion of a
multi-browser matrix, screen-reader assessment, focus order review or WCAG AA.
