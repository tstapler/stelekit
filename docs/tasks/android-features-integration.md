# Feature Plan: Android Platform Integration — Widget, Quick Settings Tile, Share Target

**Status**: Ready for implementation
**Created**: 2026-04-22
**ADRs**: ADR-001 (Application singleton), ADR-002 (Jetpack Glance), ADR-003 (shared CaptureActivity)
**Plan source**: `project_plans/android-features-integration/`

---

## 1. Epic Overview

### User Value

Android users of SteleKit have no path to capture a note without first navigating to and opening the full app. This epic adds three platform-native capture entry points: a home screen widget, a Quick Settings Tile, and a share target. Each entry point opens a lightweight translucent capture overlay (`CaptureActivity`) that auto-saves on dismiss and writes through the same actor-serialized pipeline as the main UI.

### Success Metrics

- User can open `CaptureActivity` from a home screen widget and save a note that appears in the active graph's journal page on next app open.
- User can open `CaptureActivity` from the Quick Settings Tile without leaving the current foreground app.
- SteleKit appears in the Android share sheet for `text/plain`, `text/html`, and `image/*` MIME types; received content is pre-filled into `CaptureActivity`.
- No crash or ANR when any entry point cold-starts the process (no `MainActivity` previously running).
- All three entry points degrade to a "No graph configured — open SteleKit" state on first launch.

### Scope

In scope:
- `SteleKitApplication` refactor (prerequisite for all three features)
- Glance capture widget (button-only; no live data display)
- Quick Settings Tile (API 33+)
- Share target for `text/plain`, `text/html`, `image/*`
- `CaptureActivity`: translucent overlay, keyboard-raised-immediately, auto-save on dismiss, "today's journal" default destination

Out of scope:
- Widget customization UI (choose target graph/page)
- Display widget (live data, last note preview)
- Wear OS
- iOS share extension

---

## 2. Architecture Decisions

Three ADRs govern this epic. Do not re-litigate these decisions during implementation.

**ADR-001** — `SteleKitApplication` promotes `GraphManager` to process scope. All new Android components access graph data via `(application as SteleKitApplication).graphManager`. File: `project_plans/android-features-integration/decisions/ADR-001-application-singleton-for-data-layer.md`.

**ADR-002** — Jetpack Glance 1.1.1 (`androidx.glance:glance-appwidget:1.1.1`) for the home screen widget. Widget is capture-only (no live data display) to avoid the 45–50 second Glance session lock. File: `project_plans/android-features-integration/decisions/ADR-002-jetpack-glance-for-widget.md`.

**ADR-003** — One shared `CaptureActivity` (translucent, `stateAlwaysVisible`, `singleTop`, `excludeFromRecents`) serves all three entry points. The widget button, tile tap, and share intent all route to this single Activity. File: `project_plans/android-features-integration/decisions/ADR-003-shared-capture-activity.md`.

---

## 3. Known Issues

### Bug 1 — Concurrency: Graph switch races widget write [SEVERITY: High]

**Description**: `GraphManager.switchGraph()` cancels the current graph's `CoroutineScope`. A `CaptureActivity` write in flight during a switch will receive `ClosedSendChannelException` from `DatabaseWriteActor`.

**Mitigation**:
- Wrap all `DatabaseWriteActor.execute { }` calls in a try/catch on `ClosedSendChannelException`.
- Surface `Result.failure` to the user as a visible error snackbar with a "Retry" action.
- Add an integration test: switch graph while a `CaptureActivity` save coroutine is suspended.

**Files likely affected**: `CaptureActivity.kt`, `DatabaseWriteActor.kt`

**Prevention**: Treat every write result as `Result<Unit>`; never assume success silently.

---

### Bug 2 — Security: `EXTRA_STREAM` URI permission revoked after Activity destroy [SEVERITY: High]

**Description**: The OS grants temporary read permission on a shared content URI only to `CaptureActivity`. If the URI is forwarded to a coroutine that outlives `CaptureActivity.onCreate`, or to a `WorkManager` worker, the permission is revoked and a `SecurityException` is thrown.

**Mitigation**:
- Copy `EXTRA_STREAM` content to `cacheDir` synchronously in `CaptureActivity.onCreate`, before launching any coroutine.
- Add a unit test with a mocked content resolver verifying the copy happens before any `launch { }` call.

**Files likely affected**: `CaptureActivity.kt`

**Prevention**: Rule from ADR-003 consequences: synchronous copy is mandatory, never optional.

---

### Bug 3 — Null safety: Share intent `EXTRA_TEXT` is null from many real senders [SEVERITY: High]

**Description**: A large fraction of real-world share sources (browsers, social apps) populate `clipData` instead of `EXTRA_TEXT`. Accessing `intent.getStringExtra(Intent.EXTRA_TEXT)` first and not null-guarding each field produces a blank capture with no pre-fill and no error.

**Mitigation**:
- Read extras in the mandated order: `clipData?.getItemAt(0)?.coerceToText(context)` → `EXTRA_TEXT` → `EXTRA_SUBJECT` → empty string.
- Null-guard every field; never chain without a null check.
- Unit tests for: null `EXTRA_TEXT` only, `EXTRA_SUBJECT` only, `clipData` only, all null.

**Files likely affected**: `CaptureActivity.kt`

---

### Bug 4 — Glance: Mutable state in `GlanceAppWidget` class fields [SEVERITY: High]

**Description**: `GlanceAppWidget` instances are recreated by the framework. Any mutable field (e.g., a counter, a flag, a cached value) is reset on recreation without warning, producing silent state loss.

**Mitigation**:
- Never declare `var` fields in `CaptureWidget`. All state (e.g., whether the no-graph placeholder is shown) must go through `GlanceStateDefinition` / DataStore.
- Use `MultiProcessDataStoreFactory` if the DataStore file is ever read by both the widget and the main app process.
- Add a code review checklist item: "Does `CaptureWidget` have any `var` fields?" — answer must always be no.

**Files likely affected**: `CaptureWidget.kt`

---

### Bug 5 — TileService: Coroutine scope not cancelled in `onStopListening` [SEVERITY: High]

**Description**: `TileService` is bound and unbound by the OS. If a coroutine launched inside `onStartListening` or `onClick` references `this` (the tile) after `onStopListening`, the `updateTile()` call throws `IllegalStateException: TileService not listening`.

**Mitigation**:
- Declare `private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)` in `CaptureTileService`.
- In `onStopListening`, call `tileScope.coroutineContext[Job]?.cancelChildren()`.
- Never use `lifecycleScope` or `GlobalScope` in any `TileService`.

**Files likely affected**: `CaptureTileService.kt`

---

### Bug 6 — API compatibility: `startActivityAndCollapse(Intent)` removed in API 34 [SEVERITY: Medium]

**Description**: The `TileService.startActivityAndCollapse(Intent)` overload is deprecated in API 33 and removed in API 34. Using it causes a `NoSuchMethodError` at runtime on API 34+ devices.

**Mitigation**:
- Always use `startActivityAndCollapse(PendingIntent)` — available on all supported API levels via compat.
- Add a lint check or static analysis rule: flag any call to the `Intent` overload.

**Files likely affected**: `CaptureTileService.kt`

---

### Bug 7 — `AppWidgetProvider`: `GlobalScope` in `onUpdate` causes process-level scope leak [SEVERITY: Medium]

**Description**: `onUpdate` is called on the main thread with a short deadline. Using `GlobalScope.launch { }` to defer work creates a coroutine with no cancellation path; if the process is killed mid-write, the coroutine is abandoned silently.

**Mitigation**:
- Call `goAsync()` in `onUpdate` to get a `BroadcastReceiver.PendingResult`.
- Launch in `(application as SteleKitApplication).appScope` (a `CoroutineScope` owned by the Application).
- Call `pendingResult.finish()` in the coroutine's `finally` block.

**Files likely affected**: `CaptureWidgetReceiver.kt`, `SteleKitApplication.kt`

---

### Bug 8 — Data integrity: `GraphWriter.savePage()` not called after actor write [SEVERITY: High]

**Description**: `DatabaseWriteActor` writes to SQLite. The Markdown file (the source of truth for disk sync, external editors, and file-based backup) is only updated by `GraphWriter.savePage()`. Calling only the actor leaves the `.md` file stale.

**Mitigation**:
- After every `actor.saveBlock(...)` or `actor.savePage(...)` call in `CaptureActivity`, also call `GraphWriter.savePage(page)`.
- Add an integration test: capture a note via `CaptureActivity`, then verify the `.md` file on disk contains the new block content.

**Files likely affected**: `CaptureActivity.kt`

---

### Bug 9 — Application init: `SteleKitApplication.onCreate` crash leaves no Android component startable [SEVERITY: Medium]

**Description**: If `GraphManager` construction throws (e.g., corrupt `PlatformSettings` JSON, keystore failure), the application process cannot start. Every Android component — `MainActivity`, tile, widget receiver — will fail to bind.

**Mitigation**:
- Wrap the entire initialization block in `Application.onCreate` with `try/catch(Exception)`.
- On catch, set `graphManager` to a safe null/no-op state; log the error.
- All components must check `graphManager == null` before any data access and show a recovery UI.

**Files likely affected**: `SteleKitApplication.kt`

---

### Bug 10 — Glance version: Protobuf CVE in 1.1.0 [SEVERITY: Medium]

**Description**: Glance 1.1.0 contains a protobuf security vulnerability patched in 1.1.1. Using 1.1.0 leaves the app exposed.

**Mitigation**:
- Declare `androidx.glance:glance-appwidget:1.1.1` (not 1.1.0) in `build.gradle.kts`.
- Reject any PR that downgrades this version.

**Files likely affected**: `kmp/build.gradle.kts`

---

## 4. Dependency Visualization

```
PREREQUISITE
  [Story 0: SteleKitApplication refactor]
         |
         +------------------------------------------+
         |                                          |
         v                                          v
  [Story 1: Share Target]              (no data dependency on Story 1)
  Creates CaptureActivity
         |
         v
  [Story 2: Quick Settings Tile]
  Consumes CaptureActivity PendingIntent
         |
         v
  [Story 3: Glance Widget]
  Consumes CaptureActivity via actionStartActivity
  Highest risk — validated last
```

Story 0 must be merged before any of Stories 1–3 begin. Story 1 must be merged before Story 2 because the tile's `PendingIntent` must point to a declared `CaptureActivity`. Story 2 and Story 3 are independent of each other but Story 3 is highest risk and goes last.

---

## 5. Implementation Plan

### Story 0: SteleKitApplication Refactor (Prerequisite)

**Goal**: Promote `GraphManager` and its dependencies to process scope so that `CaptureActivity`, `CaptureTileService`, and `CaptureWidgetReceiver` can all access graph data when cold-started without `MainActivity`.

**Integration checkpoint**: After this story, run the existing test suite (`./gradlew ciCheck`). `MainActivity` behavior must be identical. No new crashes on cold start.

---

#### Task 0.1: Create `SteleKitApplication` [3h]

**Objective**: Create the `Application` subclass that owns `GraphManager`, `SteleKitContext`, `DriverFactory`, `PlatformFileSystem`, and `appScope` for the entire process lifetime.

**Context boundary**:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt` (new file)
- `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.android.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`

**Prerequisites**: None.

**Implementation approach**:
1. Create `SteleKitApplication : Application` in `androidApp/src/main/kotlin/dev/stapler/stelekit/`.
2. Declare `val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` as a process-scoped coroutine scope (used by `AppWidgetProvider.onUpdate` for `goAsync()` patterns).
3. In `onCreate`, wrap the entire body in `try { } catch (e: Exception) { }`. On success, call `SteleKitContext.init(this)`, `DriverFactory.setContext(this)`, and construct `GraphManager`. On failure, set `graphManager = null` and log; do not rethrow.
4. Expose `val graphManager: GraphManager?` as a nullable property. All consumers must null-check before use.
5. Add `android:name=".SteleKitApplication"` to the `<application>` element in `AndroidManifest.xml`.
6. In `MainActivity.onCreate`, remove the `SteleKitContext.init(this)` and `DriverFactory.setContext(this)` calls (now owned by Application). Obtain `graphManager` from `(application as SteleKitApplication).graphManager` if needed; current `MainActivity` does not use `graphManager` directly, so this may be a no-op removal only.

**Validation strategy**:
- Existing `./gradlew ciCheck` must pass without change.
- Add a Robolectric test in `androidUnitTest`: `SteleKitApplication.onCreate` does not throw, and `graphManager` is non-null after a clean init.
- Add a Robolectric test: `SteleKitApplication.onCreate` with a corrupted `PlatformSettings` (mock the keystore to throw) sets `graphManager = null` and does not rethrow.

**INVEST check**: Independent (no other story depends on internal details). Negotiable (null-graph behavior is explicit). Valuable (unblocks all three features). Estimable (3h). Small (one class, one manifest change). Testable (Robolectric).

---

#### Task 0.2: Add null-graph guard utilities [2h]

**Objective**: Provide a shared composable and string resource for the "No graph configured — open SteleKit" placeholder that all three entry points will display when `graphManager` is null or returns no active graph.

**Context boundary**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/NoGraphPlaceholder.kt` (new file)
- `androidApp/src/main/res/values/strings.xml`
- `kmp/src/androidMain/res/values/strings.xml` (if resources live in kmp module)

**Prerequisites**: Task 0.1 (know the `SteleKitApplication` API surface).

**Implementation approach**:
1. Locate the `strings.xml` for the Android app (check `androidApp/src/main/res/values/strings.xml`).
2. Add string resources: `no_graph_title = "No graph selected"`, `no_graph_body = "Open SteleKit to set up your graph"`, `no_graph_action = "Open SteleKit"`.
3. Create `NoGraphPlaceholderContent` — a simple Compose function (usable in Glance via the Glance composable subset) showing the title, body, and an `actionStartActivity` link to `MainActivity`. Also create a plain-Android-View XML layout `layout_no_graph.xml` for the tile's `showDialog()` fallback (if needed).
4. For Glance specifically, produce a `GlanceNoGraphContent` composable using only Glance-compatible composables (no `LazyColumn`, no `Scaffold`).

**Validation strategy**:
- Screenshot test (Roborazzi) for `NoGraphPlaceholderContent` on a standard-size widget area.
- Verify string resources compile without lint errors.

**INVEST check**: Small and testable. Valuable because it eliminates three separate no-graph placeholder implementations.

---

### Story 1: Share Target

**Goal**: SteleKit appears in the Android share sheet. Receiving a share opens `CaptureActivity` with content pre-filled. The user saves or dismisses; the note is written to today's journal page.

**Integration checkpoint**: After this story, share text/URL from Chrome to SteleKit on a physical device or emulator. Verify: (1) `CaptureActivity` appears as a translucent overlay, (2) shared text is pre-filled, (3) tapping Save writes a block to today's journal page, (4) the `.md` file on disk contains the new block.

---

#### Task 1.1: `CaptureActivity` — skeleton and theme [3h]

**Objective**: Create the `CaptureActivity` file, register it in the manifest with the correct launch mode and window flags, and apply the translucent theme so it renders as an overlay.

**Context boundary**:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` (new file)
- `androidApp/src/main/AndroidManifest.xml`
- `androidApp/src/main/res/values/styles.xml`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/theme/DynamicColorScheme.android.kt`

**Prerequisites**: Task 0.1 (Application class must exist before the Activity can cast to it).

**Implementation approach**:
1. Add `Theme.SteleKit.Translucent` to `styles.xml`: inherit from `Theme.AppCompat.Translucent` (or `Theme.MaterialComponents.Dialog`), set `windowBackground` to `@android:color/transparent`, `windowIsTranslucent` to `true`, `windowNoTitle` to `true`.
2. Create `CaptureActivity : ComponentActivity` with:
   - `windowSoftInputMode="stateAlwaysVisible|adjustResize"` in the manifest
   - `launchMode="singleTop"` in the manifest
   - `excludeFromRecents="true"` in the manifest
   - `theme="@style/Theme.SteleKit.Translucent"` in the manifest
3. In `onCreate`, call `enableEdgeToEdge()` and `setContent { CaptureScreen(...) }` with a minimal placeholder composable (real content in Task 1.2).
4. Register in `AndroidManifest.xml` under `<application>` — no intent filters yet (those come in Task 1.3).

**Validation strategy**:
- Robolectric test: `CaptureActivity` starts without crash.
- Manual: launch `CaptureActivity` via `adb shell am start -n dev.stapler.stelekit/.CaptureActivity` and verify translucent overlay appearance.

**INVEST check**: Independent of Tasks 1.2 and 1.3 at the file level; they layer on top.

---

#### Task 1.2: `CaptureActivity` — UI and write path [4h]

**Objective**: Implement the full capture UI (`TextField`, Save button, Choose Page chip, auto-save on dismiss) and wire the write path through `DatabaseWriteActor` + `GraphWriter`.

**Context boundary**:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/` (PageRepository, BlockRepository)

**Prerequisites**: Task 1.1 (Activity skeleton), Task 0.1 (Application provides `graphManager`).

**Implementation approach**:
1. In `CaptureActivity`, resolve `graphManager` from `(application as SteleKitApplication).graphManager`. If null, show `NoGraphPlaceholderContent` and a button to launch `MainActivity`; do not proceed further.
2. Implement `CaptureViewModel` (not `rememberCoroutineScope` — the ViewModel owns its scope per CLAUDE.md). The ViewModel holds `captureText: MutableStateFlow<String>` and exposes `save(): Result<Unit>`.
3. `save()` implementation:
   a. Get today's journal `Page` from `graphManager.getActiveRepositorySet()?.pageRepository` (or create it if it does not exist).
   b. Create a new `Block` with the captured text as content.
   c. Call `actor.saveBlock(block)` — handle `ClosedSendChannelException` (Bug 1 mitigation).
   d. Call `GraphWriter.savePage(page)` after the actor write (Bug 8 mitigation).
   e. Return `Result.success(Unit)` or `Result.failure(e)` on any exception.
4. `CaptureScreen` composable: full-width multi-line `TextField` auto-focused, primary "Save" button, secondary "Choose page" chip (post-MVP: opens a page picker dialog; for MVP, label shows "Today's Journal" and is non-interactive), error snackbar on `Result.failure`.
5. Auto-save on dismiss: override `onBackPressed` (API ≤ 33) and the predictive back gesture (API 34+ via `OnBackPressedCallback`) to call `save()` before `finish()`.

**Validation strategy**:
- Unit test (`androidUnitTest`): `CaptureViewModel.save()` with a mock `GraphManager` returns `Result.success` and calls `GraphWriter.savePage` exactly once.
- Unit test: `save()` when `DatabaseWriteActor` throws `ClosedSendChannelException` returns `Result.failure` and does not crash.
- Integration test (Robolectric): full flow — start `CaptureActivity`, enter text, click Save, assert block appears in the in-memory repository.

**INVEST check**: Large but scoped to one Activity + one ViewModel. Could split into 1.2a (UI only) and 1.2b (write path) if needed.

---

#### Task 1.3: Share intent filters and extras parsing [3h]

**Objective**: Register `CaptureActivity` as a share target for `text/plain`, `text/html`, and `image/*`. Parse incoming intent extras using the mandated null-safe order and pre-fill the capture text field. Copy `EXTRA_STREAM` to private storage synchronously.

**Context boundary**:
- `androidApp/src/main/AndroidManifest.xml`
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`
- `androidApp/src/main/res/xml/shortcuts.xml` (for Direct Share / `ShortcutManagerCompat`)

**Prerequisites**: Task 1.1 (Activity declared in manifest), Task 1.2 (text field exists to pre-fill).

**Implementation approach**:
1. Add to `AndroidManifest.xml` inside `CaptureActivity`'s `<activity>` element:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.SEND" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="text/plain" />
   </intent-filter>
   <intent-filter>
       <action android:name="android.intent.action.SEND" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="text/html" />
   </intent-filter>
   <intent-filter>
       <action android:name="android.intent.action.SEND" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="image/*" />
   </intent-filter>
   <intent-filter>
       <action android:name="android.intent.action.SEND_MULTIPLE" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="image/*" />
   </intent-filter>
   ```
2. Implement `parseShareIntent(intent: Intent): ShareContent` (a data class with `text: String`, `imageLocalPath: String?`):
   - Text: `intent.clipData?.getItemAt(0)?.coerceToText(context)?.toString() ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""`
   - Image (`EXTRA_STREAM`): call `copyStreamToPrivateStorage(uri)` synchronously before returning. Store the result path in `ShareContent.imageLocalPath`. Handle `SecurityException`.
3. `copyStreamToPrivateStorage(uri: Uri): String?`: open `contentResolver.openInputStream(uri)`, copy bytes to `File(cacheDir, "share_${System.currentTimeMillis()}.jpg")`, return absolute path. Wrap in try/catch; return null on failure.
4. Override `onNewIntent(intent: Intent)` to re-parse share extras when `CaptureActivity` is already in the back stack (required by `singleTop` launch mode).
5. Register `ShortcutManagerCompat` Direct Share shortcuts (today's journal + up to 4 recent pages) in `onResume`. Use `ShortcutInfoCompat` with `action = ACTION_SEND` and point to `CaptureActivity`.

**Validation strategy**:
- Unit tests (4): null `EXTRA_TEXT`, `EXTRA_SUBJECT`-only, `clipData`-only, all-null — verify `parseShareIntent` returns the correct `text` and never throws.
- Unit test: `EXTRA_STREAM` with a readable URI copies bytes to `cacheDir` before function returns.
- Unit test: `EXTRA_STREAM` with a `SecurityException` on `openInputStream` returns `imageLocalPath = null` and does not crash.

**INVEST check**: Independently testable (no device required for unit tests). The `ShortcutManagerCompat` step is the only non-unit-testable part.

---

### Story 2: Quick Settings Tile

**Goal**: SteleKit tile appears in the Quick Settings panel. Tapping opens `CaptureActivity` over the current foreground app without dismissing the notification shade manually.

**Integration checkpoint**: After this story, add the tile to Quick Settings on a physical device or emulator running API 33+. Verify: (1) tile shows app icon and label, (2) tapping tile opens `CaptureActivity` as an overlay, (3) saving returns focus to the previous app, (4) tile does not crash on API < 33 (it simply does not appear).

---

#### Task 2.1: `CaptureTileService` [3h]

**Objective**: Implement the `TileService` subclass that shows the SteleKit tile, handles `onClick` by starting `CaptureActivity` via `startActivityAndCollapse(PendingIntent)`, and manages its coroutine scope correctly.

**Context boundary**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/tile/CaptureTileService.kt` (new file)
- `androidApp/src/main/AndroidManifest.xml`
- `androidApp/src/main/res/drawable/ic_tile_capture.xml` (vector drawable, new file)

**Prerequisites**: Task 1.1 (`CaptureActivity` declared in manifest, needed for `PendingIntent` target), Task 0.1 (Application class).

**Implementation approach**:
1. Annotate class: `@RequiresApi(Build.VERSION_CODES.TIRAMISU)` (API 33 = Tiles API minimum for `startActivityAndCollapse(PendingIntent)`).
2. Declare `private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)`.
3. `onStopListening`: call `tileScope.coroutineContext[Job]?.cancelChildren()` (Bug 5 mitigation).
4. `onStartListening`: update tile state to `Tile.STATE_ACTIVE`. Set tile label and icon from resources. Call `updateTile()` inside a `tileScope.launch { }` block.
5. `onClick`: build `PendingIntent` pointing to `CaptureActivity` with `FLAG_IMMUTABLE`. Call `startActivityAndCollapse(pendingIntent)` — the `PendingIntent` overload only (Bug 6 mitigation). If `graphManager` is null, launch `MainActivity` instead.
6. Register in `AndroidManifest.xml`:
   ```xml
   <service
       android:name=".tile.CaptureTileService"
       android:exported="true"
       android:label="@string/tile_label_capture"
       android:icon="@drawable/ic_tile_capture"
       android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
       <intent-filter>
           <action android:name="android.service.quicksettings.action.QS_TILE" />
       </intent-filter>
   </service>
   ```
7. Add tile label string: `tile_label_capture = "SteleKit Capture"`.
8. Create a minimal 24dp vector drawable `ic_tile_capture.xml` (pen/note icon).

**Validation strategy**:
- Unit test (Robolectric): `CaptureTileService.onClick` creates a `PendingIntent` pointing to `CaptureActivity` (not `MainActivity`).
- Unit test: `onStopListening` cancels all children of `tileScope` (verify via `Job.children.toList().isEmpty()`).
- Unit test: `onClick` when `graphManager` is null launches `MainActivity` instead.

**INVEST check**: Independent of Story 1's write path. Depends only on Task 1.1 (manifest entry) and Task 0.1.

---

#### Task 2.2: Tile discovery prompt [2h]

**Objective**: After a user saves their first note, prompt them to add the tile to Quick Settings using `TileService.requestAddTileService()` (API 33+ only, foreground-only).

**Context boundary**:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/tile/CaptureTileService.kt`
- `androidApp/src/main/res/values/strings.xml`

**Prerequisites**: Task 2.1 (tile service class must exist), Task 1.2 (save flow exists to hook into).

**Implementation approach**:
1. After a successful `save()` in `CaptureActivity`, check if this is the first successful save: read a `SharedPreferences` boolean `pref_tile_prompt_shown`.
2. If false and `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`: call `StatusBarManager.requestAddTileService(...)` (API 33+) from the foreground `CaptureActivity`. Set `pref_tile_prompt_shown = true`.
3. Wrap the call in `try/catch` — the OS may reject if the tile is already added or the prompt quota is exceeded.

**Validation strategy**:
- Unit test: prompt is shown at most once (second call after `pref_tile_prompt_shown = true` skips the API call).
- Unit test: on API < 33, no call is made (version guard works).

**INVEST check**: Small and safe to defer post-MVP if schedule is tight.

---

### Story 3: Glance Widget

**Goal**: A home screen widget appears in the widget picker. It shows a "Capture note" button (and at larger sizes, a labeled secondary action). Tapping the button opens `CaptureActivity`. No live data is displayed.

**Integration checkpoint**: After this story, add the widget to the home screen on a Pixel emulator and a Samsung One UI device (or emulator). Verify: (1) widget renders at 1×1 and 2×1 sizes, (2) button tap opens `CaptureActivity`, (3) widget receiver does not crash on cold start, (4) no Glance session-lock errors in logcat.

---

#### Task 3.1: Gradle dependencies for Glance [1h]

**Objective**: Add Glance 1.1.1 and its test artifact to `kmp/build.gradle.kts` without breaking existing builds.

**Context boundary**:
- `kmp/build.gradle.kts`

**Prerequisites**: None (can run in parallel with Task 0.1 if needed, but logically belongs after the story sequence is established).

**Implementation approach**:
1. In the `androidMain` dependencies block, add:
   ```
   implementation("androidx.glance:glance-appwidget:1.1.1")
   implementation("androidx.glance:glance-material3:1.1.1")
   ```
2. In the `androidUnitTest` dependencies block, add:
   ```
   implementation("androidx.glance:glance-appwidget-testing:1.1.1")
   ```
3. Run `./gradlew :kmp:assembleDebug` to verify no dependency conflicts.
4. Confirm `glance-appwidget:1.1.1` is resolved (not downgraded to 1.1.0) via `./gradlew :kmp:dependencies --configuration androidMainImplementationClasspath | grep glance`.

**Validation strategy**:
- `./gradlew assembleDebug` passes.
- `./gradlew :kmp:dependencies` shows `glance-appwidget:1.1.1` (Bug 10 mitigation).

**INVEST check**: Tiny, independent, immediately verifiable.

---

#### Task 3.2: Widget descriptor and receiver [2h]

**Objective**: Create the `appwidget-provider` XML descriptor and the `GlanceAppWidgetReceiver` subclass. No UI yet — just the scaffolding needed for the launcher to recognize the widget.

**Context boundary**:
- `kmp/src/androidMain/res/xml/capture_widget_info.xml` (new file)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/widget/CaptureWidgetReceiver.kt` (new file)
- `androidApp/src/main/AndroidManifest.xml`

**Prerequisites**: Task 3.1 (Glance dependency available).

**Implementation approach**:
1. Create `capture_widget_info.xml`:
   ```xml
   <appwidget-provider
       android:minWidth="40dp"
       android:minHeight="40dp"
       android:targetCellWidth="2"
       android:targetCellHeight="1"
       android:updatePeriodMillis="0"
       android:resizeMode="horizontal|vertical"
       android:widgetCategory="home_screen"
       android:previewImage="@mipmap/ic_launcher" />
   ```
   Set `updatePeriodMillis="0"` — capture-only widgets have no polling (ADR-002).
2. Create `CaptureWidgetReceiver : GlanceAppWidgetReceiver`. Override `glanceAppWidget` to return an instance of `CaptureWidget` (created in Task 3.3). Override `onUpdate` with the `goAsync()` + `appScope` pattern (Bug 7 mitigation):
   ```kotlin
   override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
       val pendingResult = goAsync()
       (context.applicationContext as SteleKitApplication).appScope.launch {
           try { super.onUpdate(context, appWidgetManager, appWidgetIds) }
           finally { pendingResult.finish() }
       }
   }
   ```
3. Register in `AndroidManifest.xml`:
   ```xml
   <receiver
       android:name=".widget.CaptureWidgetReceiver"
       android:exported="true">
       <intent-filter>
           <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
       </intent-filter>
       <meta-data
           android:name="android.appwidget.provider"
           android:resource="@xml/capture_widget_info" />
   </receiver>
   ```

**Validation strategy**:
- Unit test: `CaptureWidgetReceiver.onUpdate` calls `pendingResult.finish()` in the `finally` block even if `super.onUpdate` throws.
- Manual: widget appears in the launcher widget picker after installing the APK.

**INVEST check**: Independent of Task 3.3 (the receiver delegates to the widget class; the widget class can be a no-op stub for this task).

---

#### Task 3.3: `CaptureWidget` Glance composable [4h]

**Objective**: Implement the `GlanceAppWidget` with `SizeMode.Responsive` breakpoints, no mutable class fields (Bug 4 mitigation), and a button that launches `CaptureActivity` via `actionStartActivity`.

**Context boundary**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/widget/CaptureWidget.kt` (new file)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/widget/CaptureWidgetReceiver.kt`
- `kmp/src/androidMain/res/values/strings.xml` (widget button label)

**Prerequisites**: Task 3.2 (receiver scaffolding), Task 1.1 (CaptureActivity declared in manifest for `actionStartActivity`).

**Implementation approach**:
1. Define size breakpoints:
   ```kotlin
   private val smallSize = DpSize(40.dp, 40.dp)    // 1×1 — icon button only
   private val mediumSize = DpSize(110.dp, 40.dp)  // 2×1 — icon + label
   ```
2. Declare `override val sizeMode = SizeMode.Responsive(setOf(smallSize, mediumSize))`.
3. `Content()` override: use `LocalSize.current` to select the layout. No `var` fields anywhere in the class (Bug 4 mitigation).
4. Small layout: a single `Button` (Glance) with a pen icon, `onClick = actionStartActivity<CaptureActivity>()`. If `graphManager` is null, `onClick = actionStartActivity<MainActivity>()` and show `GlanceNoGraphContent`.
5. Medium layout: same button plus a `Text("Capture note")` label.
6. Detecting null graph from Glance: read the current graph state from a `GlanceStateDefinition` backed by `DataStore`. A `DataStore` key `has_active_graph: Boolean` is written by `SteleKitApplication` on init and read by the widget. Use `MultiProcessDataStoreFactory` if the DataStore file is shared with the main app (Bug 4 + synthesis mitigation).
7. Do not declare any `var` fields. No `companion object` mutable state. No `GlobalScope`.

**Validation strategy**:
- `runGlanceAppWidgetUnitTest { }` test: at `smallSize`, widget renders one button.
- `runGlanceAppWidgetUnitTest { }` test: at `mediumSize`, widget renders button and label text.
- `runGlanceAppWidgetUnitTest { }` test: when `has_active_graph = false`, widget renders no-graph content.
- Roborazzi screenshot test for both size breakpoints.
- Manual test on Samsung One UI emulator: button tap opens `CaptureActivity`.

**INVEST check**: Valuable (completes the epic), Estimable (4h), Testable (Glance testing library). The Samsung OEM variance is the main risk — manual testing is required.

---

## 6. Context Preparation Guide

Before starting each story, load only these files into context. Do not load the full codebase.

### Before Story 0 (Application refactor)

- `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.android.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/db/DriverFactory.android.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `project_plans/android-features-integration/decisions/ADR-001-application-singleton-for-data-layer.md`

### Before Story 1 (Share target)

- `androidApp/src/main/kotlin/dev/stapler/stelekit/SteleKitApplication.kt` (output of Story 0)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`
- `project_plans/android-features-integration/decisions/ADR-003-shared-capture-activity.md`

### Before Story 2 (Quick Settings Tile)

- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` (output of Story 1)
- `androidApp/src/main/AndroidManifest.xml`
- Android TileService documentation reference (synthesis.md cites the Android Developers guide)

### Before Story 3 (Glance widget)

- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`
- `kmp/build.gradle.kts` (to verify Glance dependency added in Task 3.1)
- `project_plans/android-features-integration/decisions/ADR-002-jetpack-glance-for-widget.md`
- `project_plans/android-features-integration/research/synthesis.md` (Glance pitfalls section)

---

## 7. Task Summary Table

| Task | Story | Size | New Files | Modified Files |
|------|-------|------|-----------|----------------|
| 0.1 Create SteleKitApplication | 0 | 3h | `SteleKitApplication.kt` | `AndroidManifest.xml`, `MainActivity.kt` |
| 0.2 No-graph guard utilities | 0 | 2h | `NoGraphPlaceholder.kt` | `strings.xml` |
| 1.1 CaptureActivity skeleton + theme | 1 | 3h | `CaptureActivity.kt`, `styles.xml` | `AndroidManifest.xml` |
| 1.2 CaptureActivity UI + write path | 1 | 4h | `CaptureViewModel.kt` | `CaptureActivity.kt` |
| 1.3 Share intent filters + extras parsing | 1 | 3h | `shortcuts.xml` | `CaptureActivity.kt`, `AndroidManifest.xml` |
| 2.1 CaptureTileService | 2 | 3h | `CaptureTileService.kt`, `ic_tile_capture.xml` | `AndroidManifest.xml`, `strings.xml` |
| 2.2 Tile discovery prompt | 2 | 2h | — | `CaptureActivity.kt` |
| 3.1 Glance Gradle dependencies | 3 | 1h | — | `kmp/build.gradle.kts` |
| 3.2 Widget descriptor + receiver | 3 | 2h | `capture_widget_info.xml`, `CaptureWidgetReceiver.kt` | `AndroidManifest.xml` |
| 3.3 CaptureWidget Glance composable | 3 | 4h | `CaptureWidget.kt` | `CaptureWidgetReceiver.kt`, `strings.xml` |

**Total estimated effort**: 27h across 10 tasks.

All new Kotlin files in `kmp/` go under `kmp/src/androidMain/kotlin/dev/stapler/stelekit/`. All new Kotlin files in `androidApp/` go under `androidApp/src/main/kotlin/dev/stapler/stelekit/`. The `androidApp/src/main/` does not have a separate library module — app-level Android components (`MainActivity`, `SteleKitApplication`, `CaptureActivity`) live in `androidApp/`; shared platform code (`CaptureTileService`, `CaptureWidget`) lives in `kmp/src/androidMain/`.
