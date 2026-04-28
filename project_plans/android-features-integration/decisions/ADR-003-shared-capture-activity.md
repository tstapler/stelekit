# ADR-003: Use a Shared CaptureActivity as the Unified Entry Point for All Three Features

**Status**: Proposed
**Date**: 2026-04-24
**Project**: android-features-integration

## Context

Three Android entry points need to capture a note from the user: the home screen widget button, the Quick Settings Tile tap, and the share target (receiving `ACTION_SEND` intents). Each entry point has different trigger mechanisms but an identical end-state requirement: present a text input to the user, accept a note, write it to the active graph, and return focus to whatever the user was doing.

Two design approaches were considered:

- **Separate UI per entry point** — Each component (widget, tile, share) has its own capture UI: the widget uses Glance's `RemoteInput` (inline widget keyboard), the tile opens a `Dialog` via `TileService.showDialog()`, and the share target has its own `Activity`.
- **Shared `CaptureActivity`** — A single lightweight translucent `Activity` serves all three entry points. The widget button launches it, the tile calls `startActivityAndCollapse(pendingIntent)` to it, and the share target's manifest `intent-filter` points to it.

## Decision

We decided to implement a single **`CaptureActivity`** — a lightweight, translucent `Activity` with `android:windowSoftInputMode="stateAlwaysVisible"` — that serves as the unified capture UI for all three Android entry points.

- **Widget**: The Glance widget button uses `actionStartActivity<CaptureActivity>()`.
- **Tile**: `TileService.onClick()` calls `startActivityAndCollapse(pendingIntent)` where the `PendingIntent` points to `CaptureActivity`.
- **Share target**: `CaptureActivity` is declared in the manifest with `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters for `text/plain`, `text/html`, and `image/*`. It reads the incoming intent in `onCreate` / `onNewIntent`.

## Alternatives Considered

- **Separate UI per entry point (Glance `RemoteInput` + TileService dialog + dedicated share Activity)**: Rejected. Glance's `RemoteInput` (inline widget text input) has inconsistent support on Samsung One UI — the dominant Android OEM. `TileService.showDialog()` is limited to basic `AlertDialog` with no Compose, making it difficult to show a page-picker or destination chip row. Three separate UIs triple the maintenance surface for a solo developer.

- **TileService with a `Dialog` via `showDialog()`**: Rejected specifically for the tile. `showDialog()` only accepts `android.app.Dialog` — no Compose, no Material3 theming, no page-picker. Starting `CaptureActivity` via `startActivityAndCollapse` provides full Compose and is the approach recommended by Android documentation for feature-rich tile interactions.

## Rationale

One Activity, one code path, one test target. The capture UX is identical regardless of how it was triggered — the user sees the same translucent overlay with keyboard raised, the same "Save" button and "Choose page" secondary action, and the same auto-save-on-dismiss behavior. Any UX improvement to `CaptureActivity` (e.g., adding a voice input button) benefits all three entry points simultaneously.

The `ACTION_SEND` intent filter on `CaptureActivity` means the share target is `CaptureActivity` itself — no separate `ShareTargetActivity` class is needed. The Activity reads `EXTRA_TEXT`, `EXTRA_SUBJECT`, and `EXTRA_STREAM` from the incoming intent and pre-fills the text field.

## Consequences

**Positive:**
- Single capture UI to build, test, and maintain.
- Consistent UX across widget, tile, and share: users learn one pattern.
- All write logic in one place — no risk of divergent write paths between entry points.
- `CaptureActivity` is a standard `Activity` with full Compose support, Material3 theming, and standard testing (Robolectric / Espresso).

**Negative / Risks:**
- `CaptureActivity` receives share intents, so its launch mode must be `singleTop` (not `singleTask`) to correctly handle `onNewIntent` when already in the back stack. Must handle both `onCreate` and `onNewIntent` for share intent data.
- `android:excludeFromRecents="true"` must be set so the overlay does not appear in the recent-apps list.
- **`EXTRA_STREAM` URI permission lifetime**: The OS grants temporary read permission on a shared content URI only to `CaptureActivity`. If the URI is forwarded to a `CoroutineWorker` or another process, the permission is revoked and a `SecurityException` is thrown. `CaptureActivity.onCreate` must copy stream content to app-private storage synchronously before any coroutine or async handoff.
- The tile's `startActivityAndCollapse(PendingIntent)` form must be used (not the deprecated `startActivityAndCollapse(Intent)` form removed in API 34). Must maintain a version branch for API 24–33 vs. API 34+, or use only the `PendingIntent` form (available on all supported API levels via compat).

**Follow-up work:**
- Implement `CaptureActivity` with: translucent theme (`Theme.Stelekit.Translucent`), `windowSoftInputMode = stateAlwaysVisible`, a multi-line `TextField` (pre-filled from share extras), "Save" primary action, "Choose page" secondary chip row, auto-save on any dismiss path (back, home gesture, outside tap).
- `CaptureActivity.onCreate`: read share extras in order — `clipData.coerceToText()` → `EXTRA_TEXT` → `EXTRA_SUBJECT` — with null guards on every field. Copy `EXTRA_STREAM` to `cacheDir` synchronously before launching any coroutine.
- Register `CaptureActivity` in `AndroidManifest.xml` with `ACTION_SEND` intent filters, `launchMode="singleTop"`, and `excludeFromRecents="true"`.
- Register `ShortcutManagerCompat` Direct Share shortcuts in `CaptureActivity.onResume` (or on every graph load) pointing to today's journal page and up to 4 recently visited pages.
- Write unit tests covering: null `EXTRA_TEXT`, null `EXTRA_STREAM`, `EXTRA_SUBJECT`-only intent, and multi-image `ACTION_SEND_MULTIPLE`.

## Related

- Requirements: `project_plans/android-features-integration/requirements.md`
- Research synthesis: `project_plans/android-features-integration/research/synthesis.md`
- Supersedes: (none)
- Related ADRs: ADR-001 (Application singleton), ADR-002 (Glance widget)
