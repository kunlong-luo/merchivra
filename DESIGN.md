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
