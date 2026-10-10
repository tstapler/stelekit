# Requirements: imgbroke-fixes

**Date**: 2026-06-21
**Type**: bug fix
**Complexity**: 1 — quick task

## Problem Statement

`ciCheck` fails to compile `androidTest` sources because `PhotoInsertAndroidTest.kt:171`
constructs `AndroidMediaAttachmentService` without the `fileSystem` parameter that was added
as part of the SAF image-upload fix (branch `stelekit-imgbroke`). Compilation error:

```
e: PhotoInsertAndroidTest.kt:171:13 No value passed for parameter 'fileSystem'.
```

Affects: CI pipeline, any developer running `./gradlew ciCheck`.

## Baseline

`ciCheck` exits 0. `PhotoInsertAndroidTest` compiles and the TC-PHOTO-005 test executes
on device without runtime errors.

## Users / Consumers

Developers and CI on the `stelekit-imgbroke` branch.

## Success Metrics

`./gradlew ciCheck` exits 0 with no compilation errors in `androidTest` sources.
TC-PHOTO-005 (`clipboardImageDetectionReturnsFalseForEmptyClipboard`) passes on device.

## Appetite

Small (< 30 minutes — single-line fix).

## Constraints

None. Must not change test behavior — only supply the missing constructor argument.

## Non-functional Requirements

- **Performance SLO**: not applicable
- **Scalability**: not applicable
- **Security classification**: internal
- **Data residency**: no special requirements

## Scope

### In Scope

- Supply `fileSystem = JavaFileSystem()` in TC-PHOTO-005 constructor call.

### Out of Scope

- Changes to test logic or assertions.
- Changes to `AndroidMediaAttachmentService` interface.

## Rabbit Holes

None — the constructor signature is fixed; the test just needs to supply the argument.

## Alternatives Considered

- Make `fileSystem` have a default value in the class constructor — rejected because
  `AndroidMediaAttachmentService` is always constructed in production with a real
  `PlatformFileSystem`; a default would silently allow incomplete construction.

## Feasibility Risks

None.

## Observability Requirements

Not applicable — pure compile-time fix.

## Risk Control

Not needed — low risk, no special rollout required.

## Open Questions

None.
