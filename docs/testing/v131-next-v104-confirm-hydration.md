# V131 — A3 Next Chromium V104 Base UI cancel-dialog hydration race

Date: 2026-11-09. Root run: [V130 exact main A3 #42632198199](https://github.com/kunlong-luo/merchivra/actions/runs/42632198199), commit `64b2e616c47d16e326d2310447cdff522ecce8ab`.

## Observed failure (do not classify as a pass)

- The Vite Chromium synthetic browser step **passed**, and Next production bundle/typecheck also **passed**.
- At the Next-only V104 purchase order confirmation flow, the script found `PO-V104-CONFIRM-SYNTHETIC` in the server-rendered table and then did one immediate `.click()` on its `取消` button. The `role="alertdialog"` popup failed to appear within the existing 90 × 140 ms wait: `Timed out: V104 Base UI alert dialog visible` at `smoke_browser_layout.mjs:1058`.
- This is consistent with a client hydration race between finding an SSR row and its React event handler becoming active. It is not evidence that the user can bypass approval; the run **failed correctly** because the popup was absent. Do not weaken/skip it or increase generic timeout without isolating the interaction.

## Narrow correction

- In this **synthetic browser-only, Next-only** V104 check, retry the **non-writing** cancel-dialog open action while waiting for the real Base UI `role="alertdialog"` title to exist. If hydration has not attached the click handler, another click can open it after hydration. If the dialog is genuinely broken, the original bounded wait still fails.
- Keep every prior business assertion: `v104CancelWrites===0` when opening, focus captured in popup, cancel/dismiss returns to purchasing with **zero** POSTs, reopen and explicit approval triggers **exactly one** local synthetic cancel write and the actual CANCELLED status. Existing V106 finance and all downstream browser checks remain mandatory.
- On timeout, print only synthetic route, visible PO row labels and dialog text to help diagnose the failure without leaking session cookies or production data.
- No production UI, Spring handler, inventory, finance, OAuth, or database behavior changes. Do not disable the test or substitute a mocked success state.

## Gate

Only ordinary PR fast CI runs automatically during development; on merge the milestone workflow triggers one **full exact-main-SHA A3** across Chromium/PG18/Next/Compose. V129's prior full green run validates only V129's original SHA, and V130's failed browser A3 must not be marked passed after a small fix without an entirely new green run.

Real HTTPS/secret provisioning/role/session tests on a target environment, production backup/PITR and actual rollback, and local approved Amazon Seller/Ads OAuth remain OPEN.
