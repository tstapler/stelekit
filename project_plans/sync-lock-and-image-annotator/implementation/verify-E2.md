# Verify E2 (annotator interaction): verdict PASS-WITH-FOLLOWUPS

Branch `feat/annotator-modes-and-canvas`. Reviewed `e3831960c4..7d893ca3a4`; verifier commits `fa951ccefd`, `9d8c466ab7`, `230f591104`.

## Defects found and fixed

1. **Photo viewport collapsed on short or large-font windows (reproduced).** New `AnnotatorViewportTest` (androidUnitTest) measured the canvas node before the fix: 0 dp at 320x470, 30 dp at 320x470 font 2.0, 86 dp at 640x360, 48 dp at 640x360 with the Distance prompt, 164.5 dp at 360x640 font 2.0 with the prompt. Fix `fa951ccefd`: `EditorChromePolicy.collapseDetails` (window under 600 dp high or font scale >= 1.5) folds the ARCore/tilt warnings, advice strip, GPS row and tag panel behind a 48 dp "Details" toggle that shares the calibration-badge row. After the fix all six configs pass (floor: min(200 dp, 30% of window); 20% at the 320x470 Robolectric default, which is smaller than any shipping phone). Measured after the fix: 102 dp at 320x470 with prompt, 154 dp at 320x470 font 2, 124 dp at 640x360 with prompt. Tradeoff: tags and GPS take one extra tap on short windows; the existing Robolectric tag test now taps Details first.
2. **Chip single tap delayed ~300 ms.** `combinedClickable(onDoubleClick=...)` holds `onClick` for the double-tap window on every keepable chip (AnnotationToolbar.kt, chip modifier). Fix `9d8c466ab7`: dropped double-tap; Keep tool remains on long-press, the visible 48 dp toggle, the K key and the TalkBack custom actions (ux.md:163 calls double-tap a "shortcut, not the only path"). Test now asserts a tap selects within one frame with autoAdvance off. UX-14/UX-55 wording about double-tap is now stale (update ux.md in the PR).
3. Test fixes `230f591104` (shortcut control press lacked a refocus; Robolectric tag test needs Details).

## (d) Shortcuts vs text fields

`onAnnotationToolKeyEvent` is gated by `!tagFieldFocused` (AnnotationEditorScreen.kt, `enabled =`). New jvmTest `AnnotationKeyboardShortcutsTest.toolLetters_should_BeIgnored_WhileTheTagFieldHasFocus` presses D/A/G/L/R/K/Esc in the focused tag field (tool stays Select), then refocuses and checks D works. Mutation check: with the guard removed the test still passes, because Compose Desktop's text field consumes those key events itself. The guard is therefore defensive and the test is a behaviour regression, not a guard-specific one. Limitation: the guard list is hand-maintained (tag field, label input, grid-ref dialog); dialog fields run in their own windows.

## Layer reviews

- Idioms (subagent): no blockers. Nits: inline `Color(0xFF..)` literals, 6-line comment at the Scaffold modifier, `DisposableEffect(currentTool)` captures `callbacks` (use latest), `density` captured once in `newGestureEnv`.
- Architecture (subagent): ToolPolicy pure PASS; FIT_SCALE vs 0.25 floor PASS (abs eps comparisons); hit radii in screen dp PASS; clamping PASS (nudge path traced by reading only); exporter independent of view/pasteboard PASS (file unchanged since planning commit); layering PASS. **Follow-up (MAJOR, not fixed):** the gesture layer is not fully policy-free: `runGesture` branches on `tool == SELECT`, `classify()` feeds tool/pendingPoints into `classifyPlacement`, `commitPlacement` bumps `PlacementCounters`. Inject a host-supplied placement decision instead. Behaviour is correct today and covered by tests; this is a structure debt against the ADR-005 wording.

## (b) Full suite (production HEAD `9d8c466ab7`)

`scripts/jvm-display-check.sh -- ./gradlew :kmp:jvmTest :kmp:testDebugUnitTest :kmp:detekt --continue` (real X11 display reachable). From XML: jvmTest 14167 tests, 3 failed, 68 skipped; testDebugUnitTest 4461 tests, 3 failed, 3 skipped; detekt task ran with no failure.

| Failure | Class | Evidence |
|---|---|---|
| `SafChangeDetectorFileObserverTest` (2 entries, jvm + android) | Environmental | `IOException: User limit of inotify watches reached` |
| `AnnotationEditorScreenRobolectricTest.rendersEveryCommittedAnnotationTypeAndInProgressPolygon...` | Caused by this branch (fix 1) | "Tags" not found behind Details; fixed in `230f591104`, passes on rerun |
| `AnnotationKeyboardShortcutsTest.toolLetters_should_BeIgnored...` (2 entries) | Caused by my new test (control press); fixed `230f591104`, 3 clean reruns |
| `AssetIndexingControllerTest.internalBuild_flippingTheSwitch...` (expected 5, was 10) | Flaky race in untouched code (no asset files in the diff); isolated reruns: branch 3 of 6 failed, planning commit `e3831960c4` 0 of 6 failed | UNPROVEN as pre-existing: the A/B gap is not explained; owner/CI should watch it. The doubled count points to two backfill walkers racing. |

Targeted rerun after the test fixes (not a second full suite): the three annotator classes pass; production code did not change after the full run.

## (c) Bazel

`scripts/bazel-lowprio.sh test //kmp:business_tests` (HEAD `230f591104`): `//kmp/src/businessTest/kotlin:business_tests PASSED in 175.4s`, 1 of 1 test passes; `bazel shutdown` run afterwards.

## (f) Validation-row coverage

Every E2 validation row naming a `CT/BT/JT/AT` class exists in the tree except: U-E2-16 `ScopeEscapeAuditTest` (planned under E1 task 1.8a, absent; a grep shows the only `rememberCoroutineScope` in ui/annotate is the transient `backScope`), I-E2-06 `ZoomJank12MpTest`, I-E2-09 `PasteboardFrameScreenshotTest`. UX ids are not cited in test names, so a name-level UX-to-test map was not produced (gap).

## (e) Latency

Before fix: every keepable chip tap waited the ~300 ms Compose double-tap window. After: one frame.

## (g) Owner tasks

Remaining: Task 2.7a (owner-verification-E2.md sections A-E), I-E2-06 jank (needs the device harness, section C), I-E2-09. I-E2-09 was not written: a Roborazzi golden must be recorded on the CI renderer (fonts and AA differ from this Wayland/Xwayland host), so recording it locally would produce a golden that fails in CI. Record it from CI with `-Proborazzi.test.record=true`.

## Not done

No emulator smoke run (no `adb` device attempted; Robolectric/Compose tests only). No UX-id-to-test map.
