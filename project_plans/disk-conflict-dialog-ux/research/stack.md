# Research: Technology Stack — disk-conflict-dialog-ux

**Date**: 2026-07-03
**Scope**: Ground UX fixes to `DiskConflictDialog` in the actual existing Compose Multiplatform
code, not generic recommendations.

## 1. Core files (existing implementation)

| File | Role |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt` | The dialog composable (99 lines, entirely self-contained `AlertDialog`) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (L134–138, L205–223) | `diskConflict: DiskConflict?` (active dialog state) and `pendingConflicts: Map<String, PendingConflict>` (deferred, keyed by filePath); `DiskConflict`/`PendingConflict` data classes |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (L1342–1635) | `observeExternalFileChanges()` (four-tier protection + conflict construction), `checkAndShowPendingConflict()`, resolution actions (`keepLocalChanges`, `acceptDiskVersion`, `manualResolve`, `saveAsNewBlock`) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphDialogLayer.kt` (L274–282) | Wires `appState.diskConflict` to `DiskConflictDialog`, four callbacks map 1:1 to ViewModel actions |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt` | Emits `ExternalFileChange(filePath, content)` on `_externalFileChanges: SharedFlow` — `content` is the **whole raw file** read off disk, not a block |

## 2. Confirmed: the granularity mismatch (Requirement #1)

`DiskConflict.localContent` is sourced from a **single block**:
```kotlin
// StelekitViewModel.kt L1440-1444
val localContent = blockStateManager
    ?.blocks?.value?.get(currentPage.uuid.value)
    ?.find { it.uuid.value == conflictBlockUuid }?.content
    ?: blockRepository.getBlockByUuid(BlockUuid(conflictBlockUuid)).first().getOrNull()?.content
    ?: ""
```
`DiskConflict.diskContent` is the **entire markdown file** as read by `GraphFileWatcher`
(`ExternalFileChange.content`, passed straight through from `event.content` with no
block-scoping). This confirms requirement #1 exactly as described: comparing one block against
a whole file. Fixing this means either (a) scoping the disk side down to the corresponding block
by re-parsing `event.content` with the existing outliner parser and diffing block-for-block, or
(b) scoping the local side up to the full page. Given block UUIDs are stable across parses (the
outliner pipeline preserves them via `existingBlocks.associate { it.uuid to it.content }` pattern
already used in `GraphLoader.kt` L1396/L1742 for diff/merge), re-parsing the incoming disk content
and extracting just the block with `conflict.editingBlockUuid` is the natural fix — it reuses
machinery `GraphLoader` already has, not new parsing logic.

## 3. Confirmed: hard 200-char truncation (Requirement #2)

Both previews in `DiskConflictDialog.kt` (L52, L69) truncate identically:
```kotlin
text = conflict.localContent.take(200).let {
    if (conflict.localContent.length > 200) "$it…" else it
}
```
No expansion affordance exists today. The dialog's outer `Column` is already
`.verticalScroll(rememberScrollState())` (L36), so there's headroom to simply not truncate at all
for block-scoped content (blocks are rarely more than a few hundred chars) — but per requirements,
an explicit "view full" escape hatch is still wanted for pathological cases (a block with a huge
embedded table, or the current whole-file disk content until fix #1 lands).

## 4. Confirmed: git-style conflict-marker injection (Requirement #3)

`manualResolve()` (`StelekitViewModel.kt` L1574–1602) builds literal git markers and writes them
directly into the block's content, then reopens it for editing:
```kotlin
appendLine("<<<<<<< Your edit")
append(conflict.localContent)
...
appendLine("=======")
append(conflict.diskContent.lines().firstOrNull { it.startsWith("- ") }
    ?.removePrefix("- ") ?: conflict.diskContent.take(200))
...
append(">>>>>>> Disk")
```
Two problems beyond the requirements doc's framing: (1) no inline explanation is shown anywhere —
the dialog's `TextButton` label is just `"Manual resolve (show conflict markers)"` (L93-95 of the
dialog file) with zero help text; (2) the disk side of the marker block is built with a fragile
heuristic (`lines().firstOrNull { it.startsWith("- ") }`) that assumes Logseq's `- ` bullet prefix
and falls back to `.take(200)` of the whole file — this is a second, independent truncation bug
layered on top of requirement #2, worth flagging to the planning phase even though it's not
explicitly named in requirements.md.

## 5. Confirmed: deferred-conflict snackbar (Requirement #4)

In `observeExternalFileChanges()` (`StelekitViewModel.kt` L1376–1396), when the changed file isn't
the currently-open page, the event is suppressed, stored into `pendingConflicts` (keyed by
`filePath`), and a one-shot snackbar fires only on first detection:
```kotlin
if (existing == null) {
    sendSnackbar("\"$pageName\" was modified on disk — open it to review")
}
```
Once dismissed, the *only* remaining trace is the `pendingConflicts` map entry itself — nothing in
the UI tree reads that map for a persistent indicator today. `checkAndShowPendingConflict()`
(L1467–1487) is the sole consumer, and it only fires when the user navigates directly to that
page's `Screen.PageView` (called from two `navigateTo`-style call sites, L1107/L1145/L1167). There
is no sidebar/page-list lookup against `pendingConflicts` anywhere — confirming the requirement's
"no persistent marker" complaint precisely.

## 6. Reusable UI primitives already in this codebase

| Need | Existing pattern to reuse | Location |
|---|---|---|
| Expand/collapse text | `var expanded by remember { mutableStateOf(...) }` + `AnimatedVisibility(visible = expanded)` + a rotating `Icons.Default.ExpandLess/ExpandMore` (or `KeyboardArrowDown/Right`) icon toggled via `.clickable` | `ReferencesPanel.kt` L368–397 (chevron rotates on click), `PerformanceDashboard.kt` L441–504 (up/down arrow swap) — both are simple, no new libs |
| Persistent per-item badge/indicator | Pill/dot `Row` in a `Surface`/`Box` background, colored by state, with `contentDescription` for a11y | `SectionBadge.kt` (colored-dot pill, tappable) and `SyncStatusBadge.kt` (icon+label combo keyed on a sealed state, amber `Warning` icon for `ConflictPending`/`JournalMergeReady` — **this is the closest existing analog to "unresolved conflict" styling**, reuse the same amber `Color(0xFFF59E0B)` + `Icons.Default.Warning` convention) | `ui/components/SectionBadge.kt`, `ui/components/SyncStatusBadge.kt` |
| Tooltip / inline help | `TooltipBox` + `rememberTooltipState()` + `TooltipDefaults.rememberPlainTooltipPositionProvider()` (Material3 built-in, no extra dep) | `AnnotationToolbar.kt` L216–271 (two working examples) |
| Sidebar page-list rendering (where a badge would need to attach per-page) | `Sidebar.kt` renders page list items with `Icons.Default.Star/StarBorder` for favorites — analogous slot exists for adding a conflict-warning icon per list item | `ui/components/Sidebar.kt` (favorite-star pattern, L21-22 imports) |
| Full-content view escape hatch | No existing "read full text" dialog/sheet component found in `ui/components/`; would be a small new composable (a scrollable `AlertDialog`/`ModalBottomSheet` reusing Material3 primitives already imported everywhere) — not a new dependency, just a new small composable |

No existing `ExpandableText`, diff-view, or generic "modal text viewer" composable exists yet —
each would be new but trivially small (~30-60 lines) using only already-imported Compose/Material3
APIs.

## 7. Diffing: a library is already on the classpath, unused

`kmp/build.gradle.kts` L82-83:
```kotlin
// Kotlin Multiplatform Diff — used for conflict hunk display
implementation("io.github.petertrr:kotlin-multiplatform-diff:1.3.0")
```
This is a genuine surprise: **the dependency is already declared, with a comment explicitly
anticipating "conflict hunk display"** — but a repo-wide search (`grep -rl "petertrr\|DiffUtils\|import.*\.diff\."`)
found **zero files actually importing or using it**. It appears to have been added in
anticipation of exactly this feature and never wired up. `kotlin-multiplatform-diff` (petertrr) is
a pure-Kotlin, KMP-target-agnostic Myers-diff implementation exposing `DiffUtils.diff(original,
revised)` → `Patch<T>` of line/char-level `Delta`s — no JVM-only or platform-specific code, so it
is safe to use from `commonMain` UI code directly (desktop/Android/iOS/Web all resolve it).

**Recommendation for planning phase**: use this library rather than hand-rolling a diff. It is
already a committed dependency (no new-dependency approval needed), matches the project's stated
purpose, and a from-scratch diff algorithm would be exactly the kind of "unrequested abstraction"
this codebase's CLAUDE.md conventions push back on. Given the content here is block-scoped after
fixing requirement #1 (a few lines, not a multi-KB file), a line-level diff via
`DiffUtils.diff(local.lines(), disk.lines())` rendered as inline highlighted spans (add/remove/
unchanged) is sufficient — no need for a full unified-diff/hunk UI. If requirement #1 keeps
whole-file disk content in the comparison for some paths, per-line diff still degrades gracefully
(no O(n²) risk at these content sizes — blocks/pages here are KB-scale, not MB-scale).

## 8. DomainError / state additions needed

`DomainError.kt` currently references `DiskConflict`/`PendingConflict` only incidentally (grep hit
was in doc comments describing related error paths, not a dedicated error case) — no
`DomainError` case exists for "page has unresolved pending conflict." Given `pendingConflicts:
Map<String, PendingConflict>` in `AppState` already tracks this at the UI-state layer (not
repository layer), the natural, minimal-diff approach is to add a derived-state helper on
`AppState` (e.g. `fun hasPendingConflict(filePath: String): Boolean`) rather than introducing a new
`DomainError` variant — this stays a UI-state concern, consistent with how `pendingConflicts` was
already modeled, and avoids threading a new error type through repository/Either boundaries where
it doesn't belong.

## 9. Summary of grounded recommendations for planning phase

- **Granularity fix**: re-parse incoming disk content with the existing outliner pipeline and
  extract the matching block by UUID (reuses `GraphLoader`'s existing `existingBlocks.associate`
  pattern) instead of comparing block vs. whole file.
- **Truncation/expansion**: add a small `ExpandableText` composable following the
  `ReferencesPanel.kt`/`PerformanceDashboard.kt` chevron-toggle pattern; pair with the already-
  declared `kotlin-multiplatform-diff` library for an inline line-diff view instead of two flat
  truncated blobs — no new dependency required.
- **Manual-resolve help**: add inline explanatory text (a `Text` composed under the marker
  preview, or a `TooltipBox` per the `AnnotationToolbar.kt` pattern) explaining `<<<<<<<`/`=======`/
  `>>>>>>>` before the user commits to that path; also flag the disk-side truncation bug in
  `manualResolve()` (L1589-1590 heuristic) as a related fix.
- **Persistent indicator**: extend `Sidebar.kt`'s per-page-item rendering (next to the existing
  favorite-star slot) with a small amber warning badge when `appState.pendingConflicts` contains
  that page's `filePath`, styled like `SyncStatusBadge`'s `ConflictPending` case
  (`Icons.Default.Warning`, `Color(0xFFF59E0B)`). No new `DomainError` needed — add a derived
  boolean/lookup on `AppState` instead.
