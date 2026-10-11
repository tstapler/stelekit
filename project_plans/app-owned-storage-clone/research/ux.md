# UX Research: App-Owned Storage as an Explicit, Selectable Destination

Phase 2 research for `app-owned-storage-clone`. Companion to the sibling research docs (technical
feasibility of `GitShadowWorktree` promotion, `FolderSyncSettings`/`HostDirectorySync`
generalization, etc.) — this file covers UX only: comparable patterns, mental models,
accessibility, error states, and the JTBD framing for the picker/move/link surfaces described in
`requirements.md`.

## 1. Comparable UX patterns in similar products

### Obsidian — vault picker and "Move vault"

Obsidian's model is the closest existing analog to this feature and validates the requirement's
core design choice — **app storage as a row inside the same picker, not a separate toggle**:

- **Vault creation** offers "Store in iCloud" as a first-class option alongside on-device
  creation directly in the "Create new vault" flow on iOS, not as a secondary settings screen
  reached after the fact ([Obsidian Forum](https://forum.obsidian.md/t/store-in-icloud-option-never-appears/74193)).
- **Moving an existing vault** is *not* a first-class in-app action in Obsidian — it's a
  documented manual procedure: back up first, quit the app on every device to pause sync, use the
  OS's own Files app to drag the vault folder between "iCloud Drive" and "On My iPhone," then
  reopen in Obsidian and confirm ([Obsidian Help — Manage vaults](https://huggingface.co/spaces/anpigon/obsidian-qa-bot/blob/e272fa2f2c33a981360ec7e29f42e93f75fd504f/docs/obsidian-help/Files%20and%20folders/Manage%20vaults.md)).
  This is a **negative example worth citing explicitly in the PR/plan**: Obsidian's own users hit
  exactly the failure mode this project's requirements.md Risk Control section is designed to
  prevent (quit-everywhere, hope nothing was mid-write, manually verify afterward). SteleKit doing
  copy-verify-confirm-before-delete as a guided in-app flow is a **strict UX improvement** over the
  closest comparable product's real behavior — worth stating as the design's value proposition,
  not just its safety net.
- A recognition detail worth carrying over: Obsidian's iCloud vault folder must be **created by
  the app itself** to carry an app-specific marker (a folder icon), and a folder assembled by hand
  outside the app "may not be recognized" ([Obsidian Forum](https://forum.obsidian.md/t/store-in-icloud-option-never-appears/74193)).
  Analogous risk here: a SAF-linked folder that a user later reorganizes outside SteleKit (renames
  `.git`, moves subfolders) could silently break a "link." The error-state design (§4) must treat
  "folder structure changed underneath us" as an expected failure mode, not a corner case.

### Google Drive for desktop — Mirror vs. Stream

Google Drive's client offers **Mirror files** (full local copy, works offline, occupies disk) vs.
**Stream files** (cloud-primary, downloads on open, minimal footprint) as two named, mutually
exclusive modes — directly analogous to this project's "relocate" (mirror = local copy becomes the
copy of record) vs. "link" (stream/mirror-continuously = both stay in sync)
([Google Drive Help](https://support.google.com/drive/answer/16631477?hl=en)).
Two transferable lessons:

- **Switching Mirror → Stream is explicitly flagged as data-loss-risk** if done before pending
  uploads finish — Google's own guidance is "ensure recent changes have finished syncing before
  switching," i.e. the same "quiesce in-flight writes before starting a move" requirement this
  project's Rabbit Holes section calls out for `GitShadowFlushActor`. The precedent supports
  building an explicit "waiting for in-flight writes to finish…" step into the relocate/link wizard
  before the copy begins, and surfacing it as UI (a spinner state, not a silent pre-check) rather
  than hiding it.
- **Folder-location changes warn about re-download cost**, not just correctness — switching
  Stream → Mirror without the files already present locally triggers a full re-download. This
  maps to this project's "graph-scale reads must be paginated" constraint: relocate/link UI should
  set expectations about *time*, not just correctness, for large graphs (8,000+ pages), e.g. a
  progress indicator with page/file count, not a bare spinner.

### VS Code — Open Folder quick-pick with recents

VS Code's own built-in "recent workspaces" list lacks a *pin* feature — it's a plain
most-recently-used list, and users have filed a standing feature request (open since 2021,
[microsoft/vscode#117721](https://github.com/microsoft/vscode/issues/117721)) for exactly a
pinned/starred entry above the MRU list, satisfied today only by third-party extensions like
[vscode-pin-folders](https://github.com/cedric05/vscode-pin-folders/). The lesson for this
project: **a pinned "App storage" row above (or visually distinct from) a recents/browse list is a
validated, wanted pattern that a major dev tool still lacks** — implementing it natively here is a
differentiator, not over-engineering. Concretely: render the location picker as a single scrollable
list with "App storage" as a fixed, non-scrolling first row (distinct background tint or a leading
divider), then "Browse…" as a second fixed action, with any remembered/recent real folders below
that — mirroring VS Code's requested-but-unbuilt structure.

### Android system Files app — "Move to" vs. "Copy to"

Android's own file manager exposes **both verbs side by side** in its selection action bar (not
merged into one "move" button with a checkbox) — "Copy to" and "Move to" are visually adjacent,
equal-weight actions, reinforcing that users already have a mental model distinguishing "duplicate,
keep both" from "relocate, remove original." This directly supports keeping **relocate** (final,
source removed after grace period) and **link** (perpetual duplicate, never removes source) as
separately labeled buttons/cards in the "move storage location" UI, not a single action with a
mode toggle buried inside — the requirement's own "distinct, user-chosen operations" language
already anticipates this, and the OS-native precedent is direct support for that choice.

### Cross-cutting pattern: the picker is a list, not a fork in the road

Every comparable product that gets this right (Obsidian's create-vault sheet, VS Code's
open-recent quick pick) treats "app-managed default" and "browse anywhere" as **rows in one list
component**, never a preliminary yes/no question the user answers before reaching a folder browser.
This is exactly requirement §In Scope point 5's constraint. The anti-pattern to avoid (and to
flag if seen in early designs) is a modal that asks "Use app storage? (Yes / No, let me pick)"
before showing any picker UI at all — that's an extra decision screen, not a picker.

## 2. User mental models and expectations

**What "stored in the app" implies to users**, inferred from how competitors word it and from the
support-forum friction around it:

- **Portability/visibility fear is real and specific, not vague.** The Obsidian iCloud-vault
  recognition quirk (§1) shows users already worry "will this be a normal folder I can find
  later, or some app-internal thing I can't get to." SteleKit's own codebase corroborates this
  concern is warranted for the app-storage case: on Android, app storage is `filesDir` — invisible
  to any file manager, not `MANAGE_EXTERNAL_STORAGE`-visible, and wiped on app uninstall. This is
  a real, not merely perceived, backup-safety difference and the copy must not paper over it.
- **Avoid the bare word "storage" as the *only* label — pair it with a concrete consequence.**
  Successful apps qualify the technical term with what it means for the user, not just where bytes
  live:
  - Obsidian: "This device" / "iCloud" — device-first framing, sync implied by the alternative's
    name, not spelled out.
  - Google Drive: "Mirror files" (a full copy exists locally) vs. "Stream files" (cloud is
    primary) — names describe *behavior*, not architecture.
  - This codebase's own `FolderSyncSettings.kt` already does this well and should be the reused
    tone: "This graph is stored in your browser only. You can connect it to a folder on your
    computer so edits made here are written straight to your files — no export, no git required."
    (line 96-98) — plain-language consequence first, jargon (OPFS, File System Access API) never
    appears in user-facing copy.
- **Recommended pattern for this project's "App storage" row**: a one-line subtitle under the row
  label, not a tooltip or separate info dialog — consistent with `FolderSyncSettings`'s existing
  "no export, no git required" style. Suggested phrasing directions (final wording is a copy
  pass, not this research's job, but the *shape* is):
  - Android: "App storage" / subtitle: "Kept inside SteleKit only — not visible in your device's
    file manager, and removed if you uninstall the app."
  - Web: "This browser" / subtitle: "Kept in this browser only — not backed up automatically, and
    lost if you clear site data."
  Both subtitles state the concrete downside (invisibility, deletion-on-uninstall/clear-data)
  rather than a vague "less safe" — matching this codebase's existing register of naming the exact
  mechanism (see `FolderSyncSettings`'s own doc comments' insistence on precise, non-generic
  copy).
- **"Pick a folder" needs an equally concrete subtitle**, not just contrast-by-omission: e.g.
  "Visible in your Files app, backed up if your device backs up that folder, and portable to
  another app" — so the trade-off reads as two named things with different properties, not
  "normal" vs. "risky."

## 3. Accessibility requirements

Grepped this codebase's existing Compose semantics conventions directly (`GitSetupScreen.kt`,
`Sidebar.kt`, `SyncStatusBadge.kt`, `FolderSyncStatusBadge.kt`) rather than starting from generic
WCAG guidance — the wizard and status-badge patterns to extend already exist and are internally
consistent:

**Existing conventions to follow (don't invent new ones):**

- **Radio-style selection rows** use `Modifier.selectable(selected = ..., role = Role.RadioButton,
  onClick = ...)` on the row plus a `RadioButton(selected = ..., onClick = null)` inside it (the
  `onClick = null` on the inner control is deliberate — the outer row owns the click target so the
  whole row, not just the small circle, is tappable/keyboard-activatable). See
  `GitSetupScreen.kt`'s `Step1CloneMode` (lines 719, 727) and `Step2CloneMode`'s auth-type radios
  (lines 966-990). **The location picker's "App storage" vs. "Browse a folder" choice should use
  this exact pattern** if rendered as a persistent radio pair; if rendered as a list-with-pinned-row
  (per §1's VS Code-style recommendation), each row should instead be `Role.Button` +
  `.clickable`, matching the pattern below.
- **Icon-only actionable controls** always carry a real (non-null) `contentDescription` describing
  the action, not the icon: `"Browse for directory"` (line 790), `"Browse subfolders of the
  repository root"` (line 835) — never "Folder icon" or similar. Purely decorative icons
  (`WikiSubdirBrowserDialog`'s inline folder glyphs next to text, line 907) correctly pass
  `contentDescription = null` since the adjacent `Text` already names the item. **New icons in
  this feature (an "App storage" row icon, a relocate/link distinguishing icon) should follow the
  same split**: decorative-next-to-label icons get `null`; a *lone* icon that is itself the click
  target gets a full description of the action.
- **State-driven clickable rows with a live status text** — the exact shape needed for "a link
  that later breaks" (§4) — has a complete, tested reference implementation in
  `FolderSyncStatusBadge.kt`:
  - `Modifier.semantics { role = Role.Button }.clickable(...)` only on rows that are currently
    actionable; a `FocusRequester` + `everInteracted` boolean keeps a row that just became
    non-interactive (e.g. transitioned from "broken, tap to fix" to "healthy") a valid focus
    target so keyboard/switch-access focus doesn't strand mid-flow (lines 187-209, with the
    accompanying doc comment at 177-186 explaining exactly why this is needed — the underlying
    click can trigger a native OS permission prompt outside Compose's own focus trap).
  - The status **text** itself carries `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`
    (line 227) so a screen reader announces state transitions ("Folder not found — Reconnect" →
    "Synced to Documents") without interrupting whatever the user is doing — critical for a "link
    broke silently" notification (§4b) to actually be perceived by an AT user, not just
    sighted-user color/text.
  - **This is the component to reuse or directly extend for "link is broken" surfacing**, not a
    new indicator — see §4's discussion of reusing `SyncStatusBadge`/`FolderSyncStatusBadge`
    rather than inventing a third badge family.
- **Destructive-confirmation dialogs** (the "verified — delete old copy?" step) have no existing
  in-repo precedent to follow (the "Remove graph" `AlertDialog` in `Sidebar.kt` lines ~556-570 is
  the closest, and it is a plain `AlertDialog` with a red-tinted `TextButton` as the confirm
  action — no explicit focus-management code, relying on Compose Material3's default `AlertDialog`
  behavior). External guidance (W3C WAI-ARIA Authoring Practices,
  [Alert Dialog Example](https://www.w3.org/WAI/ARIA/apg/patterns/alertdialog/examples/alertdialog/))
  fills the gap and should be followed given no stronger in-repo precedent exists:
  - Use `role="alertdialog"` semantics (Compose Material3's `AlertDialog` already sets this
    correctly under the hood) with the destructive action **not** auto-focused — default focus
    should land on "Cancel"/"Keep both copies," not "Delete old copy," so a keyboard user pressing
    Enter reflexively doesn't trigger the destructive path.
  - The dialog body must name the **exact** source and destination (already required by
    requirements.md's Risk Control section) — this doubles as the `aria-describedby`-equivalent
    content a screen reader announces on open, so the safety requirement and the accessibility
    requirement are the same sentence, not two separate asks.
  - Escape must dismiss without deleting (matches every existing `AlertDialog`'s
    `onDismissRequest` convention already used throughout this codebase, e.g. `graphToRemove =
    null` in `Sidebar.kt` line 581/645).
- **Multi-step wizard progress** (`GitSetupScreen.kt`'s step structure): the existing wizard has
  **no explicit `semantics` block announcing "step 2 of 4"** — it's implicit in the visible title
  text (`Text("Repository path", style = titleMedium)`) and a progress bar mentioned in the
  requirements' UI description but not found as an accessibility-labeled element in the grepped
  source. This is a **gap in the existing pattern, not something to copy** — if this feature adds
  a step (a "choose destination" step before or merged into `Step2RepoPath`), consider adding a
  `stateDescription` or heading-level semantics announcing step position, since the wizard is
  about to grow and this is a good moment to close that gap rather than propagate it further.

## 4. Error states and edge cases

**(a) Verification step fails (destination copy incomplete/corrupt).** No existing UI pattern in
this codebase surfaces a failed integrity check specifically, but `FolderSyncSettings.kt`'s
`ReconciliationUiState.Failed` (referenced at line 77, rendered via
`FolderSyncReconciliationProgress`) is the direct structural analog — a one-shot async operation
that can terminate in `Connecting → Summary | Failed`, with `Failed` carrying a message and an
explicit `onRetry` callback (line 87). **The relocate/link wizard's verification step should use
this same three-state shape** (`Connecting`/`Verifying` → `Summary` → `Failed`), not a new state
machine — `Failed` must, per this project's Risk Control section, leave the source untouched and
offer only "Retry" or "Cancel" (never a "Delete anyway" affordance, which would defeat the entire
safety net).

**(b) A "link" silently breaks later (Web File System Access permission revoked, or Android SAF
grant revoked).** This must **reuse the existing sidebar status-badge family**
(`SyncStatusBadge`/`FolderSyncStatusBadge`), not invent a third indicator — this is explicit,
evidence-backed guidance, not a stylistic preference:

- `FolderSyncStatusBadge`'s `HostAccessState.Disconnected` ("Folder not found — Reconnect") and
  `HostAccessState.Denied` ("Folder access declined — Grant access") already model *exactly* this
  failure class for web's existing OPFS↔real-folder livesync, including the deliberate
  "Reconnect" vs. "Grant access" copy split (`folderSyncBadgeContent`'s doc comment, lines 51-58)
  so a user who explicitly clicked "Don't allow" gets different copy than one whose folder just
  went missing. **A generalized "link" (this project's new concept) breaking should map onto
  these same states**, extended to cover Android SAF grant revocation as a platform-specific cause
  of the same `Disconnected`/`Denied` states, rather than adding Android-specific copy variants.
- The `hostWriteStuck` / `SyncDegraded` row (pendingWriteCount > 0 while nominally `Granted`,
  lines 107-110) is the right model for "link looks fine but hasn't actually synced in a while" —
  a distinct, real state already designed and shipped for the conceptually adjacent OPFS-livesync
  feature.
- Recommendation for Phase 3 planning: extend `HostAccessState` (or a shared successor type,
  if the technical-feasibility research concludes `FolderSyncSettings`/`HostDirectorySync` needs
  generalizing anyway — see Open Questions in requirements.md) to represent Android SAF permission
  loss using the same enum, so `FolderSyncStatusBadge` (or its generalized successor) is the single
  place link-health is rendered on both platforms — directly satisfying requirements.md's "no more
  than one picker/status implementation per platform" spirit, applied to the status indicator as
  well as the picker.

**(c) Insufficient storage space at the destination.** No existing pattern in this codebase
handles this (grep found no "insufficient space"/"disk full" strings). External precedent (Google
Drive's Stream→Mirror re-download warning, §1) supports doing this check **before** starting the
copy, not discovering it mid-copy: estimate source size (file count/byte total — already
computable from the same bounded-read machinery used for the largest-graph benchmarks) and compare
against destination free space where the platform exposes it (Android: `StatFs`; Web OPFS: the
Storage Manager API's `navigator.storage.estimate()`, which reports quota but not always exact
free space). Where the platform can't reliably report free space (mid-flight on web, since
`estimate()` is an approximation), fail gracefully mid-copy the same way as (a) — verification
failure due to a partial/truncated destination is a subset of "verification failed," not a
separate error class the UI needs to special-case.

**(d) User backs out mid-wizard after a copy has started.** The copy-then-verify-then-confirm
design already makes this safe by construction: since the source is never touched until after
verification *and* explicit user confirmation, backing out at any point before that confirmation
step is a no-op from a data-safety standpoint — the correct UI response is to cancel/discard the
partial destination copy (if relocate) or simply stop (if link, since link never removes anything).
The one thing needing explicit handling: a partially-written destination directory left behind
after cancel must be cleaned up (or clearly marked incomplete) so it isn't mistaken for a real
graph on next app launch — this is a data-integrity/technical question for Phase 3 planning
(scope it against `GitShadowWorktree`'s existing orphan-sweep mechanism at
`GitShadowWorktree.kt:406`, which already solves an analogous "abandoned partial state" cleanup
problem for a different subsystem — reusing that pattern rather than inventing a new one is
consistent with this doc's overall theme of extending existing machinery).

## 5. Jobs-to-be-done framing

- **Functional job**: "Get my notes usable on this device without navigating Android's
  folder-permission system or worrying about which folder is 'right.'" App storage directly
  serves this job with zero extra decisions. A folder-based destination serves a different
  functional job — "keep my notes as plain files I can reach from other tools" — which is equally
  valid but is *not* the lower-friction path and should not be conflated with it.
- **Emotional job**: "Confidence my data won't silently vanish." This is the dominant job the
  Risk Control section (copy-verify-confirm-before-delete, undo window) is built to serve, and the
  research in §1-2 shows real precedent for this fear being warranted (Obsidian's manual-move
  procedure is a real risk surface its own users navigate today; this codebase's `filesDir`
  invisibility and uninstall-wipe behavior for Android app storage is a real, not hypothetical,
  backup-safety difference).
- **Social job**: minimal, as requirements.md already assumes — this is a personal note app with
  git as its cross-device/backup mechanism; app storage vs. folder storage has no sharing/
  collaboration dimension worth designing for here.

**Default-selection recommendation, argued from the above**: **do not pre-select "App storage" as
the default** in the picker, despite it being the lowest-friction functional path. Reasoning:

1. The emotional job (confidence data won't vanish) is the one this project's entire safety-net
   design is built around, and defaulting to the option with the *weaker* backup/visibility
   properties (per §2's concrete-subtitle framing) actively works against that job for any user
   who doesn't read the subtitle before hitting "Next" — which, per standard picker UX, is most
   users on a first pass through a wizard.
2. Every well-regarded comparable product examined in §1 defaults to (or requires explicit,
   friction-bearing setup for) the *device-visible* option and treats the app-managed option as an
   equal, discoverable, but not pre-selected peer: Obsidian's vault creation defaults to on-device
   folder creation (iCloud is an explicit additional choice, not pre-checked); Google Drive
   defaults new setups to "Stream files" only after an explicit onboarding step explaining the
   trade-off, never silently.
3. This does **not** contradict requirement §In Scope point 5 ("App storage must be a selectable
   entry inside the picker itself... not a separate control the user must find first") — that
   constraint is about *reachability* (one picker, no preliminary gate), not about which row is
   pre-highlighted. A picker can present "App storage" as the visually first, always-visible
   pinned row (satisfying reachability and the VS Code-precedent recommendation in §1) while still
   requiring an explicit tap/keyboard-select before "Next" is enabled — i.e., **no row
   pre-selected at all**, forcing one deliberate choice, rather than defaulting to either option.
   This is a stronger recommendation than "default to folder-picker" (which would just move the
   friction rather than removing the "surprise later" risk) and is consistent with how this
   codebase's own `AddGraphDialog` (`App.kt:2104-2107`) already disables its confirm button until a
   required field is filled — the same "no default action, must actively choose" pattern already
   used elsewhere in this app for a different field.
4. Exception already decided by existing behavior and out of scope to relitigate: web's *current*
   fallback default to OPFS when the File System Access API is entirely unsupported (no folder
   option exists to default away from) should remain unchanged — the "no pre-selection" argument
   above applies only when both options are genuinely available to choose between.

## Sources

- [Obsidian Help — Manage vaults](https://huggingface.co/spaces/anpigon/obsidian-qa-bot/blob/e272fa2f2c33a981360ec7e29f42e93f75fd504f/docs/obsidian-help/Files%20and%20folders/Manage%20vaults.md)
- [Obsidian Forum — "store in iCloud" option never appears](https://forum.obsidian.md/t/store-in-icloud-option-never-appears/74193)
- [microsoft/vscode#117721 — Pin recent workspaces/folders/files](https://github.com/microsoft/vscode/issues/117721)
- [vscode-pin-folders extension](https://github.com/cedric05/vscode-pin-folders/)
- [Android Developers — Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Android Developers — Manage all files on a storage device](https://developer.android.com/training/data-storage/manage-all-files)
- [Google Drive Help — Manage Drive for desktop: Advanced guide (Mirror vs. Stream)](https://support.google.com/drive/answer/16631477?hl=en)
- [W3C WAI-ARIA Authoring Practices — Alert Dialog Example](https://www.w3.org/WAI/ARIA/apg/patterns/alertdialog/examples/alertdialog/)
- In-repo (read directly, this session):
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt` (Step1CloneMode
  L708-739, Step2RepoPath L741-853, WikiSubdirBrowserDialog L862+)
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (AddGraphDialog L2084-2116)
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (Edit Graph dialog
  L573-651, LeftSidebar badge wiring L206-220)
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt` (full file)
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt` (full file)
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncSettings.kt`
  (full file)
  `git show ffea987773` (sidebar dirty-count/last-synced status commit)
