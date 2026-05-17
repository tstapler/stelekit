# Findings: Compose Drag-and-Drop Stability on Desktop JVM

## Summary
Compose Multiplatform introduced a unified `DragAndDrop` API in version 1.6.0, but the
Desktop JVM implementation for receiving *external* files (dragged from the OS file
manager) has had stability issues and API churn. As of CMP 1.6.x, `onExternalDrag` is
marked `@ExperimentalComposeUiApi` and the AWT `DropTarget` interop path exists as an
alternative. The AWT `DropTarget` approach is more stable but requires `SwingInteropLayer`
or `ComposePanel` wrapping. For SteleKit's use case, the AWT `DropTarget` approach is
recommended for production reliability.

## Options Surveyed
1. Compose `onExternalDrag` (`@ExperimentalComposeUiApi`) — Compose-native, cross-platform API
2. AWT `DropTarget` + `DropTargetListener` — stable, JVM-only, requires interop layer
3. `DragAndDropTarget` (CMP 1.7.x+) — newer unified API replacing `onExternalDrag` [TRAINING_ONLY — verify version]
4. Electron-style approach (for Web) — HTML `ondragover` + `ondrop` events

## Trade-off Matrix
| Approach | API stability | File type support | Multi-file | Platform | CMP version |
|-----------|--------------|------------------|------------|----------|-------------|
| `onExternalDrag` | Experimental (may change) | URI list | Yes | Desktop+Web | 1.5+ |
| AWT `DropTarget` | Stable (Java AWT) | `java.io.File` list | Yes | JVM only | Any |
| `DragAndDropTarget` | Experimental (1.6+) | Varies | Yes | Desktop+Web | 1.6+ |
| HTML drag events | Stable (Web spec) | `File` objects | Yes | Web only | N/A |

## Risk and Failure Modes

### `onExternalDrag` API breaking changes between CMP versions
- **Failure mode**: Code using `onExternalDrag` from CMP 1.5.x does not compile on 1.6.x
  due to renamed/moved API (`ExternalDragValue`, parameter types changed).
- **Trigger**: Upgrading CMP version without checking experimental API changelog.
- **Mitigation**: Pin to a specific CMP version and test drag-drop on every upgrade.
  Wrap drag-drop in a platform-specific `actual fun` so breakage is localized.

### `onExternalDrag` on Linux — Wayland vs X11 differences
- **Failure mode**: File drag from Nautilus (Wayland) may not deliver file paths in the
  expected format; some Linux setups deliver `text/uri-list` with `file://` URIs that
  require URL decoding.
- **Trigger**: Running on Linux with Wayland compositor.
- **Mitigation**: Always decode `file://` URIs: `URI(uriString).toFile()`. Handle both
  `file://` and plain paths. [TRAINING_ONLY — verify Wayland behavior]

### AWT `DropTarget` not working inside `ComposeWindow`
- **Failure mode**: Setting `DropTarget` on the root `ComposeWindow`'s `contentPane`
  works but may conflict with Compose's own event handling, causing dropped events to
  be consumed by Compose before reaching the `DropTarget`.
- **Trigger**: Adding AWT `DropTarget` to a component that Compose also manages.
- **Mitigation**: Use `window.rootPane.setDropTarget(...)` or the `contentPane` at the
  JVM level, and check that Compose experimental drag-drop is disabled to avoid double handling.

### `transferable.getTransferData(DataFlavor.javaFileListFlavor)` ClassCastException
- **Failure mode**: Some clipboard/drag sources (e.g., virtual file systems, ZIP archives
  in Finder) return a `List<?>` where elements are not `java.io.File` but custom subtypes;
  cast to `List<File>` throws `ClassCastException`.
- **Trigger**: User drags from a virtual filesystem (e.g., compressed archive, network share).
- **Mitigation**: Use `isDataFlavorSupported(DataFlavor.javaFileListFlavor)` check; wrap
  cast in try/catch; filter list to `File` instances only.

### Multiple drag events for a single drop
- **Failure mode**: `DropTargetDragEvent` fires repeatedly during hover; processing
  each as a "file dropped" event would trigger multiple copies.
- **Trigger**: Treating `dragEnter`/`dragOver` the same as `drop`.
- **Mitigation**: Only copy files in `drop(DropTargetDropEvent)`, not in `dragEnter`/`dragOver`.

## Migration and Adoption Cost
- `onExternalDrag`: zero extra dependencies; add `@OptIn(ExperimentalComposeUiApi::class)`.
- AWT `DropTarget`: zero extra dependencies; ~50 lines of Java interop boilerplate.
- Both require `jvmMain` platform-specific code; `commonMain` gets a no-op `actual fun`.

## Operational Concerns
- Log drag events: file paths, MIME types, success/failure of copy.
- Test on macOS (Finder), Windows (Explorer), Linux (Nautilus/X11 and Wayland).
- Test with multiple files dragged simultaneously.

## Prior Art and Lessons Learned
- JetBrains IntelliJ IDEA uses AWT `DropTarget` for file drag-into-editor.
- Several open-source CMP desktop apps (e.g., Compose Image Viewer) use `onExternalDrag`
  but note it breaks on CMP upgrades.
- The CMP changelog for 1.6.0 introduced `DragAndDrop` API unification — projects on
  1.5.x using `onExternalDrag` needed migration. [TRAINING_ONLY — verify exact version]

## Open Questions
- [ ] What is the exact CMP version that introduced the stable `DragAndDrop` API? — blocks: minimum CMP version requirement
- [ ] Does `onExternalDrag` work correctly on the project's current CMP version? — blocks: which approach to use
- [ ] Is there a JetBrains-official example of external file drag on Desktop? — blocks: implementation reference

## Recommendation
**Recommended option**: AWT `DropTarget` on Desktop JVM, wrapped in a `jvmMain` `actual fun`.

**Reasoning**: More stable than the experimental Compose API; does not change between CMP versions; well-understood Java interop. The experimental `onExternalDrag` API is too likely to break on CMP upgrades for a production feature. The AWT path is used by IntelliJ IDEA and is the de-facto standard for Compose Desktop file drop.

**Conditions that would change this recommendation**: If CMP releases a stable (non-experimental) unified drag-drop API that works across Desktop and Web, migrate to it.

## Pending Web Searches
1. `"onExternalDrag" "ExperimentalComposeUiApi" compose multiplatform desktop file drop 2024` — check current stability status and CMP version
2. `compose multiplatform 1.6 "DragAndDrop" desktop external files stable` — verify unified API status in 1.6
3. `"DropTarget" "ComposeWindow" kotlin desktop file drag drop example` — find working AWT example
4. `compose multiplatform desktop drag drop "onExternalDrag" breaking change` — check for known breakage reports

## Web Search Results (verified 2026-05-15)

### `onExternalDrag` deprecated — VERIFIED
- `Modifier.onExternalDrag` was **deprecated** in CMP 1.7.0 in favor of `Modifier.dragAndDropTarget`.
- `Modifier.onExternalDrag` was **removed** in CMP 1.8.0 — using it on 1.8.0 will not compile.
- Source: [What's new in Compose Multiplatform 1.7.3](https://www.jetbrains.com/help/kotlin-multiplatform-dev/whats-new-compose-170.html), [Release 1.8.0](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.8.0)

### `dragAndDropTarget` status — still experimental
- `Modifier.dragAndDropSource` and `Modifier.dragAndDropTarget` are available in common code but **currently only work on desktop and Android** source sets (not Web).
- Both are still **experimental** and require `@OptIn` annotation.
- External files accessible via `dragInfo.transferData?.getNativeFileList()`.
- Source: [Drag-and-drop operations — KMP Docs](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-drag-drop.html)

### Linux app-not-closing bug — VERIFIED
- `Modifier.onExternalDrag` caused apps not to close on Linux (25-33% frequency).
- This was linked to `onExternalDrag` specifically; unknown if `dragAndDropTarget` has the same issue.
- Source: [kotlinlang Slack #compose-desktop](https://slack-chats.kotlinlang.org/t/16762970/for-some-time-now-my-application-has-not-been-closing-proper), [Issue #4541](https://github.com/JetBrains/compose-multiplatform/issues/4541)

### REVISED RECOMMENDATION
Given that `onExternalDrag` is removed in CMP 1.8.0, the updated recommendation is:
- **Use `Modifier.dragAndDropTarget` (CMP 1.7+)** if the project targets CMP 1.7.0+.
- Fall back to AWT `DropTarget` only if CMP < 1.7.0 or if `dragAndDropTarget` proves unstable in testing.
- Check if project's current CMP version is >= 1.7.0 before choosing.
