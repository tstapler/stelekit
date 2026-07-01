# ADR-002: Platform File Opener — New `PlatformFileOpener` expect/actual Interface

**Status**: Accepted

---

## Context

REQ-3 requires an "Open in system app" action from `AssetActionMenu`. The action must
invoke the host OS's registered handler for the file's MIME type — `java.awt.Desktop` on
JVM/Desktop, `Intent.ACTION_VIEW` on Android.

No existing abstraction in the codebase covers this use case. The closest precedent is
`platform/OpenInBrowser.kt`, a top-level `expect fun openInBrowser(url: String)` with
`jvmMain` and `androidMain` actuals. That pattern is insufficient here for two reasons:

1. **MIME type required on Android.** `Intent.ACTION_VIEW` must include a MIME type via
   `intent.setDataAndType(uri, mimeType)` to route to the correct viewer app. A bare file
   path is not enough; the interface must carry the MIME type alongside the path.

2. **FileProvider scope is too narrow for non-SAF assets on Android.** The existing
   `file_provider_paths.xml` covers only `cache-path/share_export/`. Assets stored on
   non-SAF graphs live under the Documents directory
   (`/storage/emulated/0/Documents/.../assets/`), which is outside that declared path. On
   API 24+ passing a `file://` URI from outside the FileProvider scope throws
   `FileUriExposedException` and crashes. SAF-backed graphs are safe — `PlatformFileSystem`
   returns a `content://` DocumentsContract URI for `saf://` paths, which can be passed to
   `Intent.ACTION_VIEW` directly. Non-SAF assets must be copied to
   `cacheDir/share_export/` before sharing, matching the existing note-export flow and
   keeping the FileProvider declaration narrow.

The `PlatformShareProvider` pattern (`@Composable expect fun rememberShareProvider()`)
provides the approved template for composable-scoped platform services that need `Context`
on Android.

---

## Decision

Create a new **`PlatformFileOpener`** interface with a `@Composable expect fun
rememberPlatformFileOpener(): PlatformFileOpener` factory, following the
`PlatformClipboardProvider` / `PlatformShareProvider` pattern:

**commonMain** — `platform/PlatformFileOpener.kt`
```kotlin
interface PlatformFileOpener {
    suspend fun openFile(absolutePath: String, mimeType: String)
}

@Composable
expect fun rememberPlatformFileOpener(): PlatformFileOpener
```

**jvmMain** — `platform/PlatformFileOpener.jvm.kt`
```kotlin
@Composable
actual fun rememberPlatformFileOpener(): PlatformFileOpener = remember {
    object : PlatformFileOpener {
        override suspend fun openFile(absolutePath: String, mimeType: String) =
            withContext(Dispatchers.IO) {
                if (Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(File(absolutePath))
                }
            }
    }
}
```

**androidMain** — `platform/PlatformFileOpener.android.kt`

The Android actual implements the three-way path detection required by the FileProvider
gap:

1. **`saf://` path** → call `PlatformFileSystem.resolveAssetUri()` to obtain a
   `content://` DocumentsContract URI; pass directly to `Intent.ACTION_VIEW` with
   `FLAG_GRANT_READ_URI_PERMISSION`.
2. **Direct POSIX path with MANAGE_EXTERNAL_STORAGE** → copy file to
   `cacheDir/share_export/`, obtain a FileProvider URI via
   `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)`,
   set `FLAG_GRANT_READ_URI_PERMISSION`.
3. **Legacy Documents path without MANAGE_EXTERNAL_STORAGE** → same copy-to-cache
   approach as (2); avoids `FileUriExposedException` and requires no `file_provider_paths.xml`
   changes.

`Context` is obtained via `LocalContext.current` inside the `@Composable actual fun
rememberPlatformFileOpener()`, consistent with how `PlatformShareProvider.android.kt`
accesses context.

---

## Alternatives Considered

**Pass raw file path to an existing platform service (no new abstraction).**
The `openInBrowser` top-level function could be repurposed by passing a `file://` URI.
This fails on Android because (a) `Intent.ACTION_VIEW` for local files requires FileProvider
on API 24+ and (b) no MIME type would be available to route to the correct viewer. Rejected.

**Extend `PlatformShareProvider` with a `shareFile` overload.**
`PlatformShareProvider` is defined as `export / share` semantics (write to clipboard, send
to another app via Android share sheet). Opening a file in a viewer is a distinct action
— it targets a specific registered handler rather than offering a share sheet. Mixing these
semantics in one interface would make the name misleading and complicate future per-platform
divergence (e.g., a custom in-app viewer for Desktop). Rejected.

**Add `<external-path name="assets" path="." />` to `file_provider_paths.xml`.**
This would allow passing a FileProvider URI for any external storage path without a cache
copy. However, it grants any app receiving the Intent read access to the entire external
storage tree via the FileProvider, widening the security scope significantly. The copy-to-cache
approach is scoped to a single file per invocation and mirrors the pattern already used for
note export. Rejected in favor of copy-to-cache.

**Web/iOS actuals.**
Both are listed as non-goals for this PR (see requirements.md Non-goals section). The
`wasmJsMain` actual will provide a no-op stub; the `iosMain` actual is deferred to a future
Epic.

---

## Consequences

- Three new files: `commonMain/.../platform/PlatformFileOpener.kt`,
  `jvmMain/.../platform/PlatformFileOpener.jvm.kt`,
  `androidMain/.../platform/PlatformFileOpener.android.kt`.
- A `wasmJsMain` no-op stub is required for compilation even though Web is a non-goal.
- The Android actual performs a file copy on the hot path for non-SAF graphs. The copy is
  bounded by the asset file size and runs on `Dispatchers.IO`; for large video files this
  may be slow. A progress indicator in the action menu is not required for this PR but
  should be tracked as a follow-up for video assets.
- `file_provider_paths.xml` requires no changes — the existing `cache-path/share_export/`
  declaration is sufficient.
- `AssetBrowserViewModel` and `AssetDetailViewModel` obtain `PlatformFileOpener` via
  `rememberPlatformFileOpener()` in the composable layer and pass the result down; they do
  not hold it as a constructor-injected dependency, consistent with the `rememberCoroutineScope`
  ownership rules in CLAUDE.md.
