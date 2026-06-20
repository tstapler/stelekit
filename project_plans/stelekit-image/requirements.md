# Requirements: Image Insertion End-to-End (stelekit-image)

## Problem Statement

On Android, users cannot insert images into their notes:
1. No image/camera icon is visible in the editor toolbar even when a block is in edit mode
2. When they do find and tap the button, nothing happens
3. Existing images that live in the Logseq `uploads/` directory do not render — they appear as raw path text

## Platform

Android phone/tablet (primary). Desktop is already partially working; this feature finishes the Android side.

## Confirmed Symptoms (from user)

| # | Symptom | User action |
|---|---------|-------------|
| S1 | No image icon visible in toolbar | Tapped into a block (edit mode), looked at MobileBlockToolbar |
| S2 | Nothing happens when trying to insert | Tried to use image/camera via some path |
| S3 | Image shown as path text in editor | Existing image blocks (from Logseq `uploads/`) not rendering |

## Root-Cause Analysis

### RC-1: Camera permission never requested at runtime
`AndroidCameraProvider.capturePhoto()` calls `ContextCompat.checkSelfPermission` and returns `Either.Left(PermissionDenied)` when not granted. There is no runtime permission request dialog anywhere. The app declares `<uses-permission android:name="android.permission.CAMERA" />` in the manifest but Android requires a runtime `RequestPermission` launcher for dangerous permissions. The user sees a snackbar "Camera permission denied" that disappears before they can react.

### RC-2: `SteleKitAssetMapper` doesn't handle Logseq `uploads/` paths
Logseq stores images as `![alt](../uploads/filename.png)`. `SteleKitAssetMapper` only rewrites paths starting with `../assets/`. Blocks with `../uploads/` paths fall through to Coil's default handlers which cannot resolve relative paths, so the image fails to load and Coil shows nothing.

### RC-3: `onAttachImage` toolbar icon may be hidden (secondary investigation)
`MobileBlockToolbar` shows the `AttachFile` icon only when `editingBlockId != null` (edit mode) AND `onAttachImage != null`. `onAttachImage` is non-null when `attachmentService != null`. On Android `attachmentService = rememberAndroidMediaAttachmentService(context)` is always non-null. This path looks correct. This issue may be a UX discoverability problem rather than a code bug — the icon only appears inside the primary actions row which is shown only in edit mode, and the user may not have been in true edit mode (cursor in a text field).

## Functional Requirements

### FR-1: Android camera permission request
When the user taps the camera button and camera permission has not been granted, the app must request the `CAMERA` permission via `ActivityResultContracts.RequestPermission`. If the user grants it, proceed with capture. If denied, show a helpful snackbar "Camera access is needed to take photos for notes."

- Launcher must be created at the Activity/composable level, not inside a coroutine or remember block — ActivityResultLauncher cannot be created after composition starts.
- The permission request must not block the UI thread.
- If permission was already granted, skip the dialog and capture immediately.

### FR-2: Support `uploads/` paths in `SteleKitAssetMapper`
Extend `SteleKitAssetMapper` to also rewrite paths starting with `../uploads/`:
- `../uploads/filename.png` → `file://<graphRoot>/uploads/filename.png`
- Same path-traversal guards as for `../assets/` (reject `..` components and backslashes).

### FR-3: Camera capture inserts rendered image block on current page
When camera capture succeeds:
- The `image_annotation` block is inserted on the current page (not just today's journal).
- The block renders as a tappable thumbnail in the page view (this already works via `ImageAnnotationBlockItem`).
- A snackbar confirmation "Photo added to this page" appears.
- `navigateAfterImport = false` (stay on page; user can tap thumbnail to open annotation editor).

### FR-4: Gallery picker inserts image as rendered markdown block
When the gallery picker (`AndroidMediaAttachmentService.pickAndAttach`) succeeds:
- Inserts `![filename](../assets/filename.ext)` at the cursor of the editing block.
- The image renders inline via `ImageBlock` (already works once the block is saved and re-rendered).
- A snackbar confirmation "Image attached" appears on success.

### FR-5: Icon discoverability (non-blocking)
Add a comment to `EditorCapabilities.onAttachImage` noting that it only shows in edit mode, and ensure the toolbar layout doesn't clip the icon on narrow screens. No layout changes required unless overflow is confirmed by testing.

## Non-Requirements

- iOS image insertion (separate story)
- Desktop live webcam capture (WebcamCameraProvider.isAvailable is already false on desktop; file picker already works)
- Changing image storage directory away from `assets/images/` — only add `uploads/` read support
- ARCore depth, monocular ML estimation — out of scope

## Acceptance Criteria

| # | Criterion |
|---|-----------|
| AC-1 | On Android, when user taps camera icon and CAMERA permission not yet granted, a system permission dialog appears |
| AC-2 | After granting permission, camera opens (or CameraX flow runs) and photo is saved to `assets/images/` |
| AC-3 | After capture, a new `image_annotation` block appears on the current page and renders as a thumbnail |
| AC-4 | On Android, when user taps gallery (AttachFile) icon and selects an image, a `![...](../assets/...)` block is inserted at the cursor |
| AC-5 | Existing blocks with `../uploads/filename.png` content now render as images (not text) |
| AC-6 | All existing tests in `CaptureAndImportTest`, `ImageImportServiceIntegrationTest`, `GalleryViewModelTest` continue to pass |
