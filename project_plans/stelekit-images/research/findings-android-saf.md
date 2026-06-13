# Findings: Android Storage Access Framework (SAF)

## Summary
Android 10+ (API 29+) enforces scoped storage, making raw `file://` paths to external
storage inaccessible from app code. The correct pattern is `ACTION_OPEN_DOCUMENT` with
`takePersistableUriPermission()`, which yields a `content://` URI that survives across
process restarts. Coil 3 handles `content://` URIs natively on Android without a custom
fetcher — but only if the `ContentUriFetcher` is registered (it is by default in the
standard `coil-compose` artifact). Relative `../assets/` paths require a custom fetcher
regardless because they are app-private file URIs.

## Options Surveyed
1. `ACTION_OPEN_DOCUMENT` — SAF document picker, grants persistable URI permissions
2. `ACTION_GET_CONTENT` — legacy picker, URI permissions not persistable, deprecated for new apps
3. `MediaStore` API — query for images/media, requires `READ_MEDIA_IMAGES` on API 33+
4. `FileProvider` + `file://` — for app-internal files only; blocked for external storage
5. `ActivityResultContracts.PickVisualMedia` (Photo Picker) — API 33+ native, backported via Google Play services to API 19+

## Trade-off Matrix
| Approach | Persistent access | Scoped storage safe | Multi-select | Coil compat | Permission required |
|----------|------------------|---------------------|--------------|-------------|---------------------|
| `ACTION_OPEN_DOCUMENT` | Yes (takePersistable) | Yes | Yes | Yes (content://) | None at runtime |
| `ACTION_GET_CONTENT` | No | Partial | Partial | Yes (content://) | None at runtime |
| `PickVisualMedia` | No (but we copy) | Yes | Yes (multi) | Yes (content://) | None |
| `MediaStore` | Yes (own files) | Yes | Yes | Yes (content://) | READ_MEDIA_IMAGES (API 33+) |
| `file://` path | N/A | No (blocked API 29+) | N/A | Blocked | READ_EXTERNAL_STORAGE |

## Risk and Failure Modes

### `ACTION_OPEN_DOCUMENT` without `takePersistableUriPermission()`
- **Failure mode**: URI permission is lost after the picking Activity's result Intent is
  garbage collected — typically within the same process lifetime but definitely after restart.
- **Trigger**: Forgetting to call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` immediately in `onActivityResult`.
- **Mitigation**: Since SteleKit copies the file immediately on pick into `assets/`, persistent URI permissions are not needed. Copy bytes, then discard the `content://` URI.

### File copy from `content://` URI — null InputStream
- **Failure mode**: `openInputStream(uri)` returns null if the document provider is
  unavailable (e.g., Google Drive content not yet synced, provider app uninstalled).
- **Trigger**: Network document provider with offline content.
- **Mitigation**: Wrap in try/catch, null-check the stream, return `DomainError.Left` on failure.

### `content://` URI string passed to `java.io.File()`
- **Failure mode**: `File("content://...")` does not throw but creates an invalid path;
  all subsequent reads silently return empty or throw FileNotFoundException.
- **Trigger**: Developer coerces URI to string and passes to file API.
- **Mitigation**: Always use `ContentResolver.openInputStream(uri)` for `content://` URIs.

### Android 13+ (API 33) permission split
- **Failure mode**: `READ_EXTERNAL_STORAGE` is rejected silently on API 33+ — replaced by
  `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`.
- **Trigger**: Manifest declares `READ_EXTERNAL_STORAGE` only; app targets API 33+.
- **Mitigation**: Use `PickVisualMedia` which requires no manifest permission for
  picker-sourced images. [TRAINING_ONLY — verify exact permission requirements]

### Coil 3 custom `ImageLoader` losing default `ContentUriFetcher`
- **Failure mode**: Building `ImageLoader` manually and only registering the custom asset
  fetcher with `.components { add(MyFetcher.Factory()) }` may or may not replace defaults,
  depending on Coil 3 version behavior.
- **Trigger**: Using `components {}` block in builder; behavior changed between Coil 2→3.
- **Mitigation**: Verify Coil 3 docs — in Coil 3 the `.components{}` block appends to
  platform defaults by default. [TRAINING_ONLY — verify]

## Migration and Adoption Cost
- No new Gradle dependencies: `ActivityResultContracts` is in `androidx.activity:activity-ktx`.
- KMP `expect/actual` boundary must wrap `ActivityResultLauncher` — ~1 interface + 2 platform impls (Android, stub for others).
- Testing matrix: API 28 (pre-scoped), API 29-32 (scoped, old permissions), API 33+ (new permissions + PickVisualMedia native).

## Operational Concerns
- `content://` URIs are opaque; debugging requires `ContentResolver.getType(uri)` and `DocumentFile.fromSingleUri()`.
- Log URI, MIME type, and file size on every pick.
- Test on API 29, 33, and 34 emulators.

## Prior Art and Lessons Learned
- Logseq Android copies files immediately on pick into assets — same strategy recommended here.
- Common production pitfall: storing `content://` URI strings without `takePersistableUriPermission` → crash on app relaunch. Avoided by immediate copy.
- Google recommends `PickVisualMedia` since API 33; it is backported to API 19+ via Google Play services.

## Open Questions
- [ ] Does `PickVisualMedia` backport work reliably on API 28 without Google Play services (e.g., AOSP emulator)? — blocks: whether a fallback `ACTION_OPEN_DOCUMENT` path is required
- [ ] Does Coil 3.2.0 `.components{}` append to or replace platform defaults? — blocks: custom fetcher registration strategy

## Recommendation
**Recommended option**: `PickVisualMedia` with immediate file copy to `assets/`.

**Reasoning**: No manifest permissions required. Immediate copy avoids all persistent URI complexity. Simple, modern, backported.

**Conditions that would change this recommendation**: If the app ever needs to reference the original external file without copying, `ACTION_OPEN_DOCUMENT` + `takePersistableUriPermission` would be required.

## Pending Web Searches
1. `"PickVisualMedia" Android backport API 28 "Google Play services" site:developer.android.com` — confirm backport lower bound
2. `Coil 3 "components" "add" fetcher "replace" OR "append" platform defaults` — confirm append vs replace behavior
3. `"READ_MEDIA_IMAGES" "PickVisualMedia" permission required Android 33` — confirm no permission needed for picker

## Web Search Results (verified 2026-05-15)

### PickVisualMedia backport
- Backport extends to API 19 (Android 4.4) via Google Play services module.
- If `PickVisualMedia` is unavailable on a device, `ActivityResultContracts.PickVisualMedia` automatically falls back to `ACTION_OPEN_DOCUMENT`.
- Source: [Android Developers — Photo picker](https://developer.android.com/training/data-storage/shared/photo-picker), [Android Developers Blog: Photo Picker Everywhere](https://android-developers.googleblog.com/2023/04/photo-picker-everywhere.html)

### Coil 3 component registration — VERIFIED
- In Coil 3, first-party `Fetcher`s and `Decoder`s are added automatically via **service loader** on each new `ImageLoader`.
- Custom `.components { add(MyFetcher.Factory()) }` **appends** to (does not replace) defaults.
- Service loader behavior can be disabled with `ImageLoader.Builder.serviceLoaderEnabled(false)`.
- Source: [Coil — Extending the Image Pipeline](https://coil-kt.github.io/coil/image_pipeline/)

### READ_MEDIA_IMAGES + PickVisualMedia — VERIFIED
- `PickVisualMedia` does **not** require `READ_MEDIA_IMAGES` or `READ_EXTERNAL_STORAGE`.
- Source: [ActivityResultContracts.PickVisualMedia API reference](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.PickVisualMedia)
