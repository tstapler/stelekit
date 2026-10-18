# Plan: Share URL Strip Tracking

## Architecture

The feature is a pure view-time transformation applied at export time. It never modifies the stored graph. The toggle's state is persisted in `PlatformSettings` and reflected in `AppState`; the actual stripping is a pure function in `commonMain`.

```
AppState.shareStripTrackingParams (Boolean, default false)
  ↓ read by
ShareExportCoordinator.resolveExportContent(options = ExportOptions(stripTrackingParams = …))
  ↓ passes to
ExportService.exportToString(options)  →  PageExporter.export(page, blocks, refs, options)
  ↓ if options.stripTrackingParams
TrackingParamStripper.strip(url): String  (pure, commonMain)
```

## Implementation Steps

### Step 1 — `TrackingParamStripper` (pure utility, `commonMain`)

New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/TrackingParamStripper.kt`

```kotlin
object TrackingParamStripper {
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "dclid", "wbraid", "gbraid",
        "msclkid", "twclid", "mc_eid", "mc_cid", "igshid", "yclid"
    )

    /** Strips known ad-tracking query parameters from [url]. Returns [url] unchanged on parse error. */
    fun strip(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx == -1) return url
        val base = url.substring(0, qIdx)
        val rest = url.substring(qIdx + 1)
        val (queryPart, fragment) = if ('#' in rest) {
            val fIdx = rest.indexOf('#')
            rest.substring(0, fIdx) to rest.substring(fIdx)
        } else {
            rest to ""
        }
        val retained = queryPart.split('&')
            .filter { it.isNotEmpty() }
            .filterNot { pair ->
                val key = pair.substringBefore('=').lowercase()
                key in TRACKING_PARAMS
            }
        return if (retained.isEmpty()) "$base$fragment"
        else "$base?${retained.joinToString("&")}$fragment"
    }
}
```

### Step 2 — `ExportOptions` value object (`commonMain`)

New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportOptions.kt`

```kotlin
data class ExportOptions(
    val stripTrackingParams: Boolean = false,
)
```

### Step 3 — Update `PageExporter` interface

Add `options: ExportOptions = ExportOptions()` parameter to `export()`. All implementations get it with a default, so call sites without it remain binary-compatible during the transition.

### Step 4 — Update all four exporter implementations

In each `appendNode` / URL-rendering path:
- `MarkdownExporter`: apply `options.strip(node.url)` for `UrlLinkNode`, `MdLinkNode`, `ImageNode`.
- `HtmlExporter`: same.
- `PlainTextExporter`: same (URLs appear as raw text or `[text](url)` form).
- `JsonExporter`: same (URL fields in JSON output).

Helper extension (can live inline or as a private extension on `ExportOptions`):
```kotlin
private fun ExportOptions.strip(url: String) =
    if (stripTrackingParams) TrackingParamStripper.strip(url) else url
```

### Step 5 — Update `ExportService`

Thread `ExportOptions` through `exportToString()`, `exportToClipboard()`, `exportPageWithLinks()`, and `exportJournalRange()`, passing it down to every `exporter.export(...)` call.

### Step 6 — `ShareExportSettings` (`commonMain`)

New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ShareExportSettings.kt`

```kotlin
class ShareExportSettings(private val settings: Settings) {
    fun getStripTrackingParams(): Boolean =
        settings.getBoolean(KEY_STRIP_TRACKING_PARAMS, false)

    fun setStripTrackingParams(enabled: Boolean) {
        settings.putBoolean(KEY_STRIP_TRACKING_PARAMS, enabled)
    }

    companion object {
        private const val KEY_STRIP_TRACKING_PARAMS = "share.strip_tracking_params"
    }
}
```

### Step 7 — `AppState` field

Add `val shareStripTrackingParams: Boolean = false` to `AppState`.

### Step 8 — `ShareExportCoordinator` wiring

- Inject `ShareExportSettings` (constructor param).
- On init, read and apply the persisted value into `AppState`.
- Add `fun setStripTrackingParams(enabled: Boolean)` — persists and updates `AppState`.
- Pass `ExportOptions(stripTrackingParams = uiState.value.shareStripTrackingParams)` to all `resolveExportContent` / direct-export calls.

### Step 9 — Share dialog toggle

In `ShareContent` (`ShareDialog.kt`), add a `Row` with a `Switch` (or `Checkbox`) below the format picker:

```kotlin
// Strip tracking parameters toggle
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
) {
    Switch(
        checked = appState.shareStripTrackingParams,
        onCheckedChange = { viewModel.setStripTrackingParams(it) },
    )
    Spacer(Modifier.width(8.dp))
    Text(
        "Strip tracking parameters from URLs",
        style = MaterialTheme.typography.bodyMedium,
    )
}
```

### Step 10 — Tests

1. `commonTest/export/TrackingParamStripperTest.kt`:
   - Example-based: known tracking params stripped, non-tracking retained, fragment preserved, no `?` unchanged, malformed unchanged.
   - Property-based (`checkAll`): idempotency (strip twice == strip once); non-tracking key-value pairs always present in output.

2. `businessTest` or `jvmTest` — update `StelekitViewModelShareExportTest` to exercise the `stripTrackingParams = true` path through a real `ExportService`.

## Adversarial Review

**Concern 1: Breaking change to `PageExporter.export()` signature**  
Mitigated by defaulting `options = ExportOptions()` so all existing callers compile unchanged. The only forced changes are the four implementation files and `ExportService` internal calls.

**Concern 2: Fragment (#) in URL stripped incorrectly**  
`TrackingParamStripper.strip()` explicitly splits on `#` first, then re-appends the fragment. Covered by a dedicated test case.

**Concern 3: Image nodes that are local asset URIs (e.g. `assets://...`)**  
These have no `?` so `strip()` returns them unchanged immediately. No regression.

**Concern 4: Toggle state not persisted on first launch**  
`ShareExportSettings.getStripTrackingParams()` defaults to `false` when the key is absent — matches the "default off" AC.

**Concern 5: The `exportPage` / `exportSelectedBlocks` keyboard-shortcut paths bypass the dialog**  
These are command-palette shortcuts for direct clipboard export. They should also respect the persisted setting — `ShareExportCoordinator.exportPage()` reads `uiState.value.shareStripTrackingParams`, so as long as `AppState` is initialized from `ShareExportSettings` on construction, these paths are covered for free.

**Concern 6: `StelekitViewModel.setStripTrackingParams` is not yet wired**  
`StelekitViewModel` exposes coordinator methods via delegation; the new method follows the same pattern as `setShareFormat` and `setShareScope`.

## Delivery Sequence

Phase 1 (pure core, no UI): Steps 1–5.  Verified by `TrackingParamStripperTest` and existing exporter tests.  
Phase 2 (persistence + ViewModel): Steps 6–8.  Verified by `StelekitViewModelShareExportTest` extension.  
Phase 3 (UI toggle): Step 9.  Verified by `ShareInboxPanelTest`-style Robolectric test.  
