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
| 4.5e Resolve chip, TableConflictBanner, Back rung 1, held-commit gate banner | DONE in PR-C (Robolectric), PENDING-OWNER on device | `TableInlineConflictTest` covers it headlessly. Verify on Pixel_Fold/TalkBack: banner focus order, the Resolve chip reachable from the strip, Back dismissing only the banner. |
| 4.5d hints H2/H3 | DEFERRED to PR-C | Need the sheet range mode (5.4a). |
| TalkBack / Switch Access entry profile | PENDING-OWNER | A semantic click now enters `Touch` (strip visible) because commonMain has no accessibility-service signal; `accessibilityServiceActive` is still never passed. Verify on a device with TalkBack that the strip is reachable, and decide whether a platform signal (Android `AccessibilityManager`) is worth wiring. |
| Mouse vs touch drag on a hybrid device | PENDING-OWNER | Mouse drag selects a range, touch drag never does; a stylus or touchscreen laptop that reports `Touch` for a mouse-like pointer is UNVERIFIED. |

## PR-C additions (all PENDING, nothing verified on device)

| Task | Status | What is needed |
|---|---|---|
| 5.1a Dialog window keys and Back | PENDING-OWNER | On device and desktop: Ctrl+Z/Y reach the sheet, Esc is not handled twice (key handler plus `onDismissRequest`), hardware Back dismisses the banner first, the soft keyboard and IME insets behave inside the Dialog at phone width. |
| 5.1b `onStop` / configuration change | PENDING-OWNER | `isChangingConfiguration` defaults to false in commonMain (no platform signal wired): rotate and fold on Pixel_Fold and confirm a draft is neither lost nor double-committed. |
| 5.1b commit failure on a real DB error | PENDING-OWNER | Only tested with a `Left`-returning wrapper over in-memory repositories; Retry writes the persisted content first (three undo steps), see repair-log. |
| 5.1d recovered-draft notice outside the sheet | PENDING-OWNER | Shown in a non-focusable `Popup` at the window bottom when no sheet is open; placement over the keyboard, TalkBack announcement and the Apply path (reopens the sheet, then applies) are unverified. |
| 5.2 / 5.3 pinned editor above the IME (0.4a default) | PENDING-OWNER | The edit bar is the one live field on every width (the cell mirrors the draft as text) because the IME covers 40-50% of a phone. `imePadding` on the grid inside a `Dialog` window, bring-the-active-cell-into-view above the IME, IME action `Next` with Enter semantics and the landscape fullscreen IME are all UNVERIFIED on Pixel_Fold; the go/no-go rule of Task 0.4a still applies. |
| 5.2 recycling on device | PENDING-OWNER | Draft text and selection survive a recycle (tested under Robolectric); IME composition, focus and the soft keyboard across a recycle are device-only (Task 8.2a). Process death: the draft commits on `onStop`; nothing else is restored. |
| 5.3 Wasm Tab trap and edit-mode key tweaks | PENDING-OWNER | Depends on 0.3a/b. Hardware keys, dead keys and CJK composition (`composing` passes every key to the IME) are verified only at the pure decision function. |
| 5.8a hover popup for error detail | PENDING-OWNER | A non-focusable `Popup` shows the reason while a pointer hovers an error cell; it is not dismissible with Esc (the popup never takes focus). Esc dismisses the edit bar's message line, which is the keyboard and touch path. Desktop and web hover behaviour is UNVERIFIED. |
| 5.9a "Saving..." on the closing dialog | DEFERRED | The session dismisses the dialog the moment `close()` runs, so "Saving..." while `flush()` completes is not shown (the in-sheet 300 ms indicator is). |
| 5.4a range handles and haptic tick | PENDING-OWNER | Touch long-press, 48dp handle hits, handle drag near edges, `HapticFeedbackType.LongPress` on range entry: Robolectric only. Hinge avoidance not built (no common API). |
| 5.4b clipboard on device and Wasm | PENDING-OWNER | System clipboard round trip with Sheets/Excel, and the DOM paste event: `LocalPasteEventSource` is defined, null until worker 4 / spike 0.3a provides it; until then Ctrl+V uses the port and the "Paste here" panel. UNVERIFIED. |
| 5.5 to 5.7 menus and resize | PENDING-OWNER | Bottom sheet below 600dp vs popup, right-click and Shift+F10 on real hardware, resize drag on a touch device; auto-fit is a character-count estimate (96 to 240dp), not measured text. |
| 4.5d H2/H3 hints | DONE headless | Input kind (touch vs key) is inferred from the first pointer or key event; verify on device. |
