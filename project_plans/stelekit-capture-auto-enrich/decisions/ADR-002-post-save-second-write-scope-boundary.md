# ADR-002: Post-Save Second-Write Scope Boundary

**Date**: 2026-08-27
**Status**: Accepted

## Context

AC #9 requires that a suggestion chip can still be accepted after the user has already
tapped Save — the capture sheet enters a brief "Done" state (`research/ux.md` §3c)
during which the just-persisted block can gain one more `[[link]]`. This is, on its
face, a form of post-save editing, and this codebase has no existing precedent for
writing into an already-persisted block from outside the normal edit flow: Import's
stub-page creation (`ImportViewModel.confirmImport()`) happens *before* anything is
persisted — nothing is written until the same call that creates the parent page.
Tag-suggestion's engine only ever proposes; nothing in `TagSuggestionEngine`/
`TagSuggestionViewModel` writes to a block. AC #9's second write is therefore the first
place in this codebase that deliberately reopens an already-saved `Block` for a
targeted, out-of-band mutation, and it needs an explicit boundary so it is never read
as license for general "edit anything after it's saved."

Two correctness properties from `research/architecture.md` Q2 and `research/pitfalls.md`
PF-4/PF-5 motivate the scope of this decision:

- `GraphWriter` carries per-instance mutable state beyond its constructor
  (`saveMutex`, `activeConflicts`, `pendingByPage` — `GraphWriter.kt:110,115,124-125`).
  A second write through a *different*, freshly-constructed `GraphWriter` instance has
  no visibility into the first instance's in-flight/recently-completed state for the
  same page and could race the same markdown file.
- `CaptureActivity` no longer finishes immediately on save (AC #9's own requirement),
  which extends the process-death exposure window for as long as the user lingers on
  the post-save sheet — `android:excludeFromRecents="true"` +
  `singleTop` (`AndroidManifest.xml:55-60`) make this Activity a preferred low-memory-killer
  target while backgrounded, unlike a normal in-app screen.

## Decision

The post-save second write is permitted **only** under all of the following
constraints, enforced by `CaptureViewModel.acceptSuggestionPostSave()`
(`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt`):

1. It may touch **only** the single `Block` the original `performSave()` call
   persisted (identified by its `BlockUuid`, retained in `CaptureViewModel.SavedCaptureContext`)
   and **only** the one stub `Page` for the term being accepted. It may never read
   from disk, never target a different block, and never re-open a previously-closed
   capture session.
2. It must reuse the **exact same** `GraphWriter` and `DatabaseWriteActor` instances
   `performSave()` originally constructed — never a freshly-constructed `GraphWriter`
   — so both writes serialize through the same `saveMutex`/`activeConflicts` state.
3. It must **only ever start after `performSave()`'s original write has fully
   completed** (`Result.isSuccess` observed). The two writes are never interleaved or
   raced against each other; ordering is enforced structurally because
   `SavedCaptureContext` does not exist until `performSave()` has already returned
   successfully (`CaptureViewModel.kt`, Story 2.3.2) — there is no code path that can
   construct one before the first write is done.
4. A failure on the second write (including a `ClosedSendChannelException` from a
   graph switch during the post-save window, PF-5) degrades to "suggestion not
   applied" — logged, surfaced as a per-chip failure (AC #7's isolation extended to
   this path) — and never triggers, retries, or implies retrying the *original* save.
   The error message is deliberately distinct from `performSave()`'s pre-save
   `"Graph switched during save — please retry"`, since by this point the block itself
   is already durably saved and there is nothing to retry.
5. It is bounded in time by the post-save "Done" window's own auto-finish timer
   (`research/ux.md` §3c, ~2.5–3s, resettable, paused on accessibility focus) — once
   the sheet finishes, no further second write can occur for that capture session.

## Rationale

**Why this doesn't reopen general retroactive post-save editing**: every other path
into `CaptureViewModel` that could plausibly mutate a saved block is unaffected —
`updateText()` still only ever mutates `_captureText`/`_scanState`, which the *next*
`save()` call would write as a *new* block, not an edit of the old one (there is no
"resume editing a saved capture" UI at all; the sheet closes after the "Done" window).
The only mutation path into an already-persisted `Block` is `acceptSuggestionPostSave()`,
and it is reachable from exactly one UI action (tapping a still-pending suggestion
chip) that only exists for a few seconds after save and only when suggestions were
already computed *before* the save happened. There is no way to accept a suggestion
that wasn't already visible pre-save, and no way to edit anything else about the
block's content through this path.

**Why same-instance reuse (constraint 2) is a correctness requirement, not an
optimization**: `GraphWriter.savePage()` does not go through the debounced
`pendingByPage` path (`savePage()` delegates straight to `savePageInternal`,
`GraphWriter.kt:223`), so a second write through a *fresh* instance would not
obviously break in isolation — the risk is specifically two independently-mutexed
instances racing the same markdown file with no shared `saveMutex`/`activeConflicts`
state between them. Reusing the identical instance closes this without adding any new
coordination mechanism.

**Why ordering (constraint 3) matters more here than a typical two-write sequence**:
per `research/pitfalls.md` PF-4, since the block was already durably saved before the
post-save window opens, the worst case of a process death mid-second-write is "the
suggested stub page and its `[[link]]` never got created" — a lost enhancement, not
lost user content. That safety property only holds if the *original* save is
guaranteed to have already completed; if the two writes could race or interleave, a
process death could instead corrupt the state of the original, already-reported-as-
successful save. Constraint 3 is what makes the second write safe to reason about
independently of process death.

## Consequences

- `CaptureViewModel.SavedCaptureContext` is the single mechanism through which this
  boundary is enforced — its existence is both necessary and sufficient evidence that
  constraints 2 and 3 hold (see the plan's Story 2.3.2, Story 4.2.1).
- No other class or write path in this codebase needs to change to honor this ADR —
  `GraphWriter`/`DatabaseWriteActor` are used exactly as designed, just from a second
  call site under the constraints above.
- If a future feature wants genuine post-save editing (e.g., "edit this capture from
  history"), it needs its own design and its own ADR — this decision explicitly does
  not license reusing `acceptSuggestionPostSave()`'s pattern for anything beyond the
  one suggestion-chip-accept action it was built for.
