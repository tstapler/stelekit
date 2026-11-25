# Owner queue (PR-A)

Items an agent could not complete headlessly. Each needs the owner (Tyler) or a device/browser.

| Task | Status | What is needed |
|---|---|---|
| 0.7a baseline | BLOCKED-OWNER | The table-count command in requirements.md (Baseline) needs the owner's graph path; no Logseq graph exists on this machine (`~/Documents/notes` is absent). Also the stopwatch protocol and the five persona/platform questions. Baseline stays UNMEASURED. |
| 0.3a / 0.3b Wasm spike | BLOCKED-OWNER, not attempted | Needs owner-machine Chrome paste (Ctrl+V and context-menu) and a dead-key or IME sequence on a wasm build. No PR-A task depends on it; it gates 7.1b and the Wasm part of 5.3a (PR-C). |
| 0.4a Android spike (Pixel_Fold) | NOT ATTEMPTED, PENDING-OWNER | Emulator pass on a shared machine already under heavy Gradle load; no PR-A task depends on it. Gates 4.2a and 5.2b (PR-B/PR-C). Go/no-go rule in plan.md Task 0.4a applies; the gated inline behaviour is conservative and UNVERIFIED. |
| 0.1b web baseline | NOT ATTEMPTED | Needs the wasm dev server and a browser screenshot at 390 px and desktop width. Phone baseline PNGs (Task 0.1a) exist. |
| 8.4b Logseq untouched fixtures | BLOCKED-OWNER | Open `implementation/logseq-compat/*.md` in Logseq and fill the results table in `logseq-compat/README.md`. |

## PR-B additions (all PENDING, nothing verified on device)

| Task | Status | What is needed |
|---|---|---|
| 8.2b early device smoke, inline | PENDING-OWNER | Pixel_Fold: tap/long-press/link timing, IME inset docking of the strip, landscape IME fullscreen (Compose exposes no no-extract/no-fullscreen flags), TalkBack order across the strip popup, `onStop` commit, `ImeAction.Next` reaching the Enter action. Gates 4.4a/5.1a go/no-go per Task 0.4a. |
| 0.3a/b Wasm IME/clipboard | PENDING-OWNER | `ComposeTableClipboard` paste on Wasm is UNVERIFIED; strip entry profile from DOM pointer type is INFERRED. |
| 4.5e Resolve chip, TableConflictBanner, Back rung 1, held-commit gate banner | DEFERRED to PR-C | Depend on 5.1b/5.1d. PR-B keeps the draft and holds commits; Resolve slot is inert. |
| 4.5d hints H2/H3 | DEFERRED to PR-C | Need the sheet range mode (5.4a). |
| TalkBack / Switch Access entry profile | PENDING-OWNER | A semantic click now enters `Touch` (strip visible) because commonMain has no accessibility-service signal; `accessibilityServiceActive` is still never passed. Verify on a device with TalkBack that the strip is reachable, and decide whether a platform signal (Android `AccessibilityManager`) is worth wiring. |
| Mouse vs touch drag on a hybrid device | PENDING-OWNER | Mouse drag selects a range, touch drag never does; a stylus or touchscreen laptop that reports `Touch` for a mouse-like pointer is UNVERIFIED. |
