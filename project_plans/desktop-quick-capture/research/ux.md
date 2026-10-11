# UX Research: Desktop Quick Capture

## Android ground truth (source of parity)

`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/NoGraphPlaceholder.kt`):

- Translucent scrim (`Color.Black.copy(alpha = 0.4f)`) + bottom sheet, launched as a
  transparent `ComponentActivity` over whatever app the user shared from.
- Auto-focuses the text field on appear (`FocusRequester` in `LaunchedEffect(Unit)`).
- Three exits, all converging on the same save-or-discard logic:
  - **Back gesture** (`BackHandler`, enabled only when text is non-blank and not already
    saving) → auto-saves.
  - **Tap the scrim** → save if non-blank, dismiss if blank (`if (captureText.isBlank())
    onDismiss() else viewModel.save()`).
  - **Explicit Save/Dismiss buttons** in the sheet.
- On successful save: `finish()` — the activity closes and Android returns focus to
  whatever app was in the foreground (the share source), with no persistent
  confirmation UI beyond the sheet closing.
- On save error: inline `Snackbar` inside the still-open sheet — user stays in place
  and can retry.
- No-graph state: instead of the capture sheet, renders `NoGraphPlaceholderContent()`
  (title + body + a single "Open SteleKit" button that launches the main app). No
  silent failure, no capture UI shown when there's nowhere to write to.
- Also reachable from a Quick Settings Tile and home-screen widget, not just the share
  sheet — i.e., Android already treats "capture" as a multi-entry-point action, not
  exclusively a share-intent handler.

This is the behavioral contract the desktop surfaces should match: instant focus,
save-or-discard-on-any-dismissal (never silently drop non-blank text), inline error
recovery, and a distinct empty-graph state that still explains what to do next rather
than failing invisibly.

## 1. Comparable desktop products

- **macOS Quick Note (Sonoma+, actually shipped since Monterey)**: global hotkey
  (`Fn`/`Globe`+`Q`, user-remappable in System Settings → Keyboard → Keyboard
  Shortcuts) or a screen-corner hot-corner gesture opens a small floating note window
  instantly, from any app, without switching windows. This is a **first-party OS
  capture surface that competes directly with a custom global-hotkey popup** — see
  open question below on integration vs. building our own.
  [Macworld: Quick Note hot corners](https://www.macworld.com/article/351264/macos-monterey-quick-note-hot-corners-how-to.html),
  [OSXDaily: Quick Note shortcut](https://osxdaily.com/2022/03/22/make-a-quick-note-on-mac-by-keyboard-shortcut/)
- **Things 3 Quick Entry**: global hotkey summons a small always-on-top capture panel
  from anywhere on macOS; supports pre-filled parameters (title, tags, dates) when
  invoked with autofill via URL scheme/Shortcuts, otherwise a blank quick-entry form.
  [MacStories: Things 3.17](https://www.macstories.net/reviews/things-3-17-overhauls-the-apps-shortcuts-actions/)
- **Todoist Global Quick Add**: desktop-app-only global hotkey; explicitly pitched as
  "jot down tasks without breaking stride ... even if you're in a different app."
  Desktop-app-only is a relevant constraint — it requires the app process to already
  be running, same cold-start problem SteleKit faces.
  [Todoist: Quick Add](https://www.todoist.com/help/articles/use-task-quick-add-in-todoist-va4Lhpzz)
- **Raycast / Alfred (Spotlight-style launchers)**: keyboard-first popup pattern —
  global hotkey → floating always-on-top window → type → `Enter` to act → `Esc` to
  close and return focus. Raycast's own positioning is explicitly "keyboard-first
  ... navigate the entire interface ... without touching your mouse," and uses `Esc`
  as the universal close/back action. This is the closest UI-pattern analog for a
  Linux/Windows global-hotkey capture popup in the requirements doc.
  [Raycast vs Alfred](https://www.raycast.com/raycast-vs-alfred),
  [Raycast Keyboard Shortcuts manual](https://manual.raycast.com/keyboard-shortcuts)
- **Obsidian**: no native global-hotkey capture window; the community achieves it only
  via third-party plugins (QuickAdd) combined with OS-level automation (AutoHotKey on
  Windows, Shortcuts.app on macOS) to launch/focus Obsidian and open a capture modal.
  Notable as a **negative data point**: Obsidian users had to build this themselves,
  suggesting real demand but also that it's non-trivial cross-platform, which is
  consistent with the requirements doc treating IPC/cold-start as the hard problem.
  [Obsidian Forum: AutoHotKey QuickAdd capture](https://forum.obsidian.md/t/global-hotkey-to-launch-obsidian-and-open-quickadd-capture-window-using-autohotkey-script/22626)

**Integration-point question for macOS specifically:** given Quick Note is a system
capture surface already muscle-memoried by macOS users (`Fn`+`Q`), the requirements
doc's "Share Extension / Services-menu entry" framing may be solving a narrower and
more valuable problem (selected text → SteleKit specifically) than trying to compete
with or replace Quick Note. Recommend scoping macOS v1 to the Share
Extension/Services-menu path exactly as written, and treating "add a global-hotkey
popup on macOS too" as explicitly out of scope until there's evidence Quick Note
doesn't satisfy the need — building a second, redundant system-wide capture gesture
on macOS is low JTBD value for meaningful implementation cost.

## 2. User mental models by trigger type

| Trigger | Expected latency | Expected feedback | Expected focus-return |
|---|---|---|---|
| Global hotkey (in-process popup) | Instant (<100ms), matches Spotlight/Raycast/Quick Note muscle memory | Visible floating window appears; no separate toast needed because the window *is* the confirmation surface | Snaps back to whatever had focus before the hotkey — Raycast/Quick Note both restore the prior window on `Esc`/close, this is the load-bearing expectation from the launcher-pattern precedent |
| Context-menu item ("Send to SteleKit" / Nautilus action / Windows registry handler) | User already expects an OS-mediated delay (menu closes, action runs) — a *few hundred ms* to a couple seconds is tolerable if there's a spinner/toast, because the file manager itself is the "current task," not a document the user is mid-thought in | A toast/notification is the right pattern here, not a visible window — the user's mental model is "fire and forget," analogous to "Send to compressed folder," not "open an editor" | No focus-return expectation — the file manager keeps focus; a captured-confirmation toast is sufficient, opening a window would be surprising |
| macOS Share (selected text → Share sheet → SteleKit) | Same as any macOS Share Extension — sub-second acknowledgment expected, matches system Share sheet's own dismissal animation | Share sheet's own "Done"/checkmark affordance handles the primary feedback; if SteleKit needs an extra confirmation, it should be a system notification, not a new window, to match how other Share destinations (Notes, Reminders) behave | Focus returns to the source app automatically once the Share sheet closes — this is standard macOS Share Extension behavior and needs no special handling by SteleKit |

Key shared expectation across all three: **the user should never have to click into a
separate "SteleKit desktop app" to confirm the capture happened.** Anything that pulls
the full app window forward when it wasn't already frontmost breaks the "in and out"
job-to-be-done and reintroduces the context-switch the whole feature exists to avoid.

## 3. Accessibility — global-hotkey popup

Applies specifically to the Linux global-hotkey capture popup option (the only v1
surface that's a genuinely new custom window, vs. OS-native Share/context-menu
handlers):

- **Fully keyboard-operable is non-negotiable**, not just nice-to-have: a user who
  triggered capture via hotkey precisely to avoid reaching for the mouse must be able
  to type text, save, and dismiss without ever touching it. Concretely: autofocus the
  text field on open (as Android's `CaptureActivity` already does via
  `FocusRequester`), bind `Esc` → dismiss-with-auto-save-if-non-blank (mirroring
  Android's `BackHandler` semantics exactly), and a save keybinding (`Cmd/Ctrl+Enter`
  is the de facto standard for "submit from a multi-line text field," matches Things
  3/Todoist quick-entry panels).
- **Focus trap + focus restoration** are both required and are two different
  mechanisms: while the popup is open, `Tab` must cycle only within it (standard modal
  focus-trap pattern — `role="dialog"`/platform equivalent, focus moves to the first
  interactive element on open); on close, focus must return to whatever had system
  focus before the hotkey fired — this is the desktop equivalent of Android's
  `finish()` returning focus to the calling app, and is the single most
  reliability-sensitive a11y behavior since dropping it means every capture leaves the
  user's keyboard input aimed at the wrong window afterward.
  [UXPin: accessible modals with focus traps](https://www.uxpin.com/studio/blog/how-to-build-accessible-modals-with-focus-traps/),
  [Mugo Web: keyboard focus traps](https://www.mugo.ca/Blog/Making-keyboard-navigation-more-accessible-with-JavaScript-focus-traps)
- **Screen reader**: the popup should announce itself as a dialog with a labeled
  heading immediately on open (`role="dialog"` + `aria-modal` equivalent in whatever
  desktop toolkit/Compose Multiplatform uses for accessibility semantics), so a screen
  reader user isn't left wondering whether a window appeared. Desktop toolkit
  equivalents of ARIA (e.g. platform accessibility APIs — AT-SPI on Linux, UIA on
  Windows, NSAccessibility on macOS) need the same semantic role tagged; Compose
  Multiplatform's desktop accessibility support should be checked for parity before
  assuming this is free.
- **Always-on-top ≠ modal to the OS**: a global-hotkey popup is deliberately *not* an
  OS-level modal (other apps keep running), so the focus trap must be enforced at the
  app/window level, not relied upon from window-manager modality — verify this
  explicitly against whatever windowing layer SteleKit desktop uses, since Compose
  Desktop's window API doesn't give this for free the way a native `NSPanel` or GTK
  dialog might.

## 4. Error states

- **(a) SteleKit isn't running, out-of-process trigger** (macOS Share Extension,
  Nautilus script, Windows context-menu handler — all separate OS processes per the
  requirements doc's "Key architectural fact"): the requirements doc correctly flags
  this as *the* open architectural question, not just a UX detail. Two viable UX
  contracts, and the choice should be made explicit rather than left to fall out of
  implementation:
  1. **Cold-start-and-capture**: the trigger launches SteleKit in the background,
     writes directly (bypassing IPC to a running instance if none exists), and shows a
     toast/notification confirming the write — no window ever appears. Matches the
     context-menu mental model above (fire-and-forget) and avoids the latency of a
     full UI boot.
  2. **Cold-start-and-show-popup**: the trigger launches SteleKit's capture window
     (even if the main app isn't otherwise running), which is slower but gives the
     user a chance to edit before saving, matching what a global hotkey would show if
     the app were already open.
  Recommendation driven by JTBD (see §5): default to (1) for the OS-integration
  surfaces (Share/Nautilus/context-menu) since their whole job is "get this out of my
  head with zero window management," and reserve the visible-popup behavior for the
  in-process global-hotkey path where a window is already the expected mechanism.
- **(b) No graph configured**: Android's precedent (`NoGraphPlaceholderContent()`) is
  directly reusable as the UX contract — show a small explanatory surface ("no graph
  configured" title + body + a single action button to open the main app and set one
  up) rather than silently dropping the capture or throwing an unstyled error. For an
  out-of-process trigger with no graph, this likely has to be a **notification**
  ("SteleKit: no graph configured — click to set one up") rather than a popup window,
  since there's no capture text field to show if there's nowhere to save it.
- **(c) Write fails**: Android's precedent is an inline `Snackbar` inside the
  still-open sheet, letting the user retry without losing their typed text — the sheet
  does not close on failure. For desktop: the in-process hotkey popup should follow
  the same pattern (inline error, keep window + text open, don't auto-dismiss). For
  out-of-process triggers where no window is shown at all (toast-only path from 4a),
  write failure needs its own **failure notification distinct from the success
  toast**, ideally one that surfaces the captured text (e.g., copies it to clipboard
  or opens the popup pre-filled) so the user's thought isn't silently lost — this is
  the one failure mode that's strictly worse on desktop than on Android, since Android
  always has the sheet open to retry from and a toast-only capture path does not.

## 5. Jobs-to-be-done and platform prioritization

- **Functional job**: get a thought out of working memory and into the graph before
  it's lost or before it derails the current task — capture, not compose. Confirmed by
  independent sources converging on the same framing: "if saving a note takes more
  than five seconds, you will not do it consistently," "capture has to be
  frictionless... the best second brains make capture almost automatic."
- **Emotional job**: eliminate context-switch anxiety — the user should not have to
  worry that opening a capture surface will pull them into the full app, lose their
  place in the source document, or demand a decision (which page? which format?) before
  the thought is safely stored. Android's design encodes this directly: auto-save on
  every dismissal path, single destination (today's journal, no picker), `finish()`
  back to the source app.
- **Social job**: effectively none for this feature — quick capture is a private,
  individual workflow step; no evidence from research that a social/status dimension
  applies (contrast with, say, a "shared inbox" feature).
- **Prioritization implication**: the emotional job (frictionless, zero decisions,
  no window management) is best served by whichever v1 surface gets a user from
  "have a thought" to "saved" in the fewest steps with the least implementation
  complexity. Ranked by JTBD value ÷ implementation cost:
  1. **Linux global-hotkey popup** — highest value-to-cost: runs in-process (per the
     requirements doc's own architectural note), so no IPC/cold-start problem to
     solve at all, and it's the pattern with the strongest existing user mental model
     (Raycast/Alfred/Spotlight/Quick Note muscle memory) transferable with zero user
     education. Should be sequenced first regardless of platform order implied by the
     requirements doc's list.
  2. **macOS Share Extension/Services-menu entry** — moderate cost (out-of-process IPC
     + cold-start decision required) but high value because it captures a job Quick
     Note *doesn't* — going straight from selected text in another app into
     SteleKit specifically, vs. Quick Note's generic Notes.app destination — so it is
     additive rather than redundant.
  3. **Windows context-menu registry handler** — lowest per-capture value (context-menu
     invocation implies the source is already a file/selection in a file manager, a
     narrower use case than "any thought, any app") and comparable IPC/cold-start cost
     to macOS, so should trail unless Windows user share data says otherwise.
  Recommend building and shipping the Linux hotkey popup first as the reference
  implementation of the in-process capture UX (text field, autosave-on-dismiss,
  focus-trap, error recovery), then reusing that same popup UI for the "cold-start
  with visible window" fallback path on the other two platforms if/when IPC to a
  running instance isn't available.

## Open questions for the plan phase

1. Does Compose Multiplatform desktop expose enough accessibility API surface (focus
   trap, dialog role, focus restoration) to meet the keyboard/screen-reader bar above
   without custom platform-channel code per OS?
2. For out-of-process triggers, is "cold-start and write directly, no window" (§4a
   option 1) actually feasible given the DB write path requires `DatabaseWriteActor` /
   `GraphManager` initialization — i.e., is a headless cold-start meaningfully faster
   than a windowed one, or does the JVM startup cost dominate either way? Needs a
   timing spike before committing to the toast-only UX contract.
3. Should the Linux hotkey-popup capture window be reused verbatim as the fallback UI
   for macOS/Windows out-of-process cold-start, or does that reintroduce the "window
   appears unexpectedly" problem for surfaces where users expect toast-only feedback
   (§2 table)?
