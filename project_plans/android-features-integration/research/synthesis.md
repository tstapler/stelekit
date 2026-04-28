# Research Synthesis: Android Features Integration

**Created**: 2026-04-22
**Sources**: findings-stack.md, findings-features.md, findings-architecture.md, findings-pitfalls.md

---

## Decision Required

Which Android platform integration strategy — widget, Quick Settings tile, and share target — provides the best capture UX with the least architectural risk, given SteleKit's current Kotlin Multiplatform codebase and single-developer constraint?

---

## Context

SteleKit is a Kotlin Multiplatform note-taking app (Logseq-like Markdown outliner) with a daily journal workflow. The project targets Android, desktop, iOS, and web from a single codebase. All Android-platform-specific code lives in `androidMain`. The goal is to ship three Android platform features — a home screen quick-capture widget, a Quick Settings Tile, and a share target — within 3 months.

**Competitive landscape update** (January 2026): Obsidian shipped its first official Android widgets and Quick Settings Tile in v1.11 (December 2025). The widgets are display-only/navigation shortcuts (open note, new note, daily note, search, open app) — **no inline text capture**. This is a direct differentiation opportunity for SteleKit: ship a widget with an actual keyboard-input capture flow.

**Critical technical constraint discovered in research**: Jetpack Glance has a session-based locking mechanism that holds a lock for 45–50 seconds after each widget update. During this window, new update requests are silently ignored. This rules out any real-time or frequent-refresh display widget; a capture-only widget (user-triggered only, no data display to refresh) sidesteps this entirely.

---

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| **A. Lightweight overlay Activity + Application singleton** | Promote `GraphManager` to `Application` scope; all three entry points (widget button, tile tap, share intent) open a shared `CaptureActivity`; write through existing `DatabaseWriteActor` + `GraphWriter` | Lowest complexity; maximum code reuse; requires `Application` subclass refactor |
| **B. DataStore-first + WorkManager writes** | Widget/tile read-only from a DataStore summary written by the main app; captures queued to WorkManager for deferred write | Eliminates process-isolation risk; adds 2–15 s write latency; breaks immediate-confirmation UX |
| **C. ContentProvider-based data layer** | Expose graph data via `ContentProvider`; widget reads/writes through cursor API | Cross-process safe; fights the existing Flow-based repository layer; high boilerplate; poor KMP testability |

---

## Dominant Trade-off

The fundamental tension is **process-isolation safety vs. write latency**. Android widgets and tiles can be cold-started without the main app ever running, creating a window where the data layer is uninitialized. The two sides:

- **Option B (DataStore + WorkManager)** prioritizes safety: widget captures go to a queue, written later. No risk of uninitialized `GraphManager`. But capture is not immediately in the graph — a note written from the widget won't appear in the app until WorkManager runs (typically 2–15 s, longer under battery constraints).

- **Option A (Application singleton)** prioritizes immediacy: captures go through the real data layer in real time, with the same actor-serialized write path as the UI. The trade-off is that `Application.onCreate` must initialize `GraphManager` cheaply (it does — the SQLite driver is lazily opened). The one real risk: if no graph is configured, the widget must degrade gracefully.

**Option A is the right call for SteleKit** because: (a) immediate confirmation is table-stakes for a capture UX that competes with Obsidian's new widgets, (b) `GraphManager` initialization is lazy and cheap (JSON parse only, no SQLite open), and (c) a single developer cannot afford the debugging surface of WorkManager races and queue drain logic.

**Key architectural note on DataStore**: If the widget ever needs to read graph data (e.g., "last captured note" confirmation), use `MultiProcessDataStoreFactory` — not `DataStore { }` — because mixing single-process and multi-process DataStore on the same file corrupts data.

---

## Recommendation

**Choose**: Option A — `SteleKitApplication` singleton + shared `CaptureActivity` for all three entry points.

**Because**:
1. The existing `DatabaseWriteActor` already serializes all writes; promoting `GraphManager` to `Application` scope requires no new synchronization primitives — it extends the existing safety model.
2. The Glance 45–50 s session lock means no real-time data display is practical anyway. A capture-only widget (no data to refresh) avoids this constraint entirely and is a better product decision.
3. A shared `CaptureActivity` (translucent overlay, keyboard raised immediately, auto-save on dismiss) serves the widget button, tile tap, and share intent with one codebase — consistent UX, minimal duplication.
4. Obsidian's new widgets (Jan 2026) are navigation-only; an inline-capture widget is a genuine differentiator that a DataStore-deferred design could not credibly deliver.

**Accept these costs**:
- A 1–2 day refactor to create `SteleKitApplication` before any new Android component can be built.
- `GraphManager.getActiveGraphInfo()` returning null on first-ever launch — widget/tile must show a "No graph configured" placeholder and launch main app onboarding.
- SAF permissions are only valid in the app's process (same `applicationContext`); image streams received via share must be copied to app-private storage synchronously in `CaptureActivity.onCreate` before any coroutine handoff.

**Reject these alternatives**:
- **Option B (WorkManager-deferred)**: Rejected because 2–15 s write latency is incompatible with a capture UX. A user who taps the widget and returns to the app before WorkManager runs will see a missing note, then a duplicate when the worker fires. Also creates a race condition with `DatabaseWriteActor`.
- **Option C (ContentProvider)**: Rejected because the codebase is built around `Flow`-based repositories with `StateFlow` and coroutines. Wrapping this in a cursor API for zero marginal benefit (all components are in the same process) is pure complexity cost.

---

## Implementation Order

1. **Share target** (1–2 days): Lowest technical risk. `ACTION_SEND` intent filter + `ShortcutManagerCompat` direct share. Creates `CaptureActivity` (the shared overlay Activity). Validates the `Application` singleton refactor before adding widget/tile complexity.
2. **Quick Settings Tile** (1–2 days): Low risk. `TileService.onClick()` launches `CaptureActivity` via `startActivityAndCollapse(pendingIntent)`. Guard `requestAddTileService()` at API 33.
3. **Glance widget** (3–5 days): Highest risk (Glance quirks, launcher variance). A button that launches `CaptureActivity`. No data display. `SizeMode.Responsive` with 2–3 breakpoints. Test on Pixel and Samsung One UI.

---

## Critical Implementation Rules

These must be enforced from day one — all come from confirmed failure modes in the research:

1. **Never call `updateTile()` outside an active listening window.** Wrap all tile coroutines in a scope cancelled in `onStopListening`.
2. **Never hold mutable state in `GlanceAppWidget` class fields.** All widget state must be in `GlanceStateDefinition` / DataStore (using `MultiProcessDataStoreFactory` if shared with the main app).
3. **Copy `EXTRA_STREAM` content to private storage synchronously in `CaptureActivity.onCreate`** before any coroutine. URI read permission is revoked on Activity destruction.
4. **Read share intent extras in order: `clipData` → `EXTRA_TEXT` → `EXTRA_SUBJECT` → null-guard each field.** `EXTRA_TEXT` is null from a large fraction of real senders.
5. **Use `goAsync()` + `appScope` in `AppWidgetProvider.onUpdate`; never `GlobalScope`.** Call `pendingResult.finish()` when the coroutine completes.
6. **Always call `GraphWriter.savePage()` after the `DatabaseWriteActor` write.** The markdown file (source of truth for disk sync) is only updated by `GraphWriter`, not by the actor alone.
7. **Use `startActivityAndCollapse(PendingIntent)` form for tile click** — the `Intent` overload is deprecated in API 34.
8. **`requestAddTileService()` must be called from the foreground** and only in a relevant user context (e.g., after the user saves their first note).
9. **Use Glance 1.1.1** (not 1.1.0) to pick up a protobuf security fix: `androidx.glance:glance-appwidget:1.1.1`.

---

## Open Questions Before Committing

- [ ] **Default capture destination vs. page picker**: "Always today's journal" vs. configurable per-widget — resolve before writing `CaptureActivity` UI. **Recommended default: today's journal, with a "Choose page" secondary action.** Blocks: `CaptureActivity` scope.
- [ ] **Image handling in share target**: Should received images be stored in `<graph>/assets/` (Logseq convention) or a staging directory? Affects `GraphWriter` integration. Blocks: share target image path.
- [ ] **`GraphWriter` mutex safety under concurrent callers**: Confirm `saveMutex` correctly serializes writes from `CaptureActivity` and the main UI at the same time. A quick code review will suffice; no prototype needed. Blocks: correctness validation.
- [ ] **Samsung One UI text input in Glance widget**: If `RemoteInput` is unreliable on Samsung (the dominant Android OEM), the widget button should launch `CaptureActivity` rather than attempting inline keyboard input. This is the safer default; flag for real-device testing once the widget is built.

If the page-picker and image-path questions are decided (can be resolved in < 30 minutes of conversation), no spike is needed before writing the ADR.

---

## Sources

- `research/findings-stack.md` — Glance 1.1.1, TileService, share target APIs and Gradle coordinates
- `research/findings-features.md` — UX patterns from Keep, Todoist, Obsidian (v1.11 Jan 2026), Bear, Drafts, Simplenote
- `research/findings-architecture.md` — Application singleton pattern, write-back path, Option trade-off matrix
- `research/findings-pitfalls.md` — Glance session lock (45–50 s), DataStore multi-process, URI permission lifetime, tile scope management
- [Glance releases — Android Developers](https://developer.android.com/jetpack/androidx/releases/glance)
- [MultiProcessDataStoreFactory — Android Developers](https://developer.android.com/reference/kotlin/androidx/datastore/core/MultiProcessDataStoreFactory)
- [Create custom Quick Settings tiles — Android Developers](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- [Obsidian 1.11 Mobile changelog](https://obsidian.md/changelog/2026-01-12-mobile-v1.11.4/)
- [What Finally Worked with Jetpack Glance — Box Box Club (Feb 2026)](https://blog.boxbox.club/what-finally-worked-with-jetpack-glance-f0292d319cc2)
- [Taming Glance Widgets: Fast & Reliable Widget Updates — Medium (Nov 2025)](https://medium.com/@abdalmoniemalhifnawy/taming-glance-widgets-a-deep-dive-into-fast-reliable-widget-updates-ae44bfc4c75a)
