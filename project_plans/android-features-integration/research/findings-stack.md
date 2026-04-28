# Findings: Stack — Android Widgets, Tiles, and Share Targets

**Authored**: 2026-04-22
**Input**: `project_plans/android-features-integration/requirements.md`

---

## Summary

Three distinct Android subsystems are needed for this project. Each has a clear modern API:

| Feature | API | Min SDK | Gradle artifact |
|---|---|---|---|
| Home screen widget | Jetpack Glance (`GlanceAppWidget`) | API 23 | `androidx.glance:glance-appwidget:1.1.0` |
| Quick Settings Tile | `TileService` (AOSP framework) | API 24 (tile exists); API 33 (placement API) | No extra dep — framework class |
| Share target | `ACTION_SEND` intent filter + `ShortcutManagerCompat` direct share | API 23+ | `androidx.core:core-ktx` (already in project) |

The recommendation is: **use Jetpack Glance for the widget, the framework `TileService` for the tile, and `ACTION_SEND` intent filter + `ShortcutManagerCompat` for share**. All three integrate cleanly into `androidMain`; no KMP-shared code is required.

---

## Options Surveyed

### 1. Home Screen Widget

#### Option A: Jetpack Glance (`androidx.glance:glance-appwidget`)

Glance is Google's Compose-based wrapper over `RemoteViews`. The developer writes Compose-style code (`@Composable` functions using Glance's restricted composable set) and the framework translates it to `RemoteViews` at render time. Reached stable 1.1.0 in 2024; 1.1.1 is the latest patch; 1.2.0-rc01 is in release candidate as of early 2026.

Key types:
- `GlanceAppWidget` — subclass this; override `Content()` with Glance composables
- `GlanceAppWidgetReceiver` — replaces `AppWidgetProvider`; declare in manifest
- `GlanceStateDefinition` / `GlanceDataStore` — typed state via DataStore preferences
- `actionRunCallback<T>()` / `actionStartActivity()` — click handlers
- `SizeMode` — controls single/responsive/exact sizing modes

Gradle:
```kotlin
// androidMain dependencies
implementation("androidx.glance:glance-appwidget:1.1.0")
implementation("androidx.glance:glance-material3:1.1.0")  // Material3 theming

// androidUnitTest
testImplementation("androidx.glance:glance-appwidget-testing:1.1.0")
```

Testing: `runGlanceAppWidgetUnitTest { }` block + Robolectric for `LocalContext`. Dedicated `glance-appwidget-testing` artifact provides `GlanceNodeAssertionsProvider` and matcher DSL.

Minimum API: **23** (Glance 1.x; matches SteleKit's current `minSdk = 24` — no change needed).

#### Option B: Raw `RemoteViews` + `AppWidgetProvider`

The original API. Widget UI defined via XML layouts; updates via `RemoteViews` instances passed to `AppWidgetManager.updateAppWidget()`. Supported since API 1. No Compose. Full control over every pixel and update cycle. Significantly more boilerplate for interactive widgets (click handling, text input). No dedicated testing library; must inflate views manually in Robolectric.

Minimum API: **1**.

---

### 2. Quick Settings Tile

#### Option A: Framework `TileService` (android.service.quicksettings)

`TileService` is part of the Android framework since API 24. No additional library dependency. Extend `TileService`, override lifecycle callbacks: `onTileAdded()`, `onTileRemoved()`, `onStartListening()`, `onStopListening()`, `onClick()`.

State is updated via `getQsTile().state = Tile.STATE_ACTIVE / INACTIVE / UNAVAILABLE` then `updateTile()`.

**API 33 adds `requestAddTileService()`** — lets the app prompt the user to add the tile without navigating to tile settings. This is strictly opt-in; the tile works on API 24+ without it.

Manifest snippet:
```xml
<service
    android:name=".capture.CaptureQsTileService"
    android:label="@string/qs_tile_label"
    android:icon="@drawable/ic_qs_capture"
    android:exported="true"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
    <meta-data
        android:name="android.service.quicksettings.TOGGLEABLE_TILE"
        android:value="true" />
</service>
```

No Gradle dependency beyond `core-ktx` already present.

#### Option B: Wear OS `TileService` (androidx.wear.tiles)

Wrong target. `androidx.wear:wear-tiles` is for Wear OS watch faces. Do not use.

---

### 3. Share Target

#### Option A: `ACTION_SEND` intent filter + `ShortcutManagerCompat` direct share (recommended)

**Basic share receipt** — declare an `Activity` with `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters. Manifest snippet:

```xml
<activity
    android:name=".share.ShareTargetActivity"
    android:label="@string/share_target_label"
    android:exported="true"
    android:excludeFromRecents="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
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
</activity>
```

Reading the payload:
```kotlin
val text = intent.getStringExtra(Intent.EXTRA_TEXT)
val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)  // URL title from browser
val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)  // for image/*
```

**Direct share (named targets)** — `ShortcutManagerCompat.pushDynamicShortcut()` with `ShortcutInfoCompat` objects tagged with a share category. Requires a `<share-target>` in `shortcuts.xml`:

```xml
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <share-target android:targetClass="dev.stapler.stelekit.share.ShareTargetActivity">
        <data android:mimeType="text/plain" />
        <category android:name="dev.stapler.stelekit.SHARE_TARGET_CATEGORY" />
    </share-target>
</shortcuts>
```

No new Gradle dependency — `ShortcutManagerCompat` is part of `androidx.core:core-ktx` already in the project.

#### Option B: `ChooserTargetService` (deprecated)

Deprecated in API 29; removed from documentation. Do not use.

---

## Trade-off Matrix

| Axis | Jetpack Glance | Raw RemoteViews | Framework TileService | ACTION_SEND + ShortcutManagerCompat |
|---|---|---|---|---|
| **API maturity** | Stable (1.1.0, GA since 2024) | GA since API 1 | GA since API 24 | GA since API 10 |
| **Compose compatibility** | First-class Glance composables | None — XML layouts only | N/A | N/A — full Compose in receiving Activity |
| **Minimum API level** | API 23 (matches SteleKit minSdk=24) | API 1 | API 24 (tile); API 33 (placement prompt) | API 23+ (direct share requires API 29+) |
| **KMP isolation** | androidMain only | androidMain only | androidMain only | androidMain only |
| **Testing support** | `glance-appwidget-testing` + Robolectric | Manual inflation; no dedicated tooling | Limited Robolectric support | Standard Activity testing |
| **Boilerplate** | Low (composable functions) | High (XML layouts, RemoteViews) | Low (4 lifecycle callbacks) | Low (intent filter + one activity) |
| **Input (text capture)** | Supported via RemoteInputCompatManager (launcher-dependent) | Requires separate Activity | Dialog or start Activity | Full Activity keyboard input |

---

## Risk and Failure Modes

### Widget (Glance)

1. **Process isolation**: Widget receiver may be invoked without the app in the foreground. `GraphManager` will not be initialized unless promoted to `Application` scope. Route captures through a SharedPreferences/DataStore queue or `WorkManager`.

2. **Text input from widget**: `GlanceTextInput` / `RemoteInput` support varies by launcher. AOSP Pixel launcher supports it; Samsung One UI may not. Plan a fallback that opens the full app for capture.

3. **Layout rendering variance**: Glance translates composables to `RemoteViews` at runtime. Sizing, padding, and font scaling differ across launcher hosts. Real device testing is required.

4. **Glance state DataStore collision**: If the app's DataStore and the widget's DataStore share the same file, concurrent writes from different processes can corrupt preferences. Use a separate DataStore file name for widget state.

5. **`RemoteViews` size cap**: Android enforces a ~1MB transaction limit on `RemoteViews` IPC. Large layouts or embedded bitmaps silently fail to update.

### Quick Settings Tile

1. **API 33 placement API back-compat**: `requestAddTileService()` crashes on API < 33 without a version guard. Always guard: `if (Build.VERSION.SDK_INT >= 33) { ... }`.

2. **Background activity start restriction (API 29+)**: Starting a full Activity from `TileService.onClick()` is restricted. Use `startActivityAndCollapse(pendingIntent)` pointing to a lightweight transparent Activity.

3. **API 34 `startActivityAndCollapse` signature change**: On API 34+, `startActivityAndCollapse(Intent)` is deprecated in favor of `startActivityAndCollapse(PendingIntent)`. Use only the `PendingIntent` form.

4. **No persistent state between listening cycles**: Do not store query results in TileService instance fields — the service may be unbound and re-created at any time.

### Share Target

1. **`EXTRA_STREAM` URI permission lifetime**: Temporary read permission on a shared content URI is revoked when the receiving Activity is destroyed. Copy the stream to app-private storage synchronously in `onCreate` before any async handoff.

2. **MIME type mismatch**: Some apps send `text/plain` with an embedded URL while others send `application/*` or `*/*`. Test against Chrome, Firefox, and the system Files app.

3. **`onNewIntent` vs `onCreate`**: Use a separate lightweight `ShareTargetActivity` to avoid lifecycle conflicts with `MainActivity`.

4. **Back navigation**: With `excludeFromRecents="true"`, pressing back should `finish()` the share Activity cleanly, returning to the originating app.

---

## Migration and Adoption Cost

| Feature | Estimated effort | New files | New Gradle deps |
|---|---|---|---|
| Glance widget | 3–5 days | `CaptureWidget.kt`, `CaptureWidgetReceiver.kt`, `res/xml/capture_widget_info.xml` | `glance-appwidget:1.1.0`, `glance-material3:1.1.0`, `glance-appwidget-testing:1.1.0` |
| QS Tile | 1–2 days | `CaptureQsTileService.kt`, `CaptureDialogActivity.kt` (transparent) | None — framework class |
| Share target | 1–2 days | `ShareTargetActivity.kt`, `res/xml/shortcuts.xml` | None — uses existing `core-ktx` |

No existing KMP `commonMain` code needs to change. All new code lives in `kmp/src/androidMain/`.

**Glance version lock**: SteleKit uses Compose Multiplatform 1.7.3 (Jetpack Compose BOM ~1.7.x). Glance 1.1.0 is compatible with Compose 1.7.x. Upgrade Glance only when upgrading Compose Multiplatform.

---

## Operational Concerns

1. **Widget update battery drain**: Call `GlanceAppWidget.update()` only on explicit user action or graph write. Minimum `android:updatePeriodMillis` is 1800000ms (30 min) — the OS ignores shorter intervals.

2. **`WorkManager` for deferred writes**: If the widget captures while the graph is not loaded, enqueue a `CoroutineWorker` via WorkManager to write the pending capture when the app next initializes.

3. **Tile icon must be a vector drawable**: White/monochrome on transparent background. The existing app icon is not suitable; a dedicated capture-action icon is required.

4. **Share Activity theme**: `ShareTargetActivity` should use a translucent theme so the originating app is visible behind the capture UI.

5. **Permissions**: No new dangerous permissions required. `BIND_QUICK_SETTINGS_TILE` is a normal permission granted automatically.

---

## Prior Art and Lessons Learned

- **Obsidian Android**: Routes widget captures through a file-based queue rather than directly through the database, avoiding process isolation issues. [TRAINING_ONLY — verify]

- **Google Keep**: Uses Glance for its widget since 2023, including text input. Handles "app not running" by writing to SharedPreferences, read on next app launch. Simpler alternative to WorkManager for a solo developer.

- **Community lessons** (Jetpack Glance in production, 2024):
  - `LazyColumn` in Glance maps to `ListView` — all `RemoteViews` collection limitations apply.
  - `SizeMode.Responsive` requires testing on multiple launcher configurations.
  - Real device testing is mandatory; unit tests catch structural bugs but not rendering bugs.

---

## Open Questions

- [ ] **Text input widget support on target launchers** — if users are predominantly on Samsung One UI (inconsistent `RemoteInput` support), is a "tap to open app" capture flow acceptable for the widget MVP? Blocks: widget interaction model decision.
- [ ] **Graph selection in widget/tile** — for interim (pre-widget-customization-UI), write to most recently active graph or always first loaded? Blocks: widget state design.
- [ ] **Image handling in share target** — where should received images live (graph `assets/` vs. staging dir)? Blocks: `GraphWriter` integration.
- [ ] **Glance 1.2.x upgrade timing** — target 1.1.0 (stable) or wait for 1.2.0? Blocks: dependency selection.
- [ ] **`startActivityAndCollapse` API 34 migration** — use PendingIntent overload only (API 34+) or maintain dual-path for API 24–33? Blocks: tile implementation.

---

## Recommendation

**Use all three modern APIs:**

1. **Widget: Jetpack Glance 1.1.0** — stable, Compose-first, dedicated testing library, no `minSdk` change. Widget writes to a SharedPreferences/DataStore queue (not directly to `DatabaseWriteActor`) to handle process isolation. Use `SizeMode.Responsive` with two breakpoints.

2. **QS Tile: framework `TileService`** — no new dependency, minimal boilerplate. Implement `onClick()` via `startActivityAndCollapse(pendingIntent)` pointing to a transparent `CaptureDialogActivity`. Guard `requestAddTileService()` with API 33 version check.

3. **Share target: `ACTION_SEND` intent filter + `ShortcutManagerCompat`** — `ShareTargetActivity` is separate from `MainActivity`. Copy `EXTRA_STREAM` to staging storage synchronously in `onCreate`. Publish one dynamic shortcut for the active graph's journal page.

**Implementation order**: share target → QS tile → widget (lowest risk to highest).

---

## Pending Web Searches

1. `Jetpack Glance 1.1 widget API androidx.glance gradle dependency 2025 2026`
2. `Android Quick Settings TileService API 33 requestAddTileService 2025`
3. `Android share target ACTION_SEND intent filter direct share ShortcutManagerCompat 2025`
4. `Jetpack Glance vs RemoteViews widget testing robolectric limitations 2025`
5. `androidx.glance glance-appwidget 1.1.0 minimum API level minSdk widget`

## Web Search Results

*(To be populated by parent agent)*
