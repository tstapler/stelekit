# ADR-002: Use Jetpack Glance 1.1.1 for the Home Screen Widget

**Status**: Proposed
**Date**: 2026-04-24
**Project**: android-features-integration

## Context

SteleKit needs a home screen widget for quick note capture. Android provides two APIs for building app widgets:

- **Jetpack Glance** (`androidx.glance:glance-appwidget`) — a Compose-based abstraction over `RemoteViews`. Developers write Glance composables; the framework translates them to `RemoteViews` at render time. Stable since 1.1.0 (2024); 1.1.1 patches a protobuf security vulnerability.
- **Raw `RemoteViews` + `AppWidgetProvider`** — the original XML-layout-based API, supported since API 1. Full control over layout and update cycles; no Compose. No dedicated testing library.

The project already uses Jetpack Compose (via Compose Multiplatform) throughout `commonMain` and `androidMain`. The minimum SDK is 24.

A significant constraint was discovered in research: Glance uses a **session-based locking mechanism that holds a lock for 45–50 seconds after each widget update**. During this window, new update requests are silently dropped. This effectively rules out any real-time or high-frequency data display in the widget.

## Decision

We decided to use **Jetpack Glance 1.1.1** (`androidx.glance:glance-appwidget:1.1.1`) for the home screen widget, implementing it as a **capture-only widget** (no live data display) to avoid the session-lock constraint.

The widget presents a single button (or small set of action buttons at larger sizes) that launches `CaptureActivity` — it does not attempt to display note content or refresh data at any interval.

## Alternatives Considered

- **Raw `RemoteViews` + `AppWidgetProvider`**: Rejected. High boilerplate for interactive widgets (click handling, text input), no dedicated testing library, and no Compose alignment with the rest of the codebase. For a single-developer project, maintenance cost is too high relative to Glance.

- **Glance with live data display**: Rejected as a widget configuration (not as a library). The 45–50 s session lock means any widget that polls or reacts to graph changes will produce stale or silently-failed updates. A capture-only widget sidesteps this entirely and is a better product decision — it mirrors the "Drafts" philosophy (capture first, review in-app).

- **Glance 1.2.0-rc01**: Rejected for now. The RC targets Compose 1.8+; SteleKit is on Compose Multiplatform 1.7.3 (Jetpack Compose BOM ~1.7.x). Upgrading Glance beyond 1.1.x must be synchronized with a Compose Multiplatform version bump.

## Rationale

Glance aligns with SteleKit's Compose-first posture and provides a dedicated testing artifact (`androidx.glance:glance-appwidget-testing:1.1.1`) with `runGlanceAppWidgetUnitTest { }` blocks and a matcher DSL — a material advantage over raw `RemoteViews` for a single developer maintaining tests. The session-lock limitation does not affect a capture-only widget at all, turning a potential liability into a non-issue.

Competitively, Obsidian's January 2026 widget release (v1.11) is also navigation/shortcut-only (no inline capture). A Glance widget that raises a keyboard overlay immediately on tap provides a genuine UX advantage.

## Consequences

**Positive:**
- No new synchronization concerns: the widget never reads from the graph, so there is no DataStore or SQLite access in the widget process itself.
- Testing is first-class: `runGlanceAppWidgetUnitTest` + Robolectric covers structural correctness.
- `SizeMode.Responsive` with 2–3 breakpoints gives graceful degradation (1×1 single button → 2×1 labeled buttons) with manageable testing surface.
- No `minSdk` change required — Glance 1.1.x supports API 23+.

**Negative / Risks:**
- Launcher variance: `SizeMode.Responsive` rendering differs across AOSP, Samsung One UI, and MIUI. Real-device testing is mandatory; unit tests do not catch launcher-specific rendering bugs.
- `RemoteInput` (inline text field inside the widget, without opening an Activity) has inconsistent support on Samsung One UI. The widget button should launch `CaptureActivity` rather than attempting inline keyboard input.
- Glance composables are a subset of Jetpack Compose — not all standard composables are available. `LazyColumn` maps to `ListView`; all `RemoteViews` collection limits apply.
- The 45–50 s session lock is still present; if a display widget is added in a future phase, this constraint must be revisited and a WorkManager-based update strategy adopted.

**Follow-up work:**
- Add to `kmp/build.gradle.kts` androidMain dependencies: `androidx.glance:glance-appwidget:1.1.1`, `androidx.glance:glance-material3:1.1.1`. Add to test dependencies: `androidx.glance:glance-appwidget-testing:1.1.1`.
- Create `res/xml/capture_widget_info.xml` (appwidget-provider descriptor) with `minWidth`, `minHeight`, `updatePeriodMillis = 0`, and `resizeMode`.
- Implement `CaptureWidget : GlanceAppWidget` with `SizeMode.Responsive` and 2–3 size breakpoints.
- Implement `CaptureWidgetReceiver : GlanceAppWidgetReceiver` registered in `AndroidManifest.xml`.
- Test on Pixel (AOSP) and Samsung Galaxy (One UI) before releasing.

## Related

- Requirements: `project_plans/android-features-integration/requirements.md`
- Research synthesis: `project_plans/android-features-integration/research/synthesis.md`
- Supersedes: (none)
- Related ADRs: ADR-001 (Application singleton), ADR-003 (shared CaptureActivity)
