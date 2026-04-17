# Pitfalls Research: Android UX Overhaul

**Status**: Research complete
**Date**: 2026-04-12
**Researcher**: Claude (claude-sonnet-4-6)
**Scope**: Known failure modes for edge-to-edge, bottom navigation, predictive back, and dynamic color in Compose / Compose Multiplatform

---

## Executive Summary

Six concrete pitfall areas were investigated. Two are **blockers** that will cause visible layout breakage or rejected Play Store submissions if ignored. Four are **known risks** requiring careful implementation but not hard blockers. The most dangerous is the `adjustResize` + edge-to-edge conflict (Pitfall 1): SteleKit's `AndroidManifest.xml` currently has `windowSoftInputMode="adjustResize"` on `MainActivity`, which is the exact flag that breaks keyboard handling when edge-to-edge is enabled on Android 11+. It must be changed before F2 ships.

The Roborazzi baseline situation (Pitfall 3) is a process concern, not a code bug — the current screenshot tests use `captureRoboImage()` with output paths under `build/outputs/roborazzi/` and no committed baseline directory, which means the existing test suite is already in "record mode" (no baseline comparison). This actually reduces the blast radius of the layout changes, but it also means screenshots are not currently providing regression protection — an issue to address as part of the overhaul.

| Pitfall | Severity | Classification |
|---|---|---|
| 1. Edge-to-Edge + IME (`adjustResize`) | Critical | **Blocker** |
| 2. Scaffold inset double-consumption | High | **Blocker** |
| 3. Roborazzi baseline invalidation | Medium | Known Risk |
| 4. AnimatedContent predictive back flicker | Medium | Known Risk |
| 5. Dynamic color contrast regressions | Medium | Known Risk |
| 6. NavigationBar + ModalBottomSheet overlap | Low | Known Risk |

---

## Pitfall 1 — Edge-to-Edge + IME (`adjustResize`) Conflict

### Classification: BLOCKER

### Description

`windowSoftInputMode="adjustResize"` is the standard pre-edge-to-edge way to push content up when the soft keyboard appears: the system resizes the window, the root view shrinks, and scroll containers reflect the new size. On Android 11+ (API 30+), when edge-to-edge is enabled — via `WindowCompat.setDecorFitsSystemWindows(window, false)` or the `enableEdgeToEdge()` Activity extension — the system window is no longer sized the same way. The `adjustResize` mode stops working reliably because the system is now managing insets independently of window resizing. The keyboard may appear but the content area will not scroll up to reveal the focused `TextField`, leaving the IME overlapping the editor.

SteleKit's manifest currently has:

```xml
android:windowSoftInputMode="adjustResize"
```

on `MainActivity`. This will silently break keyboard handling the moment `WindowCompat.setDecorFitsSystemWindows(window, false)` is added for F2.

### Root Cause

`adjustResize` and edge-to-edge are architecturally incompatible. `adjustResize` relies on the system shrinking the app window when the keyboard appears. Edge-to-edge opts the app out of that window management — the app is responsible for reacting to the `WindowInsetsCompat.Type.ime()` inset itself. These two mechanisms cannot coexist.

### Fix / Mitigation

**Step 1 — Change the manifest flag.**

```xml
android:windowSoftInputMode="adjustNothing"
```

`adjustNothing` tells the system: do not resize or pan my window when the keyboard appears. This is the correct companion to edge-to-edge because the app will now handle IME insets itself.

**Step 2 — React to IME insets in the Compose layer.**

The Compose Multiplatform `WindowInsets.ime` inset becomes the signal. Apply it at the scroll container level, not the root scaffold level:

```kotlin
// In the block editor scroll container (androidMain or via expect/actual)
LazyColumn(
    contentPadding = PaddingValues(bottom = WindowInsets.ime
        .asPaddingValues()
        .calculateBottomPadding())
) { ... }
```

Alternatively, the `imePadding()` modifier can be applied on the outermost content Box inside the Scaffold content slot, after the Scaffold has consumed `navigationBarsPadding`:

```kotlin
// Inside Scaffold content lambda:
Box(Modifier.fillMaxSize().imePadding()) {
    // block editor content
}
```

**Step 3 — On Android 12+ (API 31+), consider WindowInsetsAnimationCompat for smooth IME animation.**

The `WindowInsetsAnimationCompat` API allows the content to animate in sync with the keyboard slide rather than jumping. This is optional for the first ship but worthwhile given SteleKit's block-editing-heavy UX.

**Step 4 — KMP boundary.**

The manifest change is Android-only. The `imePadding()` modifier is available in Compose Multiplatform `commonMain` (it is a no-op on Desktop/iOS where there is no soft keyboard). Applying it in `commonMain` is safe, but only do so inside components that are used in mobile editing contexts — not on the root layout which would affect Desktop.

### Verification

1. Open a page, tap a block to focus it. Keyboard must appear and the focused block must scroll into view above the keyboard.
2. Type several lines until content is below the keyboard fold — it should auto-scroll.
3. On Android 11 emulator (API 30) and Android 14 physical device, repeat test.
4. Verify in `MainLayout.kt` that `Modifier.statusBarsPadding()` is removed (it is the other half of the edge-to-edge migration).
5. Run `./gradlew jvmTest` — the `MobileLayoutTest` must not regress.

---

## Pitfall 2 — Scaffold Inset Double-Consumption

### Classification: BLOCKER

### Description

When a `Scaffold` is configured with `contentWindowInsets` (or uses the default `ScaffoldDefaults.contentWindowInsets`), it automatically reserves space for status bar and navigation bar insets in its content padding. If any composable *inside* the Scaffold's content slot then also applies `statusBarsPadding()`, `navigationBarsPadding()`, `safeContentPadding()`, or `safeDrawingPadding()`, those insets are applied a second time. The result is a double gap — an extra blank strip at the top equal to the status bar height and/or an extra blank strip at the bottom equal to the navigation bar height.

In the current SteleKit codebase, `MainLayout.kt` line 38 already applies:

```kotlin
.then(if (isMobile) Modifier.statusBarsPadding() else Modifier)
```

and line 110 applies:

```kotlin
Spacer(modifier = Modifier.navigationBarsPadding())
```

When `Scaffold` replaces the root `Column` in `MainLayout` as part of F2, if `contentWindowInsets` is left at the default (which includes both status bar and nav bar insets), these existing modifiers will double-pad. The status bar will have twice the expected gap at the top, and there will be a double-height spacer at the bottom.

### Root Cause

Compose's inset system is consumption-based: a modifier "consumes" an inset by applying it as padding, removing it from the available inset pool for children. `Scaffold` consumes insets at the slot boundary — its `contentPadding` parameter already includes the inset-derived padding. Any child that re-applies the same inset type re-introduces already-consumed space.

The subtlety: `Scaffold` does not consume insets inside the `content` lambda's `PaddingValues` by default for all inset types — it depends on the `contentWindowInsets` argument. The default is `ScaffoldDefaults.contentWindowInsets` which is `WindowInsets.safeDrawing`. If you pass `WindowInsets.Zero`, Scaffold consumes nothing and all children must manage insets themselves.

### Fix / Mitigation

**Pattern A — Scaffold owns all insets (recommended for F1+F2).**

Configure `Scaffold` with `contentWindowInsets = WindowInsets.safeDrawing` (the default) and strip ALL manual inset modifiers from children:

```kotlin
Scaffold(
    topBar = { /* TopBar — no statusBarsPadding needed, Scaffold handles it */ },
    bottomBar = { NavigationBar(...) }, // NavigationBar handles its own insets
    contentWindowInsets = WindowInsets.safeDrawing
) { innerPadding ->
    // Pass innerPadding into the content — DO NOT add extra padding modifiers
    ContentHost(modifier = Modifier.padding(innerPadding))
}
```

Remove from `MainLayout`:
- `Modifier.statusBarsPadding()` (line 38)
- `Spacer(modifier = Modifier.navigationBarsPadding())` (line 110)

**Pattern B — Scaffold owns nothing, children own everything.**

Pass `contentWindowInsets = WindowInsets.Zero` to `Scaffold` and manage all insets manually inside children. This is more fragile across the KMP surface.

**Pattern A is strongly preferred.** It gives Scaffold one source of truth for insets.

**Detection heuristic for future regressions.**

Any composable inside a Scaffold content slot that calls any of these is a candidate for double-consumption:
- `statusBarsPadding()`
- `navigationBarsPadding()`
- `systemBarsPadding()`
- `safeContentPadding()`
- `safeDrawingPadding()`
- `WindowInsets.systemBars.asPaddingValues()`

A Grep across `commonMain` for these patterns, cross-referenced against whether they are inside a Scaffold content slot, is the audit step.

**`LazyColumn` + `contentPadding` secondary risk.**

`LazyColumn` has its own `contentPadding` parameter. If `navigationBarsPadding()` is applied on the `LazyColumn` itself, and the `Scaffold` already accounts for nav bar height in `innerPadding`, the bottom of the list will have extra space. Correct pattern:

```kotlin
// WRONG — double pads if Scaffold already includes nav bar
LazyColumn(
    modifier = Modifier.navigationBarsPadding()
)

// RIGHT — consume the Scaffold-provided bottom padding only
LazyColumn(
    contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
)
```

### Verification

1. On a phone with a gesture navigation bar (no 3-button bar), open the app. The content area must reach the visual bottom of the screen with no extra white/colored strip below the last list item.
2. On a phone with 3-button navigation, same test — a single nav bar height gap at the bottom, not double.
3. On a notch/cutout device, the TopBar must not have extra top padding beyond the status bar height.
4. Pixel-compare with a ruler tool or via Layout Inspector in Android Studio — inset values from `WindowInsetsCompat` must each appear exactly once in the layout tree.
5. Add a Compose layout inspector check: `LocalWindowInsets.current` values divided by density must match the visible gap on screen.

---

## Pitfall 3 — Roborazzi Screenshot Baseline Invalidation

### Classification: Known Risk

### Description

All layout changes from F1 (bottom nav restructuring `MainLayout`) and F2 (edge-to-edge, status bar padding removal) will alter the pixel output of every existing Roborazzi screenshot test. The current tests are:

- `DesktopScreenshotTest` — 2 tests (light/dark), renders `MainLayout` with full sidebar
- `MobileScreenshotTest` — 2 tests (light/dark), renders `JournalsView` only
- `JournalsViewScreenshotTest` — 2 tests (markdown rendering + live graph)

After F1/F2, none of these will match their prior output because `MainLayout`'s structure is fundamentally different (bottom bar present, no `statusBarsPadding`, different content area height).

**Additional subtlety for SteleKit specifically**: The current screenshot tests write to `build/outputs/roborazzi/*.png`. These are under `build/` which is gitignored. There are no committed baseline `.png` files in the repository (confirmed by glob search — no `.png` files in `src/`). This means Roborazzi is currently operating in **record mode only** — tests are recording images but not diffing them against a baseline. There is no active regression guard.

This is both the good news (F1/F2 changes cannot fail a baseline diff because none exists) and the bad news (screenshots are currently providing zero regression protection for layout correctness).

### Root Cause

Roborazzi requires a committed baseline directory to do comparison. Without a baseline, every `captureRoboImage()` call just writes a file — it does not assert anything. The Roborazzi Gradle plugin task `recordRoborazzi` writes baselines to `src/test/roborazzi/` by default; `verifyRoborazzi` compares against them.

### Fix / Mitigation

**Phase 1 — Establish a baseline before starting F1/F2.**

Before making any layout changes, run:

```bash
./gradlew recordRoborazziJvm
```

This writes golden baseline images to `src/jvmTest/roborazzi/` (the plugin default for Roborazzi 1.59.0 in KMP `jvmTest`). Commit these files. The path is configured by `roborazzi.outputDir` in `roborazzi.properties` or defaults to `src/test/roborazzi` — check the plugin config in `kmp/build.gradle.kts` to verify the exact directory.

**Phase 2 — After each F1/F2 sub-task, update baselines intentionally.**

When layout changes are complete and visually verified on a real device, re-record:

```bash
./gradlew recordRoborazziJvm
```

Commit the updated images with a commit message that names the specific change:

```
test(screenshots): update Roborazzi baselines after F1 bottom nav + F2 edge-to-edge
```

Do NOT use `verifyRoborazzi` between record sessions for components that are actively being changed. Reserve `verifyRoborazzi` for stable components (e.g., run it on `SearchDialog`, `TopBar` in isolation tests) while `MainLayout` is in flux.

**Phase 3 — After overhaul complete, lock baselines and add CI verification.**

Once F1–F6 are complete:
1. Record final baselines.
2. In CI (or a local `make verify` target), run `./gradlew verifyRoborazziJvm`.
3. Fail the check if any diff exceeds tolerance (Roborazzi supports pixel-level diff thresholds).

**Additional recommendation — add a mobile `MainLayout` screenshot test.**

The current `MobileScreenshotTest` only renders `JournalsView` in isolation — it does not render `MainLayout` with the bottom nav bar. After F1, add a test that renders the full mobile layout (bottom nav + content area) so the integrated layout is regression-protected.

**Test count impact.**

Expect all 6 existing screenshot tests to have changed baselines after F1+F2. 4 tests (DesktopScreenshotTest × 2, MobileScreenshotTest × 2) are particularly affected because they render `MainLayout` or use the `statusBarsPadding` path.

### Verification

1. `./gradlew recordRoborazziJvm` completes without error.
2. Generated images exist in the expected source directory (not `build/`).
3. Images are committed to git.
4. `./gradlew verifyRoborazziJvm` passes on a clean checkout with no layout changes.
5. Making a deliberate 10px padding change causes `verifyRoborazziJvm` to fail — confirming the guard is active.

---

## Pitfall 4 — AnimatedContent + Predictive Back Gesture Double-Animation Flicker

### Classification: Known Risk

### Description

When replacing `Crossfade` with directional `AnimatedContent` in `ScreenRouter`, there is a known conflict with the Android 14+ predictive back gesture system. The issue: when the user initiates a back swipe gesture, Android simultaneously:

1. Starts the system-level predictive back preview (shrinks and shifts the current screen to preview the destination behind it).
2. Triggers the `BackHandler` or `OnBackPressedDispatcher` callback when the gesture commits.

If `AnimatedContent` has an `exitTransition` configured (e.g., slide-out-to-right for back navigation), both the system's predictive back shrink animation AND the `AnimatedContent` exit animation play simultaneously on gesture commit. The user sees the screen first shrink (system preview) and then slide (app animation) — a double-animation that looks broken.

This is specifically observed in Compose Navigation (`NavHost`), which has explicit predictive back integration. A hand-rolled `AnimatedContent` + `BackHandler` does not get that integration automatically.

### Root Cause

Android 14 introduced the `PredictiveBackGestureHandler` system. Compose Navigation 2.7+ has a built-in `predictiveBackTransition` integration that intercepts the back gesture before it triggers a normal pop, runs the system preview, and suppresses the regular `AnimatedContent` exit animation when the gesture commits by transition. A custom `AnimatedContent`-based router has none of this wiring.

### Fix / Mitigation

**Option A — Use `SeekableTransitionState` with `PredictiveBackHandler` (correct, complex).**

Android and Compose expose `PredictiveBackHandler` (in `androidx.activity:activity-compose`) which provides a `progress` flow as the user drags the gesture. Combined with `SeekableTransitionState` introduced in Compose Animation 1.7, the app can drive the exit animation progress manually in sync with the gesture:

```kotlin
val transition = rememberTransition(transitionState)
val backProgress = remember { mutableStateOf(0f) }

PredictiveBackHandler { progress ->
    progress.collect { backEvent ->
        backProgress.value = backEvent.progress
    }
    // Gesture committed — navigate back
    viewModel.navigateBack()
}
```

The `SeekableTransitionState` seeks to the exit state proportionally, so when the gesture commits the animation is already partially complete — no double-play.

**Option B — Disable exit animation when back gesture triggered (pragmatic for v1).**

A simpler approach for the first ship: detect that the back gesture triggered the screen change (by tracking the navigation direction), and use `EnterTransition.None` / `ExitTransition.None` when direction is "back". The system's predictive back preview handles the visual, and the `AnimatedContent` does not fight it. Forward navigation (user taps a page link) still gets the full slide animation.

```kotlin
AnimatedContent(
    targetState = screen,
    transitionSpec = {
        if (navDirection == NavDirection.BACK) {
            // Let system predictive back handle it
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
        }
    }
) { ... }
```

**Recommended approach for SteleKit F3**: Use Option B for the initial ship (it is lower complexity and avoids the `SeekableTransitionState` machinery). Document Option A as a follow-up for a polished predictive back animation. The requirements specify predictive back *gesture opt-in* — the main requirement is `enableOnBackInvokedCallback="true"` in the manifest and `BackHandler` at the right scope, not a pixel-perfect animation sync.

**KMP note**: `PredictiveBackHandler` is `androidMain` only. The `AnimatedContent` spec must be in `commonMain` with the `transitionSpec` receiving its direction signal from an `androidMain`-provided flag or an `expect/actual` abstraction.

### Verification

1. With predictive back enabled (`enableOnBackInvokedCallback="true"` in manifest), navigate to a page, then begin a back swipe gesture slowly on Android 14+.
2. The system preview animation (screen shrinks behind) must appear with no simultaneous slide animation from `AnimatedContent`.
3. Release the gesture — the back navigation completes cleanly without a second animation.
4. Abort the gesture (swipe back to center) — the screen returns to full size, no visual artifact.
5. Navigate forward to a page (tap a link) — the slide-in `AnimatedContent` transition must play correctly.

---

## Pitfall 5 — Dynamic Color Contrast Regressions with `StelekitExtendedColors`

### Classification: Known Risk

### Description

Material You `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` generates a `ColorScheme` from the user's current wallpaper. The generated colors are guaranteed to meet WCAG AA contrast *within* the Material 3 color role system (e.g., `primary` on `onPrimary`, `surface` on `onSurface`). However, `StelekitExtendedColors` defines four custom colors outside the M3 role system:

```kotlin
data class StelekitExtendedColors(
    val bullet: Color,
    val indentGuide: Color,
    val sidebarBackground: Color,
    val blockRefBackground: Color
)
```

These are currently derived from SteleKit's static stone/parchment palette. When `DYNAMIC` mode is enabled, `MaterialTheme.colorScheme.background` and `MaterialTheme.colorScheme.surface` change to wallpaper-derived values, but `StelekitExtendedColors` is still initialized from the *static* `LightExtendedColors` or `DarkExtendedColors` instance. The result:

- `bullet` (`PaleStone = Color(0xFFB8AFA0)`) rendered on a dynamic `background` that could be any wallpaper-derived hue — could be near-invisible if the wallpaper is warm beige.
- `indentGuide` (`PaleStone.copy(alpha = 0.1f)`) is already very low contrast by design; it becomes near-invisible on certain dynamic backgrounds.
- `blockRefBackground` (`DeepPatina.copy(alpha = 0.08f)`) — a tinted highlight — may be invisible on dynamic schemes that shift toward teal/cyan.
- `sidebarBackground` (`LimestoneSurface = Color(0xFFEDE8DC)`) — a warm grey — will conflict visually with dynamic surface colors.

Additionally, the static colors `bullet` and `indentGuide` use warm stone hues. A user with a cool-blue or purple wallpaper will get a dynamic scheme in cool blues, but the bullets and indent guides remain warm stone — a visible palette clash, not just a contrast issue.

### Root Cause

`StelekitExtendedColors` is not linked to any M3 color role — it is a parallel palette maintained independently. There is no dynamic derivation path for it. When the M3 scheme changes dynamically, these colors become orphans.

### Fix / Mitigation

**Strategy: Derive extended colors from dynamic scheme roles rather than static hues.**

When `themeMode == DYNAMIC`, compute `StelekitExtendedColors` from the generated `ColorScheme` rather than the static stone palette:

```kotlin
val colorScheme = when (themeMode) {
    DYNAMIC -> if (isDark) dynamicDarkColorScheme(context)
               else dynamicLightColorScheme(context)
    // ... existing cases
}

val extendedColors = when (themeMode) {
    DYNAMIC -> StelekitExtendedColors(
        // Derive from scheme roles that are contrast-guaranteed
        bullet = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        indentGuide = colorScheme.outlineVariant.copy(alpha = 0.4f),
        sidebarBackground = colorScheme.surfaceVariant,
        blockRefBackground = colorScheme.primaryContainer.copy(alpha = 0.3f)
    )
    // ... existing static cases unchanged
}
```

This keeps static themes (Stone, Parchment, Dark) using their hand-tuned stone palette, and only DYNAMIC mode derives from the generated scheme. M3 `onSurfaceVariant` / `outlineVariant` / `surfaceVariant` roles are designed for secondary UI elements and have guaranteed contrast relationships with `surface` and `background`.

**Contrast audit for the static themes** (separate concern, lower priority):

The `indentGuide` at alpha 0.1 is intentionally very subtle. For WCAG 2.1 AA, decorative elements that convey no information are exempt from contrast requirements. Indent guides are decorative — they do not need to pass AA. Bullets, however, convey structure (they are the clickable expand/collapse target in Logseq). `PaleStone` (`#B8AFA0`) on `ParchmentBackground` (`#F5F0E8`) has a contrast ratio of approximately 2.3:1, which fails WCAG AA (requires 3:1 for large UI elements or 4.5:1 for text). This is a pre-existing issue, not introduced by dynamic color, but should be tracked.

**Settings UI guard**: The "Dynamic (Material You)" option should only be shown on Android 12+ (API 31+). This is already specified in F6 requirements. If it appears and is selected on older API, `dynamicLightColorScheme` will throw — wrap in `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` check, which should be inside `androidMain`.

### Verification

1. On Android 12+ device, apply a highly saturated wallpaper (red, blue, green) — each.
2. Switch SteleKit to Dynamic theme. Check:
   - Bullets are visible against the content background.
   - Indent guides are visible (or acceptably subtle).
   - Sidebar background does not clash with the content area background color.
   - Block reference highlights are visible but not garish.
3. Use Android Accessibility Scanner or the built-in Accessibility Checker in Layout Inspector to audit contrast ratios on each wallpaper.
4. Verify that on a monochromatic (black-and-white) wallpaper the extended colors still have sufficient differentiation.
5. Verify that selecting DYNAMIC on Android 11 or below does not crash (the option should be hidden, but add a graceful fallback).

---

## Pitfall 6 — NavigationBar + ModalBottomSheet Overlap

### Classification: Known Risk

### Description

When a `ModalBottomSheet` is displayed while the `NavigationBar` is visible at the bottom of the screen, the sheet can appear to overlap or underlap the nav bar depending on how insets are configured. There are two manifestations:

**Manifestation A — Sheet appears behind the nav bar.** If the `ModalBottomSheet` is placed inside the Scaffold content area (above the bottom bar slot), the nav bar overlaps the drag handle and top edge of the sheet. The user cannot see or interact with the top portion of the sheet.

**Manifestation B — Sheet has double bottom gap.** If the sheet correctly floats above the nav bar but also applies `navigationBarsPadding()` internally (the default for `ModalBottomSheet`), there is a gap below the sheet's content equal to the nav bar height before the visual sheet background ends. On gesture-nav devices where the nav bar is transparent, this appears as an orphaned background strip.

### Root Cause

`ModalBottomSheet` (Material 3, Compose) is a full-screen overlay implemented as a `Popup`. It is rendered in a separate composition window above the Activity window — it correctly floats above the `Scaffold`'s `bottomBar` slot. However, its default `windowInsets` parameter is `BottomSheetDefaults.windowInsets` which is `WindowInsets.systemBars`. This means the sheet's internal content padding already includes the navigation bar height. If the `Scaffold` also handles nav bar insets in `contentWindowInsets`, the insets are consumed at the Scaffold level but `ModalBottomSheet` re-reads them from the window independently (because it is a separate composition window).

### Fix / Mitigation

**Correct pattern for NavigationBar + ModalBottomSheet coexistence.**

`ModalBottomSheet` should be hosted outside the `Scaffold`'s content slot — it must be a sibling to the `Scaffold`, not a child:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = { NavigationBar(...) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        ContentHost(modifier = Modifier.padding(innerPadding))
    }

    // ModalBottomSheet as sibling — outside Scaffold content
    if (bottomSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { bottomSheetVisible = false },
            // windowInsets controls how much the sheet respects system bars
            windowInsets = WindowInsets.navigationBars
        ) {
            SheetContent()
        }
    }
}
```

The `windowInsets = WindowInsets.navigationBars` argument tells the sheet to reserve space for the nav bar at its bottom — ensuring the drag handle clears the nav bar and the content does not underlap.

**For SteleKit F1 specifically**: If the sidebar or any search/settings overlay is converted to a `ModalBottomSheet` on mobile (not required by current spec, but a natural future change), this pattern applies. The current spec uses `ModalBottomSheet`-like overlays only for `CommandPalette`, `SearchDialog`, and `SettingsDialog` — these are `Dialog`-based, not `BottomSheet`-based. The overlap issue does not affect dialogs (they render above everything). Monitor if any F1/F2 work introduces a `ModalBottomSheet`.

**Drag-to-dismiss + NavigationBar interaction**: If gesture navigation is active (nav bar is the gesture zone at the bottom), the `ModalBottomSheet` drag handle area and the system gesture zone overlap in the bottom ~36dp of the sheet drag handle area. Android resolves this by prioritizing the sheet drag when the user starts the gesture inside the sheet boundary. No special handling needed, but this interaction is worth manual testing on gesture-nav devices.

### Verification

1. Open a `ModalBottomSheet` while the `NavigationBar` is visible.
2. Confirm the sheet's drag handle appears fully above the nav bar — not clipped by it.
3. Confirm no double-height gap below the sheet content before the sheet background terminates.
4. On a gesture-nav device (pill nav bar), confirm dragging down on the sheet handle dismisses the sheet, not the system back gesture.
5. On a 3-button nav device, confirm the sheet dismisses cleanly when nav bar back button is pressed.
6. Confirm that after the sheet is dismissed, the nav bar state is unchanged (no visual artifacts).

---

## Cross-Cutting Implementation Notes

### Order of Operations for F1 + F2

The dependency chain matters for avoiding the above pitfalls in the wrong order:

1. **Remove `adjustResize` from manifest** (Pitfall 1 fix) — do this before any edge-to-edge code lands.
2. **Add `enableEdgeToEdge()` and `setDecorFitsSystemWindows(window, false)`** in `MainActivity`.
3. **Restructure `MainLayout` to use `Scaffold`** with `contentWindowInsets = WindowInsets.safeDrawing`.
4. **Strip `statusBarsPadding()` and `navigationBarsPadding()` from `MainLayout`** (Pitfall 2 fix).
5. **Add `imePadding()` at the scroll container level** in the block editor path (Pitfall 1 secondary fix).
6. **Record new Roborazzi baselines** (Pitfall 3 process step).

Doing steps 2–4 before step 1 will cause the keyboard to stop working during development. Doing step 6 before steps 2–5 will just record baselines that immediately become invalid.

### KMP Surface Boundaries

| Concern | Where to implement |
|---|---|
| `adjustResize` removal | `AndroidManifest.xml` (Android only) |
| `enableEdgeToEdge()` | `androidMain/MainActivity.kt` |
| `setDecorFitsSystemWindows` | `androidMain/MainActivity.kt` |
| Scaffold inset pattern | `commonMain/MainLayout.kt` (Scaffold is available in CMP) |
| `imePadding()` modifier | `commonMain` — safe no-op on Desktop |
| `dynamicColorScheme` derivation | `androidMain` — pass computed `ColorScheme` up via `expect/actual` |
| `StelekitExtendedColors` dynamic derivation | `commonMain/Theme.kt` — receives the color scheme from above |
| `PredictiveBackHandler` | `androidMain` — no CMP equivalent |
| `enableOnBackInvokedCallback` | `AndroidManifest.xml` (Android only) |

### Pre-existing Issues Surfaced by This Research

Two pre-existing problems were identified that are not caused by the overhaul but will become more visible:

1. **Bullet contrast ratio**: `PaleStone` on `ParchmentBackground` is ~2.3:1. Bullets are interactive (expand/collapse) and likely should meet 3:1. Track as a follow-up accessibility item.
2. **No committed Roborazzi baselines**: Screenshot tests currently write to `build/` and are not committed. They provide zero regression protection. Establish baselines before or during F1/F2 work regardless of whether layout changes are happening.
