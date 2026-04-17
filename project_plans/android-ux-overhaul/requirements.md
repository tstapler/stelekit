# Requirements: SteleKit Android UX Overhaul

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-12
**Project directory**: `project_plans/android-ux-overhaul/`

---

## Problem Statement

SteleKit is a KMP outliner/note-taking app targeting Android, Desktop (JVM), iOS, and Web. The Android UI was designed desktop-first and then adapted for mobile — the result is a set of structural UX problems that violate core Android platform conventions:

1. Primary navigation is hidden behind a hamburger button (sidebar overlay pattern) — the least thumb-accessible zone on a phone, requiring 2+ taps to reach any destination.
2. The app does not draw edge-to-edge: content stops at the status bar and navigation bar boundaries rather than bleeding under them, creating a boxed visual that feels dated on Android 14+.
3. Touch targets on the mobile TopBar are 36–40dp — below the Android-required 48dp minimum — causing mis-taps.
4. Predictive Back Gesture opt-in is missing from `AndroidManifest.xml`, meaning Android 14+ users cannot preview their navigation destination before completing a back swipe.
5. Screen transitions use a plain `Crossfade` (opacity only) rather than directional slide+fade as required by Material 3.
6. Several interactive elements lack TalkBack accessibility descriptions, and the app is not WCAG 2.1 AA compliant — a legal requirement for EU distribution under the European Accessibility Act (EAA, in force June 2025).
7. The theme system uses hardcoded static color palettes with no support for Material You dynamic color on Android 12+.

**Who has this problem:** Any Android user of SteleKit. The UX gaps are most painful on phones (vs. tablets), and the accessibility gaps affect all users relying on TalkBack.

---

## Success Criteria

- All primary Android destinations reachable in **1 tap** from any screen.
- App draws fully **edge-to-edge** on Android 14+ — no hard background boundary at status or navigation bars.
- All interactive elements have touch targets of **48×48dp minimum** on Android.
- Predictive Back Gesture is **enabled and functional** — back swipe previews the destination.
- Screen-to-screen navigation uses **directional slide+fade** transitions, respecting back vs. forward direction.
- The app passes a **TalkBack navigation test** on the 5 primary flows: graph open, page navigation, block editing, search, settings.
- **WCAG 2.1 AA** contrast and semantic compliance across all screens.
- Android 12+ users can opt into **Material You dynamic color** via Settings.

---

## Scope

### Must Have (MoSCoW)

**F1 — Android Bottom Navigation**
- Replace hamburger+sidebar-overlay with MD3 `NavigationBar` for 4 primary destinations: Journals, All Pages, Flashcards, Notifications.
- Demote Logs and Performance screens to Settings → Developer Tools.
- Repurpose the sidebar to graph switching + favorites/recents only (not primary nav).
- KMP split: `commonMain` keeps `Screen` sealed class and sidebar content; `androidMain` owns `NavigationBar` placement via platform-specific layout.

**F2 — Edge-to-Edge Layout**
- `MainActivity`: add `WindowCompat.setDecorFitsSystemWindows(window, false)` + `enableEdgeToEdge()`.
- Remove `Modifier.statusBarsPadding()` from `MainLayout` — replace with inset-aware `Scaffold` using `WindowInsets.safeContent`.
- Fix `NotificationOverlay` from hardcoded `padding(bottom = 32.dp)` to `WindowInsets.navigationBars`-aware padding.
- Fix `windowSoftInputMode` conflict with edge-to-edge.
- Must ship together with F1 (both restructure `MainLayout`).

**F3 — Predictive Back + Screen Transition Animations**
- Add `android:enableOnBackInvokedCallback="true"` to `AndroidManifest.xml`.
- Replace `Crossfade` in `ScreenRouter` with `AnimatedContent` using directional slide+fade keyed on navigation direction (`historyIndex` delta).
- Add `BackHandler` in screens that intercept back (editing mode, open dialogs, selection mode).

**F4 — Touch Target Audit**
- `TopBar.kt` mobile path: all 5 `IconButton` calls at `size(40.dp)` → `size(48.dp)`.
- `Sidebar.kt`: close button + `GraphItem` delete button → `size(48.dp)`.
- Visual regression test pass after change.

**F5 — Accessibility Pass**
- `GraphSwitcher`: add `semantics { role = Role.Button; stateDescription = "expanded/collapsed" }`.
- `MobileBlockToolbar`: replace `Text("B")` / `Text("I")` etc. formatting buttons with proper icons + `contentDescription` values ("Bold", "Italic", "Strikethrough", "Code", "Highlight", "Insert link").
- `NotificationOverlay`: inset-aware positioning (done as part of F2).
- Manual TalkBack test of 5 primary flows; document failures.
- Accessibility Scanner automated run; fix all critical findings.

**F6 — Material You Dynamic Color**
- Add `DYNAMIC` option to `StelekitThemeMode` enum.
- In `StelekitTheme`: `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` guard → `dynamicLightColorScheme` / `dynamicDarkColorScheme` fallback to existing static schemes.
- Settings UI: show "Dynamic (Material You)" only on Android 12+, hidden on older API and Desktop/iOS/Web.
- Keep Stone/Parchment/Dark themes as-is.

### Out of Scope

- iOS-specific navigation overhaul (tab bar) — separate project.
- Desktop layout changes — current sidebar-first approach is correct for desktop.
- New screen content (e.g., Flashcards implementation, Right Sidebar content).
- Web platform UX.
- Plugin system or multi-graph switching UX (separate concern from navigation structure).
- Animations below screen-level (block-level, inline).

---

## Constraints

- **Tech stack**: Kotlin Multiplatform (Compose Multiplatform); Jetpack Compose for Android; `commonMain` shared UI code; `androidMain` for Android-specific APIs. All UI changes must not break Desktop, iOS, or Web targets.
- **KMP constraint**: `WindowCompat`, `dynamicColorScheme`, `HapticFeedbackType`, `BackHandler` with Predictive Back — all `androidMain` only, wrapped in `expect/actual` where they affect `commonMain` interfaces.
- **No breaking ViewModel changes**: `AppState`, `Screen` sealed class, and `StelekitViewModel` should be modified minimally — navigation restructure happens at the layout/composable layer, not the state layer.
- **Screenshot tests**: `jvmTest` uses Roborazzi for screenshot regression tests — layout changes will require baseline updates.
- **Timeline**: Solo developer, no fixed deadline. Features can be sequenced and shipped incrementally.
- **Dependencies**: F2 (edge-to-edge) must ship together with F1 (bottom nav) — both restructure `MainLayout`. F3 (predictive back) depends on F2 being complete. F4, F5, F6 are independent and can ship in any order.

---

## Context

### Existing Work

- A comprehensive Android UX audit was completed (2026-04-12) against Material Design 3 / Material You guidelines using the `android-ux-design` skill.
- The audit identified all 6 issues listed above with specific file paths and line numbers.
- No prior attempts at Android-specific layout work were found; the current mobile path is a direct adaptation of the desktop layout.
- The `isMobile = maxWidth < 600.dp` breakpoint is the existing mobile detection — this is the correct signal for Android phone vs. tablet/desktop.

### Audit Findings Summary (filed findings, not decisions)

| Finding | Severity | File | Line |
|---|---|---|---|
| No bottom nav — sidebar overlay for all destinations | Critical | `App.kt` / `Sidebar.kt` | — |
| `statusBarsPadding()` prevents edge-to-edge | Critical | `MainLayout.kt` | 38 |
| No `setDecorFitsSystemWindows(false)` | Critical | `androidMain` | — |
| No `enableOnBackInvokedCallback` | High | `AndroidManifest.xml` | — |
| TopBar mobile IconButtons at 40dp | High | `TopBar.kt` | 57,67,76,101,104,111 |
| Sidebar buttons at 36dp | High | `Sidebar.kt` | 79, 346 |
| `GraphSwitcher` clickable with no semantics role | Medium | `Sidebar.kt` | 193 |
| Toolbar format buttons (`Text("B")`) have no a11y description | Medium | `MobileBlockToolbar.kt` | 82–111 |
| `NotificationOverlay` uses magic `32.dp` padding | Medium | `App.kt` | 487 |
| No dynamic color (Material You) | Medium | `Theme.kt` | — |
| `Crossfade` only (no directional transitions) | Low | `App.kt` | 398 |

### Stakeholders

- **Tyler Stapler** (sole developer) — product owner and implementer.
- **End users**: Android phone users of SteleKit; EU users subject to EAA accessibility requirements.

---

## Research Dimensions Needed

- [ ] Stack — KMP/Compose Multiplatform platform-specific layout patterns: `expect/actual` for navigation bars, `WindowCompat` integration from `androidMain`, Compose Multiplatform NavigationBar on Android vs. NavigationRail on Desktop
- [ ] Features — Survey how other KMP note-taking or outliner apps (Obsidian mobile, Notion mobile, Bear) handle Android bottom navigation, edge-to-edge, and mobile toolbar patterns
- [ ] Architecture — Design patterns for Android-specific layout composition within KMP (where to split `commonMain` vs `androidMain`; how to route around `Scaffold` limitations in Compose Multiplatform)
- [ ] Pitfalls — Known failure modes: edge-to-edge + IME interactions, `Scaffold` inset double-consumption, Roborazzi screenshot baseline invalidation, `AnimatedContent` back-gesture flicker, dynamic color contrast regressions on custom palettes
