# Findings: iOS File Picker (UIImagePickerController vs PHPickerViewController)

## Summary
`UIImagePickerController` is deprecated in iOS 14+ for photo/video picking; Apple replaced
it with `PHPickerViewController` which requires no permission for library access (iOS 14+).
Both are accessible from Kotlin/Native via the Objective-C interop layer. The key pitfall
is that `PHPickerViewController`'s completion callback provides a `PHPickerResult` with an
`NSItemProvider` — loading the actual image data requires an async call to
`loadFileRepresentation(forTypeIdentifier:)` or `loadDataRepresentation()`. This async
callback runs on a background queue and must be bridged back to Kotlin coroutines carefully.

## Options Surveyed
1. `UIImagePickerController` — deprecated iOS 14+, still functional; returns `UIImage` directly in delegate callback
2. `PHPickerViewController` — recommended iOS 14+; returns `PHPickerResult` with `NSItemProvider`
3. `UIDocumentPickerViewController` — for general file picking (non-photo-library); needed for arbitrary file types
4. CameraUI via `UIImagePickerController.sourceType = .camera` — camera capture

## Trade-off Matrix
| Approach | iOS version | Permission required | Multi-select | Returns | Kotlin/Native access |
|-----------|-------------|---------------------|--------------|---------|---------------------|
| `UIImagePickerController` | All | `NSPhotoLibraryUsageDescription` (deprecated in iOS 14+) | No | `UIImage` in delegate | Yes (ObjC interop) |
| `PHPickerViewController` | 14+ | None (limited access model) | Yes | `NSItemProvider` | Yes (ObjC interop) |
| `UIDocumentPickerViewController` | 11+ | None | Yes | `URL` (file://  or security-scoped) | Yes (ObjC interop) |
| Camera (`UIImagePickerController`) | All | `NSCameraUsageDescription` | No | `UIImage` in delegate | Yes (ObjC interop) |

## Risk and Failure Modes

### `PHPickerResult.itemProvider.loadDataRepresentation()` — async callback bridging
- **Failure mode**: `loadDataRepresentation(forTypeIdentifier:completionHandler:)` calls
  the completion handler on an arbitrary background queue. If the Kotlin/Native coroutine
  continuation is resumed from this queue without proper dispatching, it may deadlock or
  corrupt state.
- **Trigger**: Directly calling `continuation.resume()` from the ObjC completion block.
- **Mitigation**: Use `suspendCoroutine` with `Dispatchers.Main` resume, or bridge via
  `kotlinx.coroutines.MainScope` + `async`. [TRAINING_ONLY — verify correct pattern]

### `UIImagePickerController` deprecation — future OS removal
- **Failure mode**: If Apple removes `UIImagePickerController` in a future iOS version,
  the app breaks without a code change.
- **Trigger**: Using deprecated API without migration plan.
- **Mitigation**: Use `PHPickerViewController` for library access; keep
  `UIImagePickerController` camera-only (not yet deprecated for camera in iOS 17).
  [TRAINING_ONLY — verify camera deprecation status]

### Security-scoped URL from `UIDocumentPickerViewController`
- **Failure mode**: The `URL` returned by `UIDocumentPickerViewController` is
  security-scoped; reading it without calling `startAccessingSecurityScopedResource()`
  returns empty data or throws permission error.
- **Trigger**: Passing the URL directly to `NSData(contentsOf:)` without scope access.
- **Mitigation**: Call `url.startAccessingSecurityScopedResource()` before reading;
  `url.stopAccessingSecurityScopedResource()` in a `defer` block after.

### `PHPickerViewController` — video files not returned as file URLs on iOS < 16
- **Failure mode**: On iOS 14/15, `PHPickerResult` for videos returns `NSItemProvider`
  that may not support `loadFileRepresentation` for all video types.
- **Trigger**: User picks a video on iOS 14/15.
- **Mitigation**: For images specifically, `loadDataRepresentation(forTypeIdentifier: "public.image")` is reliable on iOS 14+. [TRAINING_ONLY — verify]

### `NSPhotoLibraryUsageDescription` required in Info.plist for `UIImagePickerController`
- **Failure mode**: App crashes immediately on iOS when `NSPhotoLibraryUsageDescription`
  is missing from `Info.plist` but `UIImagePickerController` is used.
- **Trigger**: Missing plist key.
- **Mitigation**: Add to `iosMain` Info.plist. `PHPickerViewController` does NOT require
  this permission for picking (only for saving back to library).

### Kotlin/Native calling `presentViewController` — must be on main thread
- **Failure mode**: Presenting `PHPickerViewController` from a background Kotlin coroutine
  dispatcher crashes with "UI updates must be performed on the main thread."
- **Trigger**: Calling present from `Dispatchers.Default` or `Dispatchers.IO`.
- **Mitigation**: Wrap UI presentation in `withContext(Dispatchers.Main)` or use
  `DispatchQueue.main.async { }` from Kotlin/Native.

## Migration and Adoption Cost
- No new Kotlin dependencies; uses Kotlin/Native ObjC interop.
- Requires adding `NSCameraUsageDescription` and/or `NSPhotoLibraryAddUsageDescription`
  to Info.plist (not `NSPhotoLibraryUsageDescription` for PHPicker).
- ~100-200 lines of `iosMain` Kotlin for the picker wrapper and callback bridging.
- KMP `expect/actual` boundary: ~1 interface in `commonMain` + `iosMain` actual.

## Operational Concerns
- Test on iOS 14 (PHPicker minimum), iOS 16, iOS 17 simulators.
- Simulator limitations: camera is unavailable on simulator; test on device.
- PHPicker selection is sandboxed — user must explicitly select each time (no persistent access).

## Prior Art and Lessons Learned
- Compose Multiplatform's `compose-multiplatform` GitHub has iOS camera/gallery examples
  using `UIImagePickerController` — these are now outdated for gallery use.
- KMP community libraries like `compose-multiplatform-media-picker` wrap `PHPickerViewController`. [TRAINING_ONLY — verify library availability]
- The main pain point in the community is the async NSItemProvider → KMP coroutine bridge.

## Open Questions
- [ ] Is there a maintained KMP library that already wraps `PHPickerViewController` with a Kotlin coroutine API? — blocks: build vs buy decision for iOS picker
- [ ] Can `PHPickerViewController` return file URLs directly (not data) to avoid loading large images into memory? — blocks: memory strategy for large images on iOS

## Recommendation
**Recommended option**: `PHPickerViewController` for gallery picking, `UIImagePickerController` (camera source) for camera capture.

**Reasoning**: `PHPickerViewController` is the current Apple recommendation, requires no permissions for library access, and supports multi-select. Camera via `UIImagePickerController` is still the standard approach (not yet deprecated for camera).

**Conditions that would change this recommendation**: If a maintained KMP library already provides this wrapper, use it instead of writing from scratch.

## Pending Web Searches
1. `PHPickerViewController Kotlin Native KMP coroutine callback "suspendCoroutine"` — find established bridging pattern
2. `"compose-multiplatform" iOS "PHPickerViewController" OR "UIImagePickerController" example 2024` — check for official CMP examples
3. `KMP library "PHPickerViewController" wrapper Kotlin Multiplatform image picker` — check for existing libraries
4. `UIImagePickerController camera "deprecated" iOS 17 OR iOS 18` — verify camera deprecation status

## Web Search Results (verified 2026-05-15)

### `UIImagePickerController` deprecation status — PARTIALLY VERIFIED
- `UIImagePickerController` is deprecated for **photo library access** starting iOS 14.
- The **camera** source type (`sourceType = .camera`) is **not deprecated** as of iOS 17/18.
- Use `PHPickerViewController` for gallery, `UIImagePickerController` (camera) for camera capture.
- Source: [BiTE Interactive — Picking a Photo in iOS 14](https://www.biteinteractive.com/picking-a-photo-in-ios-14/), [Medium — iOS Using the camera with SwiftUI](https://medium.com/@quentinfasquel/ios-using-the-camera-with-swiftui-004731ce78f0)

### KMP image picker libraries — VERIFIED
- **ImagePickerKMP** (`io.github.ismoy:imagepickerkmp`) exists and is actively maintained (1.0.29+, updated 2025).
  - Cross-platform: Android + iOS; uses expect/actual + Compose Multiplatform.
  - On iOS uses `UIImagePickerController` internally.
  - Source: [GitHub — ismoy/ImagePickerKMP](https://github.com/ismoy/ImagePickerKMP)
- **KMPImagePicker** also available: [GitHub — QasimNawaz/KMPImagePicker](https://github.com/QasimNawaz/KMPImagePicker)

### Coroutine bridging for ObjC callbacks — VERIFIED
- Use `suspendCancellableCoroutine` (not `suspendCoroutine`) — supports cancellation even when the ObjC API doesn't.
- Pattern: register the ObjC callback, call `continuation.resume()` or `continuation.resumeWithException()` from within it.
- Source: [Kotlin Coroutine bridges — RevenueCat](https://www.revenuecat.com/blog/engineering/kotlin-coroutine-bridge/)

### REVISED RECOMMENDATION
Consider using **ImagePickerKMP** (`io.github.ismoy:imagepickerkmp`) rather than writing iOS picker from scratch. Evaluate whether it uses `PHPickerViewController` (preferred) or `UIImagePickerController` for gallery. If it only uses `UIImagePickerController` for gallery, consider contributing a `PHPickerViewController` backend or wrapping it directly.
