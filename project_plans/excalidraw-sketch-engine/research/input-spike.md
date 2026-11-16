# Input spike matrix (Spike 0.1.4): STUB

**Status**: UNMEASURED. Task 0.1.4a to 0.1.4d fill every cell below; until a cell holds a measured value it reads UNMEASURED and the plan treats the target as NOT Supported (the edge-pan strip stays off, D-32). ADR-007 links here.

## Pointer capability matrix (Story 0.1.4, first criterion)
| Target | `PointerType` from Compose core | Pressure | Hover | `compose-stylus` needed | Eraser-end tool type | Barrel button |
|---|---|---|---|---|---|---|
| Desktop (JVM) | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED |
| Android (Robolectric `MotionEvent`, then Pixel_Fold AVD) | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED |
| wasmJs (`pointerType=pen`) | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED |
| iOS (compile only) | compiles: UNMEASURED | n/a | n/a | n/a | n/a | n/a |

## Browser gestures (Task 0.1.4b)
| Check | Result |
|---|---|
| wasm canvas host stops page scroll on two-finger pinch (`touch-action: none`) and both pointers reach the handler | UNMEASURED |

## System-gesture exclusion, edge deferral and palm (Task 0.1.4d; UX-102, UX-121, D-18)
A target class is marked Supported only when a real drag that started in the protected span reached the app on every attempt of at least 20.
| Target | Drag inside the span | Drag outside the span | Drag in the gesture inset | Granted span height | `TOOL_TYPE_PALM` / `ACTION_CANCEL` | Supported |
|---|---|---|---|---|---|---|
| Android (`setSystemGestureExclusionRects`) | UNMEASURED | UNMEASURED | UNMEASURED | UNMEASURED (200 dp expected) | UNMEASURED | NOT Supported until measured |
| Touch desktop | UNMEASURED | UNMEASURED | UNMEASURED | n/a | UNMEASURED | NOT Supported until measured |
| Web (`overscroll-behavior: none` + `touch-action: none`) | UNMEASURED | UNMEASURED | UNMEASURED | n/a | UNMEASURED | NOT Supported until measured (a Safari iOS edge swipe cannot be blocked) |
| iOS (`preferredScreenEdgesDeferringSystemGestures`, compile only) | device behaviour UNVERIFIED | UNVERIFIED | UNVERIFIED | n/a | UNVERIFIED | NOT Supported until a device run |
