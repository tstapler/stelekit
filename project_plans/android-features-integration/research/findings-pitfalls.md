# Findings: Pitfalls — Known Failure Modes for Widgets, Tiles, and Share Targets

## Summary

Three Android platform features are planned for SteleKit: a home-screen quick-capture widget (Jetpack Glance), a Quick Settings Tile (TileService), and a Share target (ACTION_SEND). All three are Android-only; all three interact with Android's process, lifecycle, and permission models in ways that differ sharply from in-app Compose code.

The dominant failure pattern across all three features is the same: treating them as if they share the app process's lifecycle and memory. They do not. Each runs in an isolated process context (widgets and tiles are bound and unbound by the system; share intents arrive as cold starts or task-stack insertions). Code that is reliable in the main app — long-lived coroutines, in-memory caches, Compose State objects, direct SQLite calls — will silently fail or crash in these contexts.

The secondary failure pattern is data-contract brittleness: MIME type mismatches on share intents, `null` extras from third-party apps, stale RemoteViews blobs surviving a widget config change, and tile state not being refreshed after the device reboots.

**Prioritized mitigation checklist (top 5 things to get right from day one):**

1. **Never hold mutable state in `GlanceAppWidget`** — all widget state must be persisted (DataStore or GlanceStateDefinition), fetched in `provideGlance`, and never stored as class fields.
2. **Wrap every `onStartListening` coroutine in a scope that is cancelled in `onStopListening`** — the tile service is destroyed between these calls; any leak causes crashes or stale updates.
3. **Read share intent data defensively: check `clipData` first, then `EXTRA_TEXT`/`EXTRA_STREAM`, and null-guard every field** — senders from third-party apps are not required to populate extras in any particular way.
4. **Call `FLAG_GRANT_READ_URI_PERMISSION` when forwarding stream URIs received via share intents** — failing to do so causes a `SecurityException` when SteleKit tries to open the URI in a worker/service.
5. **Do all database and file I/O off the main thread in widget and tile update paths** — these code paths run in a `BroadcastReceiver`-derived context with a strict foreground timeout; an ANR here kills the widget update silently.

---

## Options Surveyed

Feature areas and their primary failure categories:

| Feature | Primary Failure Categories |
|---|---|
| Glance widget (GlanceAppWidget) | Update lifecycle, process death, Compose state model mismatch, SizeMode misconfiguration, RemoteViews size ceiling, update-rate quota, coroutine scope leaks |
| Quick Settings Tile (TileService) | onStartListening/onStopListening scope management, active vs. template tile confusion, STATE_UNAVAILABLE handling, reboot state reset, requestListeningState misuse, click handler on locked screen |
| Share target (ACTION_SEND) | MIME type filtering, null extras, clipData vs EXTRA_TEXT priority, URI permission grants, Activity launch mode conflicts, task stack mismanagement, ChooserTargetService deprecation |
| Cross-cutting | ANR from slow init, multi-process SQLite/WAL conflicts, DataStore cross-process access, cold-start latency showing blank UI |

---

## Trade-off Matrix

| Pitfall | Severity | Frequency | Mitigation Available | Detectability |
|---|---|---|---|---|
| Mutable state in GlanceAppWidget class | Critical | Very high | Yes — use DataStore/GlanceStateDefinition | Low — fails silently after process death |
| Missing `withContext(DB)` in widget update path | Critical | High | Yes — follow dispatcher matrix from CLAUDE.md | Medium — ANR logged but not surfaced to user |
| SizeMode.Exact causing excessive recomposition | High | High | Yes — use SizeMode.Responsive | Medium — visible as jank/blank widget |
| Coroutine scope not cancelled in onStopListening | High | Very high | Yes — use a Job cancelled in onStopListening | Low — leak is quiet until next bind cycle |
| Active tile: calling updateTile() outside listening window | High | High | Yes — use requestListeningState() correctly | Low — update silently dropped |
| Tile state not restored after reboot | Medium | High | Yes — update in onStartListening unconditionally | High — user-visible immediately |
| Share intent EXTRA_TEXT null from third-party app | Critical | Very high | Yes — null-guard + clipData fallback | High — crash is immediate |
| MIME type text/plain vs text/* filter mismatch | High | High | Yes — declare both in intent filter | High — app never appears in chooser |
| Missing FLAG_GRANT_READ_URI_PERMISSION on stream URI | Critical | Medium | Yes — explicit flag grant before opening | High — SecurityException logged |
| Wrong Activity launchMode on share entry point | Medium | Medium | Yes — use singleTop or handle onNewIntent | Medium — duplicate activities noticed by user |
| ChooserTargetService used instead of Sharing Shortcuts | Medium | Low (deprecated) | Yes — migrate to ShortcutManager push model | High — visible performance regression |
| Multi-process SQLite without WAL mode | High | Medium | Yes — enable WAL, or use DataStore | Low — data corruption is intermittent |
| Widget update quota exceeded (pre-Android 12) | Medium | Low | Yes — batch updates, use WorkManager | Medium — updates silently stopped |
| RemoteViews binder transaction too large | High | Low | Yes — use URI references, not embedded Bitmaps | Medium — TransactionTooLargeException in logs |

---

## Risk and Failure Modes

### 1. Jetpack Glance Widget

#### 1.1 GlanceAppWidget is stateless by contract — violations are silent

`GlanceAppWidget` is instantiated in the app's broadcast-receiver process context. The system can destroy this process at any time. Any Kotlin field or in-memory state stored on the `GlanceAppWidget` subclass survives only as long as the current process lives. After a device restart, a low-memory kill, or a widget reconfigure, the field is gone and the widget renders with default values.

The correct pattern is to use `GlanceStateDefinition` backed by DataStore (Glance provides `PreferencesGlanceStateDefinition` out of the box) and to fetch all rendering data inside `provideGlance()`. The widget content lambda must read from `currentState()` only.

```kotlin
// Wrong — field is lost on process death
class CaptureWidget : GlanceAppWidget() {
    private var lastNote: String = ""  // gone after kill
}

// Correct
class CaptureWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val lastNote = prefs[stringPreferencesKey("last_note")] ?: ""
            CaptureWidgetContent(lastNote)
        }
    }
}
```

#### 1.2 Glance Compose state is not regular Compose state

`GlanceAppWidget` uses the Compose runtime but its state model is entirely separate from the `remember`/`mutableStateOf` pattern used in UI Compose. `remember { }` inside `provideContent` does not persist across widget updates or recompositions triggered by `GlanceAppWidget.update()`. Developers who copy Compose patterns from the app's UI will produce widgets where user-visible state resets on every update.

All state must flow through the `GlanceStateDefinition` / DataStore path, not through Compose's in-memory snapshot state.

#### 1.3 onUpdate timing and the blank widget window

`AppWidgetProvider.onUpdate` is called by the system on a schedule defined by `updatePeriodMillis` in `appwidget-provider` XML. The minimum system-enforced interval is 30 minutes (1,800,000 ms). Developers commonly set shorter values expecting frequent updates; the system silently clamps to 30 minutes on Android 11 and below.

A separate problem: when the user first places the widget on the home screen, `onUpdate` is called immediately. If `provideGlance` performs I/O (DataStore read, database query) and this is slow, the widget renders blank or with a loading skeleton until the coroutine completes. This is visible and jarring. Mitigation: always have a non-empty default state in the `GlanceStateDefinition` so the first render is not blank.

For more frequent updates (capture confirmation, note count badge), the app must call `GlanceAppWidget.update(context, id)` or `GlanceAppWidget.updateAll(context)` explicitly. The `update()` call itself suspends and must be launched from a coroutine scope that outlives the `BroadcastReceiver` window — use `goAsync()` or dispatch to a `WorkManager` task.

#### 1.4 Coroutine scope in BroadcastReceiver context

`AppWidgetProvider` (and therefore `GlanceAppWidgetReceiver`) extends `BroadcastReceiver`. A `BroadcastReceiver.onReceive` callback has approximately 10 seconds before the system kills the process (the ANR threshold is 5 seconds on foreground broadcasts). Launching a coroutine with `GlobalScope.launch` inside `onReceive` is unreliable — the process may die before the coroutine finishes. The pattern endorsed by Glance is to use `goAsync()` and a dedicated scope tied to the work, or to enqueue a `WorkManager` `OneTimeWorkRequest` and return immediately.

SteleKit's database layer uses `PlatformDispatcher.DB` (mapped to `Dispatchers.IO` on Android). This is correct for widget update paths, but the `withContext(PlatformDispatcher.DB)` call must still be inside a properly scoped coroutine, not launched on `GlobalScope`.

#### 1.5 SizeMode gotchas

- **SizeMode.Single (default)**: Only one layout is produced regardless of widget size. This is the safest but least flexible option. Text may clip or images may overflow on small resize targets.
- **SizeMode.Exact**: `provideContent` is called fresh for every size change (including every home-screen scroll or live wallpaper repaint on some launchers). This causes excessive recomposition and RemoteViews regeneration, which is expensive. Avoid for widgets with non-trivial content.
- **SizeMode.Responsive**: Takes a set of `DpSize` breakpoints and produces one layout per breakpoint, sending all of them upfront. The launcher picks the best fit without calling back into the app. This is the preferred mode but requires pre-declaring all size variants; forgetting to include a size that the user resizes to will produce an unexpected layout.

A specific gotcha: on some launchers (particularly Samsung One UI), the reported widget size differs from what the Android docs specify. Layouts designed with exact pixel assumptions from `SizeMode.Exact` may look wrong on non-stock launchers. Use `SizeMode.Responsive` with generous size bins.

#### 1.6 RemoteViews binder transaction size limit

Glance serializes its Compose tree into a `RemoteViews` object, which is sent via Binder IPC to the launcher process. The Binder transaction buffer is 1 MB shared across all transactions. Embedding large bitmaps directly in RemoteViews frequently exceeds this limit, producing a `TransactionTooLargeException` that silently prevents the widget from rendering.

Mitigation: never embed `Bitmap` objects in widget content. Use `ImageProvider.fromUri(uri)` or `ImageProvider.fromResource(resId)` so that the launcher fetches the image independently. If a captured note's thumbnail must appear in the widget, write it to a file and pass the URI.

#### 1.7 Process death during provideGlance

If the app process is killed while `provideGlance` is suspended (e.g., during a DataStore read or DB query), the widget is left with its last-rendered RemoteViews. The system does not retry `provideGlance` until the next scheduled `onUpdate` or until the app calls `update()`. In a quick-capture widget, this means a capture written to disk may not appear in the widget until the next update cycle. Mitigation: enqueue a `WorkManager` task on every successful save that calls `updateAll()`. WorkManager is process-death resilient by design.

---

### 2. Quick Settings Tile (TileService)

#### 2.1 onStartListening / onStopListening are not symmetric with process lifecycle

`TileService` is bound by the system when Quick Settings is opened and unbound when it is closed. The `onStartListening` and `onStopListening` callbacks bracket the window during which the tile's UI is visible and the `Tile` object returned by `getQsTile()` is valid. Outside this window, calling `getQsTile()` returns `null` or a stale object depending on API level.

The single most common bug: launching a coroutine in `onStartListening` without cancelling it in `onStopListening`. The coroutine continues running after the panel closes, may call `getQsTile().updateTile()` on a tile that is no longer bound, and either no-ops silently or throws a `NullPointerException`.

```kotlin
// Wrong
override fun onStartListening() {
    scope.launch { observeNoteCount().collect { updateTile(it) } }
    // never cancelled — leaks after panel close
}

// Correct
private var listeningJob: Job? = null

override fun onStartListening() {
    listeningJob = lifecycleScope.launch {
        observeNoteCount().collect { count -> updateTile(count) }
    }
}

override fun onStopListening() {
    listeningJob?.cancel()
    listeningJob = null
}
```

Note that `TileService` does not implement `LifecycleOwner`. You must either use a `CoroutineScope` backed by `SupervisorJob()` that you cancel manually, or use the `androidx.lifecycle:lifecycle-service` artifact to get `lifecycleScope`.

#### 2.2 Active tile vs. template tile confusion

Android supports two modes declared in the manifest:

- **Template tile** (default): The system binds the service and calls `onStartListening` whenever Quick Settings is visible. The tile is updated reactively while the panel is open.
- **Active tile** (`android.service.quicksettings.ACTIVE_TILE` metadata set to `true`): The system does NOT call `onStartListening` on its own. The tile is only bound when the app explicitly calls `TileService.requestListeningState(context, componentName)`. After the app calls this, the system calls `onStartListening` once, allows one `updateTile()` call, then immediately calls `onStopListening`.

Active tile mode is the right choice for push-driven updates (e.g., "a note was just captured, update the tile badge"). Template mode is right for polling or live-observable state. Teams routinely mix them up — declaring active tile but writing template-style reactive code (which never fires because `onStartListening` is never called by the system), or using template mode but calling `requestListeningState()` (which works but is redundant and can cause race conditions).

For SteleKit's quick-capture tile, template mode is appropriate unless the tile shows a live note count that the main app needs to push. If a count badge is desired, active tile mode with a `requestListeningState()` call from a WorkManager post-save task is the correct pattern.

#### 2.3 STATE_UNAVAILABLE handling

`Tile.STATE_UNAVAILABLE` should be set when the tile cannot perform its action — for example, when no graph is loaded. Many teams never set this state, leaving the tile visually active even when tapping it would produce a no-op or error. The tile icon should reflect unavailability.

Also: the tile state is not persisted by the OS. After a reboot, every tile starts in `STATE_INACTIVE`. The tile must restore its correct state in `onStartListening` unconditionally. Failing to do so means the tile shows the wrong icon for the first Quick Settings open after every restart.

#### 2.4 Tile actions on locked screen

`TileService.isLocked()` returns true when the tile is tapped from the lock screen. If SteleKit's capture flow requires an unlocked device (e.g., to write to the database), the click handler must check `isLocked()` and either:

- Call `unlockAndRun(Runnable)` to prompt unlock first, or
- Show a toast explaining the action requires unlock.

Failing to check `isLocked()` means the tile appears to work (tap registers) but the action silently fails because the app's database or file system is not accessible while the device is locked (on Android 7+ with FBE, credential-encrypted storage is inaccessible before first unlock).

#### 2.5 API level constraints

- `TileService` itself: API 24 (Android 7.0) minimum.
- `requestListeningState()`: API 24.
- Active tile metadata: API 24.
- Tile subtitles (`setSubtitle`): API 29.
- `Tile.STATE_UNAVAILABLE` visual distinction: API 24 (but rendering differs across OEM skins).
- On API 33+, the system enforces stricter background process limits that affect how quickly `requestListeningState` binds the service. On older APIs, binding is near-instant; on API 33+, it may be deferred if the app is in a restricted standby bucket. [TRAINING_ONLY — verify with Android 13 release notes]

---

### 3. Share Target (ACTION_SEND)

#### 3.1 MIME type filter mismatch

The intent filter in `AndroidManifest.xml` must match the MIME types sent by the apps SteleKit expects to receive from. Common mistakes:

- Declaring only `text/plain` but expecting to receive URLs shared from browsers (Chrome sends `text/plain`; some apps send `text/*` or even `*/*`).
- Declaring `text/*` but not handling cases where `EXTRA_TEXT` is null and `EXTRA_STREAM` contains a URI instead.
- Declaring `*/*` to catch everything but then crashing when receiving an image or file the app has no way to process.

The safest configuration for a note-capture share target:

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/*" />
</intent-filter>
```

Declare both explicitly rather than relying on wildcard matching alone, because some system sharers match against the literal MIME type first.

#### 3.2 clipData vs EXTRA_TEXT priority — which one wins?

Android 10 (API 29) changed how `Intent.createChooser()` propagates data. The framework copies `ClipData` from the `ACTION_SEND` intent into the `ACTION_CHOOSER` intent but does NOT copy `EXTRA_STREAM`. Many real-world apps set only `EXTRA_TEXT`, only `ClipData`, or set them to different values.

The defensive reading order that handles all cases:

```kotlin
fun extractSharedText(intent: Intent): String? {
    // 1. Try EXTRA_TEXT (most common for text sharing)
    val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
    if (!extraText.isNullOrBlank()) return extraText

    // 2. Fall back to ClipData (Android 10+ standard path)
    val clip = intent.clipData
    if (clip != null && clip.itemCount > 0) {
        val item = clip.getItemAt(0)
        val coerced = item.coerceToText(context)
        if (!coerced.isNullOrBlank()) return coerced.toString()
    }

    // 3. Try EXTRA_SUBJECT as a last resort
    return intent.getStringExtra(Intent.EXTRA_SUBJECT)
}
```

Never assume `EXTRA_TEXT` is non-null. It is `null` in every case where the sender uses only `EXTRA_STREAM` (binary file sharing), and it is also `null` when the sender forgets to set it (frequent with third-party apps).

#### 3.3 Stream URIs and FLAG_GRANT_READ_URI_PERMISSION

When receiving a binary share (image, PDF, audio), the intent carries a `content://` URI in `EXTRA_STREAM`. This URI is owned by the sending app's `ContentProvider`. SteleKit receives read permission via `FLAG_GRANT_READ_URI_PERMISSION` on the intent automatically for `ACTION_SEND`.

The pitfall: if SteleKit needs to forward this URI to another component (a `Service`, a `WorkManager` worker, a secondary `Activity`), the permission grant does NOT automatically transfer. You must either:

- Read and copy the stream data in the original receiving Activity before passing it along, or
- Re-grant the permission explicitly: `context.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`.

Failing to do this causes a `SecurityException` in the worker/service with a message like "Permission Denial: reading ContentProvider ... requires the provider be exported". Additionally: the URI grant expires when the receiving Activity task is finished or the granting app is killed. Do not store a `content://` URI for later use without first copying the data to SteleKit's own storage.

#### 3.4 Activity launch mode conflicts

The share entry point Activity must use `android:launchMode="singleTop"` (or `singleTask` if SteleKit uses a single-activity architecture) and override `onNewIntent`. Without this, each share creates a new Activity instance on top of the existing SteleKit task, producing duplicate screens and a confusing back stack.

```xml
<activity
    android:name=".ShareActivity"
    android:launchMode="singleTop"
    android:exported="true">
```

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleShareIntent(intent)
}
```

If SteleKit uses Compose Navigation with a single `MainActivity`, the share intent should route through `MainActivity` (with `singleTask`) rather than a separate `ShareActivity`, to avoid navigation stack fragmentation.

#### 3.5 Sharing Shortcuts (Direct Share) and ChooserTargetService deprecation

`ChooserTargetService` (the pre-Android 10 Direct Share API) is deprecated and was the cause of the notoriously slow share sheet on Android 7–9. On Android 10+ the system uses `ShortcutManager`'s push model — apps publish `ShortcutInfo` objects in advance with `setCategories` matching a `share-target` declaration in `shortcuts.xml`.

The pitfall: teams either use the deprecated `ChooserTargetService` (works but slow and deprecated), skip Direct Share entirely (acceptable for an MVP), or implement `shortcuts.xml` but forget to call `ShortcutManagerCompat.pushDynamicShortcut()` to actually publish the shortcuts (the share target appears but with no direct contacts/destinations).

For SteleKit, the simplest correct approach is:
1. Declare the `share-target` in `shortcuts.xml` with `mimeType="text/plain"`.
2. Call `ShortcutManagerCompat.pushDynamicShortcut()` once at app startup with the capture destination shortcut.
3. Do not implement `ChooserTargetService`.

---

### 4. Cross-Cutting Concerns

#### 4.1 ANR from slow initialization in widget/tile process

Both the widget's `AppWidgetProvider.onReceive` and the tile's bound service lifecycle run on the main thread of the app process. Any blocking call on this thread — synchronous SharedPreferences reads, cold SQLite open, file I/O — produces an ANR if it exceeds 5 seconds (foreground) or 10 seconds (background broadcast).

SteleKit's `GraphManager` initialization path (which opens SQLite, reads markdown files, runs `OutlinerPipeline`) must never be triggered from a widget update or tile bind path. The widget and tile should read only from a pre-populated DataStore key (written by the main app after each save), not from the full database.

#### 4.2 Multi-process SQLite and WAL mode

If the widget or tile runs in a separate process (declared with `android:process` in the manifest), both processes may open the SQLite database simultaneously. SQLite's default journal mode (`DELETE`) does not support concurrent readers from multiple processes safely. WAL (Write-Ahead Logging) mode allows one writer and multiple concurrent readers across processes.

SteleKit currently uses SQLDelight with a `PooledJdbcSqliteDriver` on JVM. On Android, the SQLite driver must be configured with WAL mode enabled if any component outside the main process reads the database:

```kotlin
// In the Android driver factory
driver.execute(null, "PRAGMA journal_mode=WAL", 0)
```

However, if only the main process writes and widget/tile components only read a DataStore (not SQLite), this problem is avoided entirely. The recommended architecture: main app writes note data to SQLite AND writes a summary (last note text, count) to DataStore; widget and tile read only from DataStore. This eliminates the multi-process SQLite risk.

#### 4.3 DataStore cross-process access

`DataStore` (Preferences or Proto) is designed for single-process access. Using the same DataStore instance from multiple processes without coordination produces data corruption. The documented workaround is to use a separate DataStore file per process, with the main app writing to the shared file and the widget/tile reading from it in a read-only pattern (secondary process never writes).

This works in practice but is not guaranteed by the API contract. [TRAINING_ONLY — verify current DataStore multi-process guidance in 2025 docs]

#### 4.4 Widget update quota (pre-Android 12)

On Android 11 and below, there is an observed limit of approximately 30 `AppWidgetManager.updateAppWidget()` calls per 30-minute window per widget instance. Exceeding this quota causes subsequent updates to be dropped silently. The quota was relaxed in Android 12. [TRAINING_ONLY — verify exact quota numbers]

Mitigation: batch widget updates. Do not call `updateAll()` after every single block edit. Instead, debounce or use a WorkManager task that coalesces rapid saves into a single widget update.

#### 4.5 Cold start latency showing blank widget content

When the widget update triggers a cold app start (process was killed), the time between `onUpdate` being called and `provideGlance` completing its I/O can be 500ms–2s on mid-range devices. During this window the widget may show the last RemoteViews snapshot (which could be stale) or a blank frame.

Mitigation: maintain a DataStore key that is always written immediately on save, before the full database write completes. The widget reads this key first and renders immediately; the full database state is a background concern.

---

## Migration and Adoption Cost

| Feature | Estimated Integration Effort | Primary Cost Driver |
|---|---|---|
| Glance widget | 2–4 days (implementation) + ongoing | GlanceStateDefinition setup, DataStore integration, WorkManager update chain |
| TileService | 1–2 days | Lifecycle scope management, active vs. template decision |
| Share target | 1–2 days | Intent handling, launch mode, Sharing Shortcuts declaration |
| Cross-cutting DataStore layer | 1 day (shared between all three) | Schema definition, write-on-save integration |

The DataStore summary-write layer is a shared investment: once the main app writes `last_note_text` and `note_count` to DataStore on every save, the widget and tile can consume it for free without any SQLite dependency.

Migrating from no widget to a Glance widget carries meaningful ongoing maintenance cost: every Compose API used in the app UI must be checked for Glance compatibility — many standard Compose modifiers and components are not available in Glance and will fail to compile or behave unexpectedly at runtime.

---

## Operational Concerns

- **Widget update failures are silent** — the system does not surface widget update errors to the user. Implement a logging call in `provideGlance`'s catch block that writes to a DataStore debug key. This key can be read from a developer settings screen.
- **Tile service crashes are logged but not user-visible** — monitor `adb logcat` for `TileService` component crashes during development. Production: use Firebase Crashlytics with the tile service process in scope.
- **Share intent handling must be idempotent** — the user may share the same URL twice. The save path must handle duplicate detection gracefully (check for existing block with same content before inserting).
- **Battery optimization** — `TileService` bindings and `AppWidgetProvider` broadcasts count against battery stats. On aggressive OEM skins (Xiaomi MIUI, Huawei EMUI), background restrictions may kill the widget update WorkManager tasks. Document the "allow background activity" user step in onboarding if real-time widget updates are a key feature.
- **Widget configuration Activity** — if SteleKit adds a widget configuration screen (`AppWidgetProvider.configure`), the widget is NOT added to the home screen until the configuration Activity returns `RESULT_OK`. Returning `RESULT_CANCELED` (or the Activity crashing) removes the widget immediately. This is a confusing UX moment for users.

---

## Prior Art and Lessons Learned

- **Todoist, Notion, Bear** all implement home-screen widgets for quick capture. Todoist's widget update reliability is frequently cited in user reviews as a pain point on non-stock Android launchers (particularly Samsung and Huawei), confirming that the multi-launcher SizeMode and OEM restriction problems are real-world issues, not theoretical.
- **Signal and Telegram** implement both widgets and share targets. Their share target implementations use the `singleTask` launch mode with `onNewIntent` to avoid stack fragmentation, and both null-guard every extra field.
- The Android 10 share sheet slowness (pre-Sharing Shortcuts) was severe enough that Google published a blog post and sample app specifically to migrate teams off `ChooserTargetService`. The lesson: do not use any API that requires real-time system callbacks at share-sheet-open time.
- **Glance 1.0.0 release notes** (released 2023-10-04) document several breaking changes from alpha/beta, including changes to `GlanceStateDefinition` and `ActionCallback`. Projects upgrading from alpha Glance will encounter API mismatches. SteleKit is starting fresh, so this is not a migration concern — but it confirms that the Glance API stabilized late and community knowledge from pre-1.0 articles should be treated as potentially outdated.
- **Ian Lake's 2016 Quick Settings Tiles post** (Android Developers Medium) remains the canonical reference for template vs. active tile selection and is still accurate as of API 34.

---

## Open Questions

1. Does SteleKit plan to run the widget or tile in a separate process (`android:process`)? If yes, the SQLite multi-process WAL question becomes urgent. If no, it can be deferred.
2. Will the quick-capture widget write directly to the database, or will it write to a pending-captures DataStore queue that the main app drains on next open? The latter is significantly safer but requires a drain mechanism.
3. Should the share target be a separate lightweight `Activity` or routed through `MainActivity`? The answer affects the Compose Navigation architecture and launch mode choices.
4. Is there a minimum Android version floor beyond `minSdk`? `TileService` requires API 24; if the project already targets API 24+ this is a non-issue.
5. What happens to in-flight widget updates during a SteleKit schema migration (e.g., SQLDelight migration runner)? The widget's DataStore read path must be migration-agnostic — another argument for keeping widget data in DataStore rather than SQLite.

---

## Recommendation

Adopt a **DataStore-first widget/tile data layer**. The main app writes a small summary record (last note text, graph name, note count) to a `PreferencesDataStore` file on every successful save, in addition to the SQLite write. The widget and tile read exclusively from this DataStore file and never open SQLite. This one architectural decision eliminates:

- Multi-process SQLite contention
- GlanceStateDefinition complexity (DataStore IS the state)
- ANR risk from cold SQLite open in broadcast context
- Widget blank-flash on cold start (DataStore read is fast)

The remaining highest-priority items:

1. Use `SizeMode.Responsive` with 2–3 breakpoints (small / medium / large).
2. Drive all widget updates from `WorkManager` (not `GlobalScope`), enqueued by a unique name so rapid saves coalesce.
3. Implement share intent reading with the clipData → EXTRA_TEXT → EXTRA_SUBJECT fallback chain, with null guards on every field.
4. Tie all TileService coroutines to a scope cancelled in `onStopListening`.
5. Check `isLocked()` before performing any database write in the tile click handler.

---

## Pending Web Searches

The following searches were executed during research and informed this document. Additional searches that would further validate specific claims:

1. `"Jetpack Glance" "SizeMode.Responsive" launcher compatibility Samsung OneUI 2025` — to validate OEM-specific SizeMode rendering differences.
2. `Android DataStore multi-process widget tile 2025 site:developer.android.com` — to get the current official stance on cross-process DataStore access.
3. `TileService API 33 background restricted bucket requestListeningState latency` — to confirm the API 33 binding latency regression.
4. `AppWidgetManager updateAppWidget rate limit quota Android 11 12` — to confirm the exact quota numbers and Android version where it was relaxed.
5. `"FLAG_GRANT_READ_URI_PERMISSION" "WorkManager" share intent forwarding SecurityException` — to find canonical examples of the URI permission forwarding pattern.

---

*Sources consulted:*
- [Manage and update GlanceAppWidget — Android Developers](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)
- [Demystifying Jetpack Glance for app widgets — Android Developers / Medium](https://medium.com/androiddevelopers/demystifying-jetpack-glance-for-app-widgets-8fbc7041955c)
- [TileService API reference — Android Developers](https://developer.android.com/reference/android/service/quicksettings/TileService)
- [Create custom Quick Settings tiles — Android Developers](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- [Receive simple data from other apps — Android Developers](https://developer.android.com/training/sharing/receive)
- [ACTION_SEND, the Chooser, and ClipData — CommonsWare](https://commonsware.com/blog/2021/01/07/action_send-share-sheet-clipdata.html)
- [Provide Direct Share targets — Android Developers](https://developer.android.com/training/sharing/direct-share-targets)
- [Create an advanced widget — Android Developers](https://developer.android.com/develop/ui/views/appwidgets/advanced)
- [Quick Settings Tiles on Android 7.0 — Ian Lake / Android Developers Medium](https://medium.com/androiddevelopers/quick-settings-tiles-e3c22daf93a8)
