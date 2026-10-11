# Stack Research: app-owned-storage-clone

Phase 2 (research only — no code changes made). All line numbers verified against the
working tree at commit `ffea987773` (2026-09-12).

## 1. Android SAF APIs — current usage and API levels

- `androidApp/build.gradle.kts:37,41-42` / `kmp/build.gradle.kts:1431,1435`: `compileSdk = 36`,
  `minSdk = 26`, `targetSdk = 36`. minSdk 26 already covers everything SAF needs (SAF has
  existed since API 21/24); no floor change required for this feature.
- Dependency: `androidx.documentfile:documentfile:1.0.1` (`kmp/build.gradle.kts:260`). This is
  the **last released version** of `androidx.documentfile` — it has not moved past 1.0.1 in
  years and there is nothing newer to upgrade to.
- The tree-pick flow is split across two files:
  - `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt:128-170` —
    `registerForActivityResult(ActivityResultContracts.OpenDocumentTree())`, then
    `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or
    FLAG_GRANT_WRITE_URI_PERMISSION)`. This is the modern `ActivityResultContracts` pattern
    (not the deprecated `startActivityForResult`/`onActivityResult`), so no migration needed
    there.
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt:634-691` —
    `pickDirectory()` is a no-op stub (`actual override fun pickDirectory(): String? = null`);
    all real work happens in `pickDirectoryAsync()`, which awaits the callback registered via
    `initSaveFilePicker`/`onPickDirectory`, then re-reads the persisted tree URI from
    `SharedPreferences` (`PREFS_NAME`/`KEY_SAF_TREE_URI`) rather than reconstructing it from the
    `saf://`-encoded string — the comment at lines 653-662 documents why:
    `Uri.decode()` strips percent-encoding, so re-parsing the encoded string produces a URI
    whose `toString()` no longer `.equals()` the one Android actually persisted permission for.
    **Any new "app storage vs. SAF" picker must preserve this exact re-read-from-prefs step**
    if it reuses `pickDirectoryAsync()`, or it will silently fail `isSafPermissionValid`.
  - `DocumentFile.fromTreeUri` / `DocumentFile.fromSingleUri` calls throughout
    `PlatformFileSystem.kt` (lines 62, 408, 454, 547, 908, 935, 941) are the read/stat/list
    surface built on top of the granted tree.
- No use of the older `ACTION_OPEN_DOCUMENT_TREE` raw `Intent` API directly — it's fully wrapped
  by the `ActivityResultContracts.OpenDocumentTree()` contract, which is the currently
  recommended approach (no `startActivityForResult` deprecation exposure).
- **App-owned storage is already used internally**, just never exposed as a picker choice:
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt:209` —
    `context.filesDir.absolutePath` is the DB directory unconditionally, for every graph,
    regardless of storage backend.
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt:23` — read-only
    shadow markdown cache, also under `filesDir`.
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt:51` —
    `File(context.filesDir, "graphs/$shadowKey/gitshadow")`, a full JGit working tree. This is
    the concrete promotion target named in the requirements ("promoting the shadow worktree
    from cache to primary storage") — the directory already exists at the right location; the
    feature work is making it the *source of truth* instead of a write-back mirror of the SAF
    folder.

## 2. JGit — version and relocate-safety

- Version: `org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r`, applied identically in
  `jvmMain`, `androidMain`, and the shared `commonMain`-adjacent block (`kmp/build.gradle.kts`
  lines 145, 174-175, 327-331). This is a **current release** — JGit 7.3.0 (June 2026) is on
  the modern 7.x line (JGit moved to CalVer-ish major bumps tracking Eclipse platform releases;
  7.x requires Java 11+, hence the desugaring note below). No newer major version to chase.
  - `androidMain` additionally pulls `org.eclipse.jgit.ssh.jsch:7.3.0...` for SSH
    (`kmp/build.gradle.kts:331`), `jvmMain` pulls `org.eclipse.jgit.ssh.apache` instead
    (line 175) — a platform-specific SSH transport split already exists; not something this
    feature needs to touch.
  - `kmp/build.gradle.kts:1203,1440`: Android core-library desugaring is enabled specifically
    because JGit needs `java.time`/`java.util.stream` on API < 26 — but minSdk is already 26
    (`java.time` is natively available at 26), so this is legacy belt-and-suspenders, not a
    blocker.
- **Repository lifecycle pattern already used** (`AndroidGitRepository.kt`): every git
  operation opens a fresh `Git` handle scoped to a `.use { }` block —
  `private suspend fun openGit(...): Git` (line 695) → `Git.open(File(resolveForJGit(...)))`
  (line 702), called from ~10 call sites (lines 139, 177, 193, 231, 256, 272, 287, 455, 474,
  498, 552, 610, 624), each wrapped in `.use { git -> ... }`. **No long-lived `Repository` or
  `Git` object is held across calls** — this is the load-bearing fact for relocation safety:
  JGit's `Repository` holds native file handles/locks only for the duration of one `.use{}`
  block, so moving the `.git` working tree (e.g. `GitShadowWorktree`'s `worktreeRoot`, or a
  user's SAF-backed repo root) is safe as long as it happens when no `openGit(...)` call is
  in-flight — there's no persistent JGit-held lock to release first. A relocate/link flow
  should serialize through whatever mutex already guards concurrent git ops (see
  `GitWorktreeLocks` at `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/
  GitWorktreeLocks.kt`, referenced from `GitShadowWorktree.kt:9`) rather than inventing a new one.
- JGit itself has no built-in "move repository" API — moving a `.git` dir is a plain filesystem
  move plus reopening `Git.open()` at the new path; JGit does not cache absolute paths beyond
  the `Repository` instance's lifetime, so no config rewrite is needed after a move (unlike,
  say, git submodules or worktrees with recorded absolute paths — this repo does not use
  `git worktree add`, it uses its own from-scratch `GitShadowWorktree` mirror, so that JGit
  gotcha doesn't apply).
- A comment in `GitShadowWorktree.kt:22,42` references "ADR-018" for the worktree-vs-cache
  design rationale, but **no `docs/adr/ADR-018-*.md` file exists in the repo** (`docs/adr/`
  only has ADR-015, 016, 017, 019) — this looks like a numbering gap/lost doc, not something
  to rely on; treat it as UNVERIFIED/missing context rather than a citable source.

## 3. Web File System Access API + OPFS

- Browser support as of today (2026-09-12), confirmed via web search:
  - Chrome/Edge/Opera (Chromium): full support for `showDirectoryPicker()`,
    `FileSystemDirectoryHandle`, `queryPermission`/`requestPermission` — has been since Chrome
    86 (2020) [MDN](https://developer.mozilla.org/en-US/docs/Web/API/Window/showDirectoryPicker), [Chrome for Developers](https://developer.chrome.com/docs/capabilities/web-apis/file-system-access).
  - Firefox: does **not** support the local-disk picker methods (`showDirectoryPicker`,
    `showOpenFilePicker`, `showSaveFilePicker`) in any version — ships OPFS
    (`navigator.storage.getDirectory()`) only.
  - Safari (macOS/iPadOS/iOS): does **not** support `showDirectoryPicker`/`showOpenFilePicker`/
    `showSaveFilePicker`; Safari 15.2+ supports OPFS only.
  - Net effect: this is a **two-tier browser world**, not a graceful-degradation edge case —
    roughly half the browser market (Firefox + all Safari) can only ever get OPFS/app-owned
    storage; the "unified location picker" must treat app-owned storage as a fully first-class,
    always-available option on web, not a fallback shown only when the native picker is
    missing (which is what the current `AddGraphDialog` fallback-only framing does, per the
    requirements doc's baseline section). Sources: [MDN File System API](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API), [caniuse](https://caniuse.com/native-filesystem-api), [testmuai support table](https://www.testmuai.com/learning-hub/file-system-access-api-browser-support/).
- Existing Kotlin/Wasm interop (no new library needed — this repo hand-rolls minimal `js()`/
  `@JsFun` externals rather than pulling a wrapper dependency):
  - `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt:6` —
    `internal fun showDirectoryPickerSupported(): Boolean =
    js("typeof window.showDirectoryPicker === 'function'")`.
  - Same file, line 16 — `internal fun showDirectoryPickerPromise(): kotlin.js.Promise<JsAny> =
    js("window.showDirectoryPicker()")`.
  - `PlatformFileSystem.kt` (wasmJsMain) consumes these behind
    `supportsNativeDirectoryPicker`/`supportsHostDirectoryLink` (lines 617-618) and
    `requestDirectoryPickerNow()`/`pickDirectoryAsync()` (lines 624-691) — this is the
    equivalent of the Android `pickDirectoryAsync()` contract, and per the class doc comment at
    lines 35-36, OPFS paths already look like `/stelekit/<graphId>/<repo-relative-path>`
    (line 479) — i.e., OPFS-as-default-storage plumbing is already fully wired, only the UI
    surfacing (git-clone wizard) is missing, matching the requirements doc's baseline claim.
  - `App.kt:1408-1412` documents a real constraint to carry into the new picker UI:
    `showDirectoryPicker()` must be invoked **synchronously within the same click's transient
    user activation** — `fileSystem.requestDirectoryPickerNow()` is called directly from the
    click handler, not after an `await`/suspend gap, or Chrome silently rejects the picker call.
    Any new unified picker's "browse to real folder" entry must preserve this call-site
    constraint.
- No `kotlinx.browser` wrapper libraries or third-party FSA/OPFS Kotlin bindings are used
  anywhere in the tree — this is 100% hand-written `js()`/external interop, consistent with the
  rest of `wasmJsMain`. Nothing to add to `build.gradle.kts` for this dimension.

## 4. Compose Multiplatform UI — picker component pattern

- Compose Multiplatform version: `id("org.jetbrains.compose") version "1.10.3"`
  (`settings.gradle.kts:17`), Kotlin `2.4.10` (`settings.gradle.kts:9-10`), `kotlin("plugin.compose")
  2.4.10`. Both are current lines as used elsewhere in this repo; no version research needed —
  this feature is pure UI composition on top of what's already pinned.
- **There is no existing generic `DirectoryPicker`/`LocationPicker` composable.** Grepping
  `kmp/src/commonMain/.../ui/` for those names returns nothing. What exists instead:
  - `GitSetupScreen.kt:862-935` — `WikiSubdirBrowserDialog`, a private composable scoped to one
    screen. This is the closest existing pattern and the best template to generalize: it
    maintains `segments: List<String>` (breadcrumb state), lists subdirectories via the
    platform-agnostic `FileSystem.listDirectories(path)` call (no SAF/OPFS-specific branching
    in the composable itself — the platform `actual` handles that), and re-queries on
    `LaunchedEffect(segments)` as the user drills in. Its own doc comment (lines 855-861)
    explicitly frames it as filling the gap because `repoRoot` "is frequently an opaque picker
    URI (Android SAF, wasm OPFS) that a user cannot meaningfully hand-type a path relative to" —
    i.e. the authors already recognized the cross-platform-opaque-path problem this feature
    must solve more generally.
  - `Step2RepoPath` (`GitSetupScreen.kt:742-853`) is the current git-clone wizard step this
    feature must modify — today it's a bare `OutlinedTextField` plus an optional trailing
    `IconButton` (`Icons.Default.FolderOpen`) wired to `onBrowseRepoRoot` (line 785-794), which
    the requirements doc confirms is unconditionally wired to `pickDirectoryAsync()` today, with
    no app-storage entry.
  - `AddGraphDialog` (referenced in requirements at `App.kt:2084`, not re-verified line-by-line
    here since Phase 2 scope is stack/pattern research, not a full audit) is the other existing
    surface with an app-storage-adjacent choice.
- **Recommendation for this feature (pattern, not code):** promote `WikiSubdirBrowserDialog`'s
  shape (breadcrumb list dialog over a platform-agnostic `FileSystem` abstraction) into a
  shared `commonMain` composable that adds one extra top-level list entry — "Use app-owned
  storage" — before the breadcrumb/folder list, reachable from all three surfaces (new-graph
  dialog, `Step2RepoPath`, and the new "move storage" action). No new Compose library or
  component dependency is needed; Material3's existing `AlertDialog`/`LazyColumn`/`ListItem`
  (already used throughout `ui/components/`) are sufficient, matching this repo's existing
  style of hand-built dialogs rather than a picker library.

## 5. SQLDelight / libsql — relocate-safety

- SQLDelight: `app.cash.sqldelight:{runtime,coroutines-extensions,async-extensions,
  sqlite-driver,android-driver,native-driver}:2.3.2` (`kmp/build.gradle.kts:100-102,171,250,381`)
  — current 2.x line, consistent across all platform source sets.
- Android does **not** use SQLDelight's stock `android-driver` at runtime by default — there's a
  custom libsql JNI driver path:
  - `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/libsql/AndroidLibsqlDriver.kt:23-41` —
    wraps a `LibsqlDriverCore` (shared with `JvmLibsqlDriver`) backed by a Rust `.so`
    (`native/libsql/Cargo.toml`: `libsql = { version = "0.9", features = ["core"] }`, embedded
    local mode, no Turso cloud sync), loaded via `System.loadLibrary`. Pool size defaults to 4
    on Android vs. 8 on desktop (line 20, memory-budget comment).
    `AndroidLibsqlDriver.create(context, graphId)` hardcodes the DB path to
    `"${context.filesDir.absolutePath}/stelekit-graph-$graphId.db"` (line 38) —
    **the DB file's location is not affected by which storage backend the user picks for
    markdown content at all**; it's always `filesDir`, unconditionally, on every graph. This is
    a significant simplification for relocate-safety: relocating a graph's *markdown* storage
    (SAF ↔ app-owned) never requires touching or reopening the SQLite/libsql DB file, since the
    DB was never in the relocatable location to begin with.
  - Whether `db.libsql.enabled` (a runtime feature flag, `DriverFactory.android.kt:138`) is on
    or off, the fallback system-SQLite driver (`app.cash.sqldelight:android-driver`) is also
    rooted at `filesDir` (line 209's `context.filesDir.absolutePath` is the shared base for
    both paths) — same conclusion either way.
- WAL/journal-file relocate concerns from this repo's own conventions
  (project `CLAUDE.md`'s "Coroutine dispatcher and database connection pool" section,
  `DriverFactory.android.kt:23-41`): `PRAGMA journal_mode=WAL`, `busy_timeout=5000`,
  `wal_autocheckpoint=1000`. These are only relevant if a future iteration ever considered
  moving the *SQLite DB file itself* between storage backends (e.g. "put the DB on the SAF
  folder too") — which is **out of scope** per the findings above, since the DB always lives in
  `filesDir` regardless of the chosen markdown backend. If that assumption is ever revisited,
  the existing repo convention already documents the right precaution: never move/copy a
  SQLite file while a WAL/`-shm` file sits beside it without a full checkpoint
  (`PRAGMA wal_checkpoint(TRUNCATE)`) and all connections closed first — but this feature's
  actual relocate surface (markdown files under SAF vs. `filesDir`/OPFS) never touches SQLite
  file bytes, only the `DatabaseWriteActor`/repository layer's *content*, so this whole class of
  risk is avoidable by design: **relocate/link operations should move markdown files only and
  leave the DB in place, re-deriving its content via the existing
  `GraphLoader.loadDirectory`/reconcile path from the new location** rather than attempting a
  live file-level DB move.

## Summary of concrete versions/deps relevant to this feature

| Component | Version | Source |
|---|---|---|
| `androidx.documentfile` | 1.0.1 (latest release) | `kmp/build.gradle.kts:260` |
| JGit | 7.3.0.202506031305-r | `kmp/build.gradle.kts:145,174-175,327-331` |
| Compose Multiplatform | 1.10.3 | `settings.gradle.kts:17` |
| Kotlin | 2.4.10 | `settings.gradle.kts:9-10` |
| AGP | 8.13.2 | `settings.gradle.kts:14-15` |
| SQLDelight | 2.3.2 | `kmp/build.gradle.kts:100-102` |
| libsql (Rust crate, JNI) | 0.9 (core feature only) | `native/libsql/Cargo.toml:22` |
| kotlinx-coroutines | 1.10.2 | `kmp/build.gradle.kts:94,152,169,249,346` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 | `androidApp/build.gradle.kts:37,41-42` |

**No new external dependencies are needed for this feature on either platform.** Everything
required — SAF (`DocumentFile`, `ActivityResultContracts.OpenDocumentTree`), JGit's existing
`Git.open()`/`.use{}` pattern, the FSA/OPFS `js()` interop, and Compose Material3 dialog/list
primitives — is already present and pinned at current-enough versions. The work is entirely
architectural/UI (a shared picker composable + relocate/link orchestration logic), not a
dependency-acquisition problem.
