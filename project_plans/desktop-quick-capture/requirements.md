# Requirements: Desktop Quick Capture (OS-level)

**Item ID**: caff12c9-008e-4ff1-a2de-ccf0b6ff8232
**Source**: backlog item "feat: OS-level quick capture (context menu / Share extension) for Desktop platforms"

## Target User

SteleKit is a solo-maintainer, source-available personal knowledge-management app (see
`README.md`'s "Why I built this") — not a product with a segmented user base. The "target
user" for this feature is the primary maintainer (Tyler) and any other individual SteleKit
desktop users, not a distinct persona requiring research or segmentation.

## Prioritization

This item was picked up via the backlog triage process, not scored against a formal RICE/ICE
model or other open backlog items. Its justification is the platform-parity gap stated above:
Android already has `CaptureActivity`; desktop has no equivalent, so desktop users have
strictly more friction than Android users for the same one-line-note task.

## Problem

Android has `CaptureActivity` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`):
share-sheet → translucent overlay → text field → save to today's journal via
`GraphWriter`/`DatabaseWriteActor`. Desktop (JVM: macOS/Linux/Windows) has no equivalent —
no way to capture a note from outside the running app without opening SteleKit and using
in-app Import. This is a friction gap, not a broken feature: desktop users today have
strictly more steps than Android users to add a one-line note.

Distinct from:
- #230 — Chrome web-clipper extension (browser-scoped only).
- #232 — local REST/automation API (this item's suggested v1 scope treats #232 as a
  dependency for the *out-of-process* capture surfaces — see Open Questions).

## Why it matters

- Closes a real UX asymmetry between platforms; not a regression, not blocking any release.
- Once #232 lands, this becomes a thin OS-integration client of it, mirroring how #230
  (web clipper) will consume it — avoiding a second bespoke write path.

## In scope (v1, per backlog item's "Suggested scope")

- **macOS**: Share Extension / Services-menu entry ("Add to SteleKit") — selected text →
  today's journal. **Deferred to Phase 2** — re-scoped out of v1 during planning per
  `project_plans/desktop-quick-capture/decisions/ADR-002-v1-scope-cut-in-process-popup-only.md`
  (kept here as the original ask, not silently dropped).
- **Linux**: file-manager "Send to" script (Nautilus action) for text files, OR a
  global-hotkey capture popup — whichever is lowest-effort first.
- **Windows**: context-menu registry handler invoking the same capture path. **Deferred to
  Phase 2** — re-scoped out of v1 during planning per the same ADR-002 (kept here as the
  original ask, not silently dropped).
- Reuse the enrichment pipeline (auto-link + tag-suggest) being wired into Android capture,
  once that lands — no second bespoke enrichment path.

## Out of scope (v1)

- Full Import UI parity (scope/format picker) — this is the minimal-friction path only,
  equivalent to Android's bottom sheet.
- Mobile platforms (already covered by `CaptureActivity`).
- The local REST API itself (#232) — tracked separately; this item consumes it, does not
  build it.

## Existing reference implementation

- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt` +
  `CaptureViewModel.kt` — parses share intent, writes via
  `GraphManager.getActiveRepositorySet().journalService.ensureTodayJournal()` +
  `GraphWriter`, all in-process (same JVM/app instance, no IPC).
- `ADR-003-shared-capture-activity.md`
  (`project_plans/android-features-integration/decisions/`) — rationale for one shared
  capture UI across widget/tile/share entry points on Android; the same "one capture
  surface, multiple triggers" principle applies to desktop's context-menu/global-hotkey/
  Share-extension triggers.
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` — desktop `main()`,
  Compose `Window` entry point.

## Key architectural fact discovered during triage

Not all three v1 surfaces have the same dependency shape:

- A **global-hotkey capture popup** can run **in-process**, inside the already-running
  desktop app (a second small `Window`/`Dialog` in the same `application { }` block),
  and call the same `GraphManager` / `JournalService` / `GraphWriter` write path
  `CaptureActivity` uses on Android directly — **no #232 API required**.
- **macOS Share Extension**, **Nautilus "Send to" script**, and **Windows context-menu
  handler** are separate OS processes (an app extension sandbox, a shell script, a
  registry-invoked handler) with **no in-process access** to a running SteleKit JVM.
  These genuinely need either #232 (local REST API) or some other IPC (Unix socket,
  named pipe, single-instance lock file + argv hand-off) to reach a running instance —
  and a cold-start story when SteleKit isn't running.

This means the backlog item's "thin client of #232" framing is accurate for the OS
context-menu/Share-extension surfaces, but not for a hotkey popup, which is the one
"lowest-effort" option the item itself calls out for Linux.

## Acceptance Criteria (draft — see final list in triage JSON output)

1. At least one desktop platform (macOS, Linux, or Windows) has a working OS-level
   capture surface that lands captured text in today's journal, matching
   `CaptureActivity`'s save target. **v1 scope note** (see ADR-002, pre-mortem): the shipped
   surface only works while SteleKit is already running — it does not close the "capture
   without opening the app at all" cold-start gap the original backlog item's title implies.
   Out-of-process, cold-start-capable surfaces (macOS Services menu, Nautilus script, Windows
   registry handler) are deferred to Phase 2.
2. The capture path reuses the existing commonMain write path
   (`JournalService.ensureTodayJournal()` + `GraphWriter`/`DatabaseWriteActor`) rather
   than inventing a second write mechanism.
3. Out-of-process surfaces (Share extension / context-menu handlers) have a defined
   IPC/API contract to a running SteleKit instance, and a defined behavior when SteleKit
   is not running (cold start, queue-and-flush, or explicit error — TBD by design).
4. Feature is additive: no changes to existing Android `CaptureActivity` or its tests.

## Success Metrics

This is a solo-maintainer personal app, not a metrics-instrumented product — no dedicated
telemetry is built for this feature (see `implementation/plan.md`'s Observability Plan: no
metrics added in v1). Success is assessed qualitatively:

- Does the primary maintainer (Tyler) actually use the hotkey capture path daily instead of
  falling back to in-app Import, within the first month post-release?
- Are there zero duplicate-journal-entry bug reports attributable to the idempotency
  mechanism (`captureId`-based `INSERT OR REPLACE`)?

## Open Questions

1. Should v1 ship only the in-process global-hotkey popup (no #232 dependency, smallest
   diff) and treat macOS Share Extension / Windows context menu as v2 once #232 exists?
   The backlog item lists all three OS surfaces as v1 scope but flags picking "whichever
   is lowest-effort first" for Linux — the same lowest-effort logic argues for
   sequencing the hotkey popup before the two OS-extension surfaces repo-wide.
2. #232 (local REST/automation API) does not exist in this codebase yet (confirmed: no
   `project_plans/` entry, no server code found). Is #232 already planned/prioritized
   elsewhere, or does taking this item mean scoping and building #232 first?
3. What does "SteleKit not running" do for the OS-extension surfaces — launch the app
   silently in the background, queue the note in a local file and flush on next launch,
   or show an OS-level error? Android's share sheet always has a foreground Activity to
   launch into; desktop context-menu handlers do not have an equivalent guarantee.
4. Auto-link/tag-suggest enrichment is called out as "being wired into Android capture"
   in a companion issue — is that issue merged yet? If not, desktop v1 ships without
   enrichment and picks it up later, which should be stated explicitly rather than
   silently deferred.
