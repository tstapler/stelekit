# Implementation Plan: wasm-image-drop

**Feature**: Drag-and-drop image attachment on the WASM/web build — build the missing web
image-attachment pipeline (bytes-capable `MediaAttachmentService`, OPFS byte I/O, JS drop
capture) end to end, not just the JS listener the ticket literally asked for.
**Date**: 2026-09-22
**Status**: Ready for implementation
**ADRs**: ADR-001 (hand-rolled JS interop over `kotlinx-browser`), ADR-002 (Base64 bridge for
`ArrayBuffer` ↔ `ByteArray`)

---

## Step 0.5 — CREATIVE pass: alternative architectures considered

**A. Body-level global JS listener + bytes-capable interface method (chosen).** A single
`dragenter`/`dragover`/`drop` listener set is installed once on `document.body` (event bubbling
covers the canvas); the wasmJs `actual pageDropTarget` just registers/swaps which
`onFilesDropped` callback is "active." Bytes reach `App.kt` through a new `DroppedFileBytes`
value type carried in the existing `List<Any>` contract, and a new default-null
`MediaAttachmentService.attachBytes` method.
- *Strength*: smallest diff — `EditorCapabilities`, `PageView.kt`, and the `onFileDrop` callback
  shape are untouched; sidesteps the "canvas not queryable until Compose mounts it" timing race
  entirely (research/pitfalls.md §2) by never querying for the canvas.
- *Weakness*: the listener's "which callback is active" state lives in a module-level var
  outside Compose's state model, which is a mild departure from idiomatic Compose (mitigated:
  it's write-only from Compose's side and read-only from the JS callback, so it can't cause
  recomposition bugs).

**B. Adopt `kotlinx-browser` + Compose `dragAndDropTarget` on the canvas.**
- *Strength*: if it worked, it would be the "correct" cross-platform abstraction — same
  `Modifier.pageDropTarget` call shape as every other platform's use of Compose's own DnD API.
- *Weakness*: research/build-vs-buy.md §1a found no working, documented wasmJs drop-*target*
  implementation at this repo's Compose Multiplatform 1.10.3 pin — a firsthand report one patch
  below this pin says the vendor's own sample "doesn't compile." Betting the fix on an unproven
  API is the wrong risk trade for a bug-fix-scoped project.

**C. Ticket's literal proposal — JS listener posts into a `Channel`, consumed via a
`CompositionLocal` wired through `Main.kt`.**
- *Strength*: fully decouples the JS listener lifecycle from Compose recomposition; one listener,
  one long-lived `Flow` of drop events.
- *Weakness*: requires introducing a new architectural primitive (`CompositionLocal`-provided
  `Channel`) and changing `EditorCapabilities`/`PageView.kt`, both of which
  research/architecture.md §1 confirms need **no** change today. Bigger diff for no behavioral
  gain over Approach A.

**Chosen: A.** Rejected alternatives are recorded in the Pattern Decisions table below.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `DroppedFileBytes` | Immutable value type wrapping an in-memory dropped file: `suggestedName: String`, `bytes: ByteArray`. Represents a browser `File`/`Blob` that has no filesystem path. | New, commonMain. Newtype-style value object, not a raw `Pair`. |
| `attachBytes` | New default-`null` method on `MediaAttachmentService`: `suspend fun attachBytes(bytes: ByteArray, suggestedName: String, graphRoot: String): Either<DomainError, AttachmentResult>?`. The bytes-capable sibling of `attachFilePath`. | Same nullable-outer/`Either`-inner contract shape as the interface's three existing optional methods. |
| `WasmMediaAttachmentService` | wasmJsMain implementation of `MediaAttachmentService`; only overrides `attachBytes`. | New file. Mirrors `IosMediaAttachmentService`'s "only implement what's supported" shape. |
| `AttachmentResult` | Existing commonMain result type (`relativePath`, `displayName`) returned by every attach path on success. | Unchanged; reused as-is. |
| `opfsWriteFileBytes` | New suspend function in `OpfsInterop.kt`: writes a `ByteArray` to an OPFS path, creating parent directories, and **propagates failure to the caller** (does not swallow into `println`, unlike the existing `opfsWriteFile`). | wasmJsMain, `internal`. |
| `uniqueOpfsFileName` | New async suffix-counter dedup function (`photo.jpg` → `photo-1.jpg`) that probes live OPFS via `getFileHandle(create = false)` instead of a synchronous `okio.FileSystem`. | wasmJsMain. Async sibling of the existing sync `uniqueFileName` (`AttachmentFileNaming.kt`) — not a reuse of it, per research/architecture.md §"Additional finding." |
| `IMAGE_EXTENSIONS` (wasm) | wasmJsMain-local `val` set of accepted extensions (`jpg, jpeg, png, gif, webp, heic, svg, bmp`), matching JVM's filter list verbatim. | Duplicated per-platform, matching the repo's existing convention (JVM already duplicates this constant across `PageDropTarget.kt` and `JvmMediaAttachmentService.kt`) — see Pattern Decisions. |
| `DropZoneInterop` | New wasmJsMain file holding the raw JS glue: `external fun`/`js(...)` declarations for `document.body` drag/drop listeners, `DataTransfer.files` extraction, and `File.name`/`File.arrayBuffer()` reads. | The only new *untested-by-unit-test* surface (DOM event glue) — see Observability/Risk sections. |
| `ByteBufferInterop` | New wasmJsMain file holding the Base64-based `ArrayBuffer` ↔ `ByteArray` bridge functions (ADR-002). | Used by both the read path (`File.arrayBuffer()` → `ByteArray`) and the write path (`ByteArray` → OPFS `write()`). |
| `DomainError.AttachmentError.CopyFailed` | Existing sealed variant, reused (not extended) to represent any OPFS write/quota/permission failure on wasm. | See Pattern Decisions for why no new variant is added. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall drop-capture architecture | Body-level global listener + bytes-capable interface method (Approach A) | — | Compose `dragAndDropTarget` on canvas (B); `Channel`/`CompositionLocal` bridge (C) | B is unproven/undocumented for wasmJs OS-file-drop at this Compose pin; C requires new architectural primitives and touches files research confirmed don't need to change |
| `WasmMediaAttachmentService` | Service Layer implementation of an existing interface (PoEAA) | Fowler | `is WasmMediaAttachmentService` cast in commonMain `App.kt` | Would break the platform-agnostic-common-code discipline the `Any`-erased `onFileDrop` payload already preserves |
| `MediaAttachmentService.attachBytes` | Optional capability method, default `= null` (existing interface convention) | Repo precedent (`attachFilePath`, `hasClipboardImage`, `pasteFromClipboard`) | Separate `BytesAttachmentService` interface | Would force `App.kt` to hold and branch on two service references instead of one; the existing interface was explicitly designed for this kind of incremental extension (research/architecture.md §3) |
| `DroppedFileBytes` | Newtype / value object | type-driven-design | Raw `Pair<String, ByteArray>` passed through `List<Any>` | A named type documents intent at the `is DroppedFileBytes` check in `App.kt`; a `Pair` would be silently ambiguous with any other future `Pair<String, ByteArray>` payload |
| JS interop style (drop capture, byte read) | Hand-rolled `external fun = js(...)` | ADR-001 | `kotlinx-browser` typed bindings | See ADR-001 — OPFS still needs hand-rolling regardless, so mixing styles for only half the feature is worse than one consistent style |
| `ArrayBuffer` ↔ `ByteArray` conversion | Base64-string bridge | ADR-002 | Per-element `Int8Array` get/set loop (matches `SqliteWorkerInterop.kt`'s existing small-array pattern) | Per-element crosses the Wasm↔JS boundary once per byte — research/pitfalls.md §3 flags this as a severe performance cliff for multi-MB images |
| Drop listener attach point | `document.body` (event bubbling) | research/pitfalls.md §2 | `document.querySelector("canvas")` + `MutationObserver` | The canvas doesn't exist at `main()` time (Compose mounts it asynchronously via `ComposeViewport`); body-level listening sidesteps the race entirely |
| OPFS byte write path | Direct new interop call from `WasmMediaAttachmentService`, bypassing `FileSystem.writeFileBytes` | research/architecture.md "Additional finding" | Implement `PlatformFileSystem.writeFileBytes`/`readFileBytes` generically | Mirrors how `JvmMediaAttachmentService` already bypasses the app's `FileSystem` abstraction for attachments; routing binary bytes through `PlatformFileSystem`'s `String`-typed `cache` would require a second, parallel binary cache |
| Attachment filename dedup on wasm | New async `uniqueOpfsFileName` (OPFS existence probe) | — | Reuse `uniqueFileName(assetsDir, ..., fileSystem)` | `uniqueFileName` requires a synchronous `okio.FileSystem.exists()`, which OPFS cannot provide from the main thread; it also can't see binary-only OPFS entries that were never loaded into `PlatformFileSystem`'s text `cache` |
| OPFS write failure → `DomainError` | Reuse `AttachmentError.CopyFailed(message)` | — | New `AttachmentError.QuotaExceeded`/`OpfsWriteFailed` variant | Keeps the diff minimal for a Complexity-2 bug fix; the message string carries OPFS-specific detail (e.g. `QuotaExceededError`); a dedicated variant can be added later if telemetry shows callers need to branch on it specifically |
| wasm attach-failure UX | `WasmMediaAttachmentService.attachBytes` calls an injected `NotificationManager.show(..., NotificationType.ERROR)` directly, right before returning `Either.Left` (Task 3.1.2a); `App.kt`'s shared `onFileDrop` `ifLeft` branch is untouched — still just `graphContentLogger.warn(...)`, exactly as it is today | research/ux.md §4 | (1) Toast call added to the shared `App.kt` `ifLeft` branch — **rejected after cross-artifact review**: `ifLeft` runs for every platform's failures (JVM's `attachFilePath` included), so this would change JVM/Android/iOS's existing log-only behavior, contradicting requirements.md AC6. (2) Leave silent, matching JVM/Android/iOS today | Scoping the toast to the wasm-only `WasmMediaAttachmentService` — not the platform-agnostic `onFileDrop` handler — means the new user-visible behavior can only ever execute from a wasm-only code path. No `is WasmMediaAttachmentService` cast or `if (platform)` branch is needed in shared code, and the "JVM/Android/iOS left as-is" claim is now literally true (the shared `ifLeft` branch has zero diff), not just a stated intent the diff contradicted. Requires threading a `NotificationManager` instance from wasm's `Main.kt` into both `WasmMediaAttachmentService` and `StelekitApp` (Task 1.1.4a, Task 3.1.2a) — see Story 1.1.4 |
| `IMAGE_EXTENSIONS` on wasm | Duplicate the constant locally (matches JVM's own duplication across two files) | — | Extract a shared commonMain `IMAGE_EXTENSIONS` constant | Smaller, lower-risk diff; JVM already duplicates this value in two places without a shared constant, so extracting one now would be a gratuitous unrelated refactor of code this project doesn't otherwise touch |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `MediaAttachmentService` / wasmJsMain image pipeline | wasmJsMain has zero implementation of `MediaAttachmentService`; all three attach entry points (`onAttachImage`, `onFileDrop`, `onPasteImage`) are dead on web (requirements.md "Root-cause investigation") | **Extend as-is** | Confirmed agreement with research/architecture.md §3: the seam already exists — `MediaAttachmentService`'s three optional, default-`null`/`false` methods were explicitly designed for incremental per-platform capability rollout (doc comments literally invite future overrides). wasmJsMain simply never got an implementation, the same way a brand-new platform target starts with zero widgets. Adding `WasmMediaAttachmentService` + one more optional interface member (`attachBytes`) continues the established pattern; no adapter/facade layer or interface redesign is warranted. |
| `PlatformFileSystem.writeFile` fire-and-forget OPFS persistence (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt:66-70`) | Returns `true` before the async OPFS write is confirmed; `opfsWriteFile` swallows failures into a `println`. This is a real, pre-existing bug (silent data loss on text-content writes) | **Isolate via seam — do not extend, do not fix here** | This project's new byte-write path (`opfsWriteFileBytes`) deliberately does **not** reuse `writeFile`/`opfsWriteFile` — it is written fresh with `await()`-before-return and a caught, error-propagating contract (see Pattern Decisions, "OPFS byte write path"). This isolates the new attachment path from the existing text-write bug rather than inheriting it. Fixing `PlatformFileSystem.writeFile`'s fire-and-forget behavior for markdown content is out of scope — it is a distinct, pre-existing defect unrelated to image attachment and touches the journal/page save path, not this feature. |

---

## Migration Plan

Not applicable — no schema or persisted-data-format changes. New OPFS files are written under
the existing `<graphRoot>/assets/` convention, identical in shape to files JVM already writes
there; no migration of existing data is needed.

## Observability Plan

- **Logs**: `graphContentLogger.warn("Drag-and-drop attachment failed: $err")` already exists at
  the `onFileDrop` failure branch (`App.kt`) and is retained unchanged. `opfsWriteFileBytes`
  failures surface as a thrown `Throwable` caught in `WasmMediaAttachmentService.attachBytes`,
  converted to `DomainError.AttachmentError.CopyFailed` with `e.message` — the underlying OPFS
  error text (e.g. `QuotaExceededError`) is preserved in the log line via the existing
  `toUserMessage()`/`warn` call, not discarded. The same catch block also triggers the wasm-only
  user-visible toast (Story 3.1.2, Task 3.1.2a) — the log line and the toast are two independent
  side effects of the same `catch`, not a shared-code call chain.
- **Metrics**: none added. This is a client-side desktop/web app with no existing metrics
  pipeline for attachment operations on any platform (`JvmMediaAttachmentService` has none
  either) — adding one would be inconsistent with every other platform's attach path and is out
  of scope for a bug fix restoring parity.
- **Alerts**: no new alerts required (no server-side component; no existing alerting
  infrastructure for this client-side operation on any platform).

## Risk Control

- **Feature flag**: not gated. The change is inert until wasmJsMain's `attachmentService`
  parameter is non-null, which only happens for real OPFS-backed graphs (see Task 3.2a — demo
  fallback stays `null`, matching today's behavior exactly). The wasm build itself is already
  gated behind `-PenableJs=true`, which is the existing rollout control for the entire web target.
- **Rollback procedure**: standard revert via PR close + revert commit. No data migration to
  unwind (Migration Plan: not applicable).
- **Staged rollout**: full rollout on merge, scoped to the wasmJs target only (JVM/Android/iOS
  code paths are additive-only per file — see "Not touched" list in
  research/architecture.md §"Summary of files touched" — so no other platform's rollout risk
  changes).

## Unresolved Questions

The two items research originally flagged as needing an explicit plan-phase decision
(`kotlinx-browser` adoption; `ArrayBuffer`↔`ByteArray` conversion strategy) remain resolved in
ADR-001 and ADR-002 respectively. Two conditional items were introduced while addressing
pre-mortem's P1 findings:

1. **ADR-002's Base64 bridge is provisionally accepted pending Task 5.1.3a's realistic-size
   (5-10 MB) measurement** (pre-mortem P1 #2). If the <3s-round-trip / no-visible-UI-freeze
   threshold fails for an 8 MB JPEG, ADR-002 must be revisited and the write/read path
   re-implemented with a real `Uint8Array` bulk transfer instead of Base64 — see Story 5.1.3's
   acceptance criteria for the exact fallback and threshold. Owner: whoever implements Phase 5;
   blocking story: Story 5.1.3. This project is not shippable until Task 5.1.3a's measurement is
   recorded — pass, or fail with the `Uint8Array` fallback applied.
2. **`wasmJsBrowserTest`'s JUnit XML report path (Task 5.2.2a) is asserted by convention
   (`**/build/test-results/wasmJsBrowserTest/**/TEST-*.xml`), not verified against this repo's
   exact Kotlin Gradle Plugin version.** If `mikepenz/action-junit-report`'s `report_paths` glob
   doesn't match the real output location, adjust it during implementation — the test
   execution/gating itself (the `run:` step failing the job on a real test failure) does not
   depend on this path being correct, only the GitHub Checks UI annotation does. Not a blocker,
   just an assumption to verify against a real CI run.

Three further items were raised by earlier reviews (architecture-review.md, research/pitfalls.md,
research/features.md) but never formally logged here; the Engineering lens of the Product Triad
Review flagged the omission. Logged now:

- [ ] **Cross-drop-event OPFS filename race**: `uniqueOpfsFileName` (existence probe) →
  `opfsWriteFileBytes` (write) is check-then-write, not atomic (architecture-review.md). Two
  near-simultaneous drops of same-named files from independent drop events (not the same-batch
  sequential `forEach`, which `uniqueOpfsFileName`'s third acceptance criterion already covers)
  can race and silently overwrite each other.
  `WasmMediaAttachmentServiceConcurrencyTest.attachBytes_mayCollideOnFilename_whenCalledConcurrentlyForSameName`
  (validation.md) already exercises this and is expected to fail/flake against this plan's design
  — it exists to surface the gap in CI, not fix it. Fix path: wrap `WasmMediaAttachmentService.attachBytes`'s
  dedup-then-write sequence in a `Mutex.withLock` (per-directory or per-service instance), per
  architecture-review.md's own recommendation — `DatabaseWriteActor`
  (`kmp/src/commonMain/.../db/DatabaseWriteActor.kt`) is this repo's existing precedent for
  serializing concurrent writes behind a single coroutine/lock. — blocks Story 3.1.1/3.1.2's
  fast-follow (not this project's ship) — owner: implementer, before merge of the fast-follow
  story.
- [ ] **No `try/catch` around `File.arrayBuffer()` in `DropZoneInterop`'s drop callback**
  (architecture-review.md): an unreadable file in a multi-file drop batch throws inside
  `readFileBytes`, which propagates out of the `dropScope.launch` block and silently drops every
  remaining file in that batch with no log/toast. This is small enough to fold into this
  project's existing scope rather than defer: Task 4.1.2a's `ensureListenerInstalled()` loop now
  wraps each `readFileBytes(file)` call in `try/catch` and logs the failure via a
  `Logger("GraphContent")` instance local to `PageDropTarget.kt` (matching App.kt's
  `graphContentLogger` tag convention — a plain top-level `Logger(...)` instance per this
  project's established per-file logging pattern, since App.kt's own `graphContentLogger` is a
  composable-local `remember` value `PageDropTarget.kt` cannot reach), then `continue`s to the
  next file instead of letting the exception abort the batch. Already folded into Task 4.1.2a's
  code above — not left open — blocks Story 4.1.2 — owner: implementer of Task 4.1.2a.
- [ ] **Graph-switch/DB-close race is real in principle, unreachable in practice today**: the
  shared `App.kt` `onFileDrop` code path could in principle race with `GraphManager.switchGraph()`
  closing the database mid-drop (see `kmp/CLAUDE.md`'s Repository Flow resilience section for the
  general pattern this risk resembles). On wasm specifically, this is currently unreachable:
  `browser/Main.kt` hardcodes a single `"default"` graph (research/pitfalls.md, research/features.md)
  and offers no graph-switch UI, so `switchGraph()` is never invoked. Not addressed by this
  project — explicitly deferred as a note for whoever eventually adds multi-graph support to wasm,
  not a gap in this project — blocks (future) multi-graph-on-wasm story — owner: whoever
  implements wasm multi-graph support.

---

## Dependency Visualization

```
Phase 1 (commonMain seam)                Phase 2 (OPFS byte I/O)
┌────────────────────────────┐           ┌──────────────────────────────┐
│ 1.1.1a DroppedFileBytes     │           │ 2.1.1a ByteBufferInterop.kt   │
│ 1.1.1b attachBytes on       │           │        (Base64 bridge)       │
│         MediaAttachmentSvc  │           └──────────────┬───────────────┘
│ 1.1.2a App.kt onFileDrop    │                          │
│         when-branch          │                          ▼
│ 1.1.3a PageDropTargetModifier│           ┌──────────────────────────────┐
│         doc fix               │           │ 2.2.1a opfsWriteFileBytes    │
│ 1.1.4a StelekitApp gains an  │           │ 2.2.2a uniqueOpfsFileName    │
│         optional              │           └──────────────┬───────────────┘
│         notificationManager   │                          │
│         param (wasm toast     │                          │
│         seam; default preserves│                         │
│         JVM/Android/iOS as-is)│                          │
└──────────────┬───────────────┘                          │
               │                                            │
               ▼                                            ▼
        ┌──────────────────────────────────────────────────────┐
        │ Phase 3: WasmMediaAttachmentService                    │
        │  3.1.1a attachBytes impl (needs 1.1.1b, 2.1.1a, 2.2.*) │
        │  3.1.2a failure toast inside attachBytes, via injected │
        │         NotificationManager (needs 1.1.4a)             │
        │  3.2a   browser/Main.kt wiring: constructs one shared  │
        │         NotificationManager, passes it to both         │
        │         WasmMediaAttachmentService (3.1.2a) and        │
        │         StelekitApp (1.1.4a) (needs 3.1.1a, 3.1.2a)    │
        └──────────────────────────┬─────────────────────────────┘
                                    │
                                    ▼
        ┌──────────────────────────────────────────────────────┐
        │ Phase 4: JS drop-event capture                        │
        │  4.1.1a DropZoneInterop.kt (needs 2.1.1a for byte read)│
        │  4.1.2a PageDropTarget.kt actual rewrite               │
        │         (needs 4.1.1a, 1.1.1a, 3.2a wired end-to-end)  │
        └──────────────────────────┬─────────────────────────────┘
                                    │
                                    ▼
        ┌──────────────────────────────────────────────────────┐
        │ Phase 5: Tests & verification                         │
        │  5.1.1a WasmMediaAttachmentServiceTest                │
        │  5.1.2a ByteBufferInterop round-trip test (+ realistic │
        │         5-10MB timing/memory gate, see Story 5.1.3)    │
        │  5.2.1a ciCheck + wasmJsBrowserTest + manual check      │
        │  5.2.2a wasmJsBrowserTest wired into CI (ci.yml)        │
        └────────────────────────────────────────────────────────┘
```

---

## Phase 1: Domain & Interface Seam (commonMain)

### Epic 1.1: Bytes-capable `MediaAttachmentService` seam

**Goal**: Give commonMain a typed way to carry in-memory dropped-file bytes through the existing
`onFileDrop: (List<Any>) -> Unit` contract and attach them, without touching
`EditorCapabilities`/`PageView.kt`.

#### Story 1.1.1: Add `DroppedFileBytes` and `MediaAttachmentService.attachBytes`

**As a** wasm build, **I want** a typed, in-memory representation of a dropped browser file and
a service method that can persist it, **so that** `App.kt`'s existing drop handler can attach
bytes without a filesystem path.

**Acceptance Criteria**:
- `DroppedFileBytes(suggestedName: String, bytes: ByteArray)` exists in commonMain and is usable
  anywhere `List<Any>` is accepted.
  - *Given* a wasmJs `pageDropTarget` implementation that has read a dropped file's bytes,
    *When* it constructs `DroppedFileBytes(suggestedName = "photo.png", bytes = <42 KB array>)`
    and passes `listOf(that)` to `onFilesDropped: (List<Any>) -> Unit`,
    *Then* the value compiles and type-checks against the existing `expect fun
    Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier` signature with no
    changes to that signature.
- `MediaAttachmentService.attachBytes(bytes: ByteArray, suggestedName: String, graphRoot: String): Either<DomainError, AttachmentResult>?`
  exists with a default implementation returning `null`.
  - *Given* `JvmMediaAttachmentService` (which does not override `attachBytes`),
    *When* commonMain code calls `jvmService.attachBytes(byteArrayOf(1,2,3), "x.png", "/tmp/g")`,
    *Then* it returns `null` (no-op), exactly matching how `attachFilePath` behaves on iOS today
    — no behavior change to JVM/Android/iOS (satisfies requirements.md AC6).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt`,
new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/DroppedFileBytes.kt`

##### Task 1.1.1a: Create `DroppedFileBytes` (~3 min)
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/DroppedFileBytes.kt` with the
  copyright/SPDX header matching `MediaAttachmentService.kt`, package
  `dev.stapler.stelekit.service`, and:
  ```kotlin
  /** An in-memory dropped file with no filesystem path (browser File/Blob). */
  data class DroppedFileBytes(val suggestedName: String, val bytes: ByteArray)
  ```
  Note: `ByteArray` gives this class structural (not content) `equals`/`hashCode` by default
  from Kotlin's `data class` unless overridden — acceptable here since instances are never
  compared/deduped, only constructed and consumed once.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/DroppedFileBytes.kt`

##### Task 1.1.1b: Add `attachBytes` to `MediaAttachmentService` (~4 min)
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt`, add a
  new method after `attachFilePath` (after line 64), following the same doc-comment convention:
  ```kotlin
  /**
   * Copies already-in-memory file bytes (no filesystem path available) into the graph's
   * `assets/` directory. Used for platforms where a dropped/pasted file exists only as bytes
   * (e.g. a browser `File`/`Blob` on wasmJs).
   *
   * Returns [Either.Right] with [AttachmentResult] on success.
   * Returns [Either.Left] with [DomainError.AttachmentError] on failure.
   * Returns `null` if the platform does not support this operation.
   *
   * Default implementation returns `null` (no-op) so existing implementations need not
   * override unless they support bytes-only attachment.
   */
  suspend fun attachBytes(
      bytes: ByteArray,
      suggestedName: String,
      graphRoot: String
  ): Either<DomainError, AttachmentResult>? = null
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt`

#### Story 1.1.2: Wire `App.kt`'s `onFileDrop` handler to branch on payload type

**As a** user dropping an image on any platform, **I want** the existing drop handler to
recognize both `java.io.File` (JVM) and `DroppedFileBytes` (wasm) payloads, **so that** wasm gets
the same markdown-insertion outcome as JVM for the same interaction.

> **Scope note (post cross-artifact-review fix)**: this story only covers *dispatch*. The
> original draft also added a user-visible toast directly in this handler's `ifLeft` branch, but
> that branch is shared, non-wasm-gated code — it runs for JVM's `attachFilePath` failures too, so
> adding a toast there would have changed JVM/Android/iOS's existing log-only failure behavior,
> contradicting requirements.md AC6 ("No regression to JVM/Android/iOS ... behavior") and this
> plan's own Pattern Decisions claim that JVM/Android/iOS are "left as-is." The toast now lives
> entirely in wasm-only code — see Story 1.1.4 (the notifier seam) and Story 3.1.2 (the actual
> `notificationManager.show(...)` call, inside `WasmMediaAttachmentService.attachBytes`). This
> handler's `ifLeft` branch is **not modified by this project** and keeps its current
> `graphContentLogger.warn(...)`-only body unchanged.

**Acceptance Criteria**:
- Dropping a `DroppedFileBytes` payload calls `attachmentService.attachBytes(...)`; dropping
  anything else falls back to today's `attachmentService.attachFilePath(filePath = file.toString(), ...)`.
  - *Given* the `onFileDrop` handler in `App.kt` with a non-null `attachmentService`,
    *When* `files = listOf(DroppedFileBytes("photo.png", byteArrayOf(...)))` is passed in,
    *Then* the handler calls `attachmentService.attachBytes(bytes = ..., suggestedName = "photo.png", graphRoot = graphRoot)`
    and never calls `attachFilePath`.
  - *Given* the same handler on JVM,
    *When* `files = listOf(java.io.File("/tmp/photo.png"))` is passed in,
    *Then* the handler still calls `attachmentService.attachFilePath(filePath = "/tmp/photo.png", graphRoot = graphRoot)`
    exactly as it does today — unchanged JVM behavior (AC6).
- The `ifLeft` branch is byte-for-byte unchanged from its pre-project form.
  - *Given* a diff of `App.kt`'s `onFileDrop` handler after this project,
    *When* a reviewer inspects the `ifLeft = { err -> ... }` block,
    *Then* it contains only the existing `graphContentLogger.warn("Drag-and-drop attachment
    failed: $err")` call — no `notificationManager`/toast call has been added here (AC6).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.1.2a: Branch `onFileDrop` on payload type (~5 min)
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, inside the `onFileDrop`
  lambda's `files.forEach { file -> ... }` (currently lines 1206-1224), replace the single
  `attachmentService.attachFilePath(filePath = file.toString(), graphRoot = graphRoot)` call with:
  ```kotlin
  val result = when (file) {
      is dev.stapler.stelekit.service.DroppedFileBytes ->
          attachmentService.attachBytes(
              bytes = file.bytes,
              suggestedName = file.suggestedName,
              graphRoot = graphRoot
          )
      else ->
          attachmentService.attachFilePath(filePath = file.toString(), graphRoot = graphRoot)
  } ?: return@forEach
  ```
  Leave the surrounding `result.fold(...)` — including the `ifLeft` branch — completely
  unchanged in this task and in this project; do not add a toast call here (see the story's
  scope note above).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

#### Story 1.1.3: Correct the `pageDropTarget` doc comment

**As a** future reader of `PageDropTargetModifier.kt`, **I want** the doc comment to reflect that
wasm now supports drop, **so that** the code and its documentation don't contradict each other
(requirements.md AC5).

**Acceptance Criteria**:
- The doc comment no longer states WASM is a no-op.
  - *Given* `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTargetModifier.kt`,
    *When* a reader reads the doc comment after this change,
    *Then* it states that WASM captures OS file drops via a `document.body`-level JS listener
    (not "no-op"), and that Android/iOS remain no-ops.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTargetModifier.kt`

##### Task 1.1.3a: Update the doc comment (~2 min)
- Replace line 15 ("On Android, iOS, and WASM, this is a no-op and returns the receiver
  unchanged.") with two sentences: one covering WASM's new behavior (canvas-covering
  `document.body` drop listener, filtered to image extensions, same as JVM), one covering
  Android/iOS remaining no-ops.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTargetModifier.kt`

#### Story 1.1.4: Thread an optional `NotificationManager` seam through `StelekitApp`

**As a** wasm build, **I want** `StelekitApp` to accept an externally-constructed
`NotificationManager` instead of always creating its own, **so that** `browser/Main.kt` can share
one instance between the composable's `NotificationOverlay` and `WasmMediaAttachmentService`
(Story 3.1.2), without any other platform's behavior changing.

**Acceptance Criteria**:
- JVM/Android/iOS callers, which never pass this parameter, get byte-for-byte identical behavior.
  - *Given* `StelekitApp`'s call sites on JVM/Android/iOS (none of which pass
    `notificationManager`),
    *When* `StelekitApp` is composed,
    *Then* it constructs its own `NotificationManager` via `remember { NotificationManager() }` —
    the exact same expression, same lifecycle, as today (AC6: no behavior change).
- A caller can supply its own instance.
  - *Given* wasm's `browser/Main.kt` constructs a `NotificationManager` and passes it as
    `StelekitApp(..., notificationManager = sharedNotificationManager)`,
    *When* `StelekitApp` composes,
    *Then* it uses that exact instance (not a new one) for both `NotificationOverlay` and every
    internal reference currently named `notificationManager`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 1.1.4a: Add `notificationManager` parameter to `StelekitApp` (~3 min)
- In `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, change the existing local
  `val notificationManager = remember { NotificationManager() }` (line 301) into a composable
  parameter on `StelekitApp` with the same expression as its default value:
  ```kotlin
  fun StelekitApp(
      // ...existing params...
      notificationManager: NotificationManager = remember { NotificationManager() },
  ) {
  ```
  Remove the old local `val` declaration; every existing internal usage of `notificationManager`
  (already-in-scope at lines 322, 1171, 1342 per Story 1.1.2's original investigation) now
  resolves to the parameter instead of the local — no other call-site changes required since
  Compose evaluates the default expression identically to the old local `remember` call for every
  caller that omits the argument.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

---

## Phase 2: WASM OPFS byte I/O plumbing

### Epic 2.1: `ArrayBuffer` ↔ `ByteArray` bridge

**Goal**: A bulk (not per-byte) conversion between JS `ArrayBuffer`/base64 and Kotlin
`ByteArray`, per ADR-002.

#### Story 2.1.1: `ByteBufferInterop` Base64 bridge

**As a** wasm attachment pipeline, **I want** to move bytes across the Wasm↔JS boundary without
one call per byte, **so that** multi-MB images don't hit the performance cliff research/pitfalls.md
flags.

**Acceptance Criteria**:
- A `ByteArray` round-trips through the bridge unchanged.
  - *Given* `val original = ByteArray(50_000) { (it % 256).toByte() }` (a 50 KB buffer covering
    every byte value),
    *When* `val jsBuf = original.toBase64Js()` then `val decoded = jsBuf.fromBase64ToByteArray()`
    (exact function names per Task 2.1.1a) are called in sequence,
    *Then* `decoded.contentEquals(original)` is `true`.
- The bridge crosses the JS boundary once per call, not once per byte.
  - *Given* the implementation in `ByteBufferInterop.kt`,
    *When* a reviewer inspects the function bodies,
    *Then* no function contains a Kotlin `for`/`while` loop that calls an `external fun` once
    per array index — encode/decode loops run inside a single `js("...")` block or via
    `kotlin.io.encoding.Base64` (pure Kotlin stdlib, no boundary crossing per byte).

**Files**: new `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/ByteBufferInterop.kt`

##### Task 2.1.1a: `ByteArray` → base64 → JS `Uint8Array` (write direction) (~5 min)
- Create `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/ByteBufferInterop.kt`,
  package `dev.stapler.stelekit.platform`. Implement, following `OpfsInterop.kt`'s style:
  ```kotlin
  import kotlin.io.encoding.Base64
  import kotlin.io.encoding.ExperimentalEncodingApi

  private fun base64ToUint8Array(base64: String): JsAny = js("""
      (function() {
          var binary = atob(base64);
          var len = binary.length;
          var bytes = new Uint8Array(len);
          for (var i = 0; i < len; i++) { bytes[i] = binary.charCodeAt(i); }
          return bytes;
      })()
  """)

  @OptIn(ExperimentalEncodingApi::class)
  internal fun ByteArray.toJsUint8Array(): JsAny = base64ToUint8Array(Base64.encode(this))
  ```
  Confirm `kotlin.io.encoding.Base64`/`ExperimentalEncodingApi` compiles for the `wasmJs` target
  at this repo's Kotlin 2.3.21 pin during implementation (stdlib API, expected to be available —
  flagged here as the one assumption this task depends on).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/ByteBufferInterop.kt`

##### Task 2.1.1b: JS `ArrayBuffer` → base64 → `ByteArray` (read direction) (~5 min)
- In the same file, add the reverse direction:
  ```kotlin
  private fun arrayBufferToBase64(buffer: JsAny): String = js("""
      (function() {
          var bytes = new Uint8Array(buffer);
          var binary = '';
          var chunk = 0x8000;
          for (var i = 0; i < bytes.length; i += chunk) {
              binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
          }
          return btoa(binary);
      })()
  """)

  @OptIn(ExperimentalEncodingApi::class)
  internal fun jsArrayBufferToByteArray(buffer: JsAny): ByteArray =
      Base64.decode(arrayBufferToBase64(buffer))
  ```
  (Chunked `subarray`/`fromCharCode.apply` avoids the argument-count ceiling `String.fromCharCode`
  hits on very large single calls — standard base64-encode-ArrayBuffer idiom.)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/ByteBufferInterop.kt`

### Epic 2.2: OPFS byte write + async dedup

**Goal**: Write bytes into OPFS with the `Either`-safe, non-swallowing contract
research/pitfalls.md §1 requires, and dedup filenames against live OPFS state.

#### Story 2.2.1: `opfsWriteFileBytes` — error-propagating byte write

**As a** `WasmMediaAttachmentService`, **I want** an OPFS byte-write function that surfaces
failures instead of swallowing them, **so that** a failed write never returns
`Either.Right` (requirements.md AC2, non-functional Either constraint).

**Acceptance Criteria**:
- A successful write lands bytes in OPFS before the function returns.
  - *Given* an OPFS root with no existing `assets/` directory under graph path `/stelekit/default`,
    *When* `opfsWriteFileBytes("/stelekit/default/assets/photo.png", bytes)` is called and awaited,
    *Then* a subsequent `getFileHandle(assetsDir, "photo.png", create = false)` succeeds (the
    file exists) — proven by Task 5.1.1a's test.
- A failure (e.g. simulated quota error) propagates as a thrown `Throwable`, not a swallowed log line.
  - *Given* `fileHandleCreateWritable`/`writableWrite`/`writableClose` calls that reject their
    `Promise`,
    *When* `opfsWriteFileBytes(...)` is called,
    *Then* the `Throwable` propagates out of `opfsWriteFileBytes` uncaught (unlike the existing
    `opfsWriteFile`, which never throws) — the catch/convert-to-`Either.Left` step is the caller's
    responsibility (`WasmMediaAttachmentService.attachBytes`, Task 3.1.1a).

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`

##### Task 2.2.1a: Add `writableWriteBytes` and `opfsWriteFileBytes` (~5 min)
- In `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`, add beside the
  existing `writableWrite` (line 51):
  ```kotlin
  private fun writableWriteBytes(writable: JsAny, bytes: JsAny): kotlin.js.Promise<JsAny> =
      js("writable.write(bytes)")
  ```
  Then add a new function beside `opfsWriteFile` (after line 71), mirroring its
  directory-walk-with-create logic but **without** the `try/catch`/`println` swallow:
  ```kotlin
  internal suspend fun opfsWriteFileBytes(path: String, bytes: ByteArray) {
      val root = getOpfsRoot()
      val parts = path.removePrefix("/").split("/")
      var dir: JsAny = root
      for (part in parts.dropLast(1)) {
          dir = getDirectoryHandle(dir, part, true)
      }
      val fileName = parts.last()
      val fileHandle = getFileHandle(dir, fileName, true)
      val writable: JsAny = fileHandleCreateWritable(fileHandle).await()
      writableWriteBytes(writable, bytes.toJsUint8Array()).await()
      writableClose(writable).await()
  }
  ```
  Add the import for `toJsUint8Array` from `ByteBufferInterop.kt` (Task 2.1.1a). No `try/catch`
  here by design — see Story 2.2.1's acceptance criteria.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`

#### Story 2.2.2: `uniqueOpfsFileName` async dedup

**As a** `WasmMediaAttachmentService`, **I want** a suffix-counter dedup check against live OPFS
state, **so that** dropped images never overwrite an existing same-named asset (requirements.md
AC2's "existing duplicate-filename suffix convention").

**Acceptance Criteria**:
- No existing file with the base name: returns the base name unchanged.
  - *Given* an OPFS `assets/` directory containing no `photo.png`,
    *When* `uniqueOpfsFileName("/stelekit/default/assets", "photo", "png")` is called,
    *Then* it returns `"photo.png"`.
- Existing file with the base name: returns the first available suffixed name.
  - *Given* an OPFS `assets/` directory that already contains `photo.png` and `photo-1.png`,
    *When* `uniqueOpfsFileName("/stelekit/default/assets", "photo", "png")` is called,
    *Then* it returns `"photo-2.png"`.
- Sequential calls within the same drop batch see each other's writes (matches JVM's
  sequential-not-parallel processing per research/features.md §2 point 4).
  - *Given* two files named `photo.png` dropped together,
    *When* `App.kt`'s `files.forEach` processes them sequentially (Task 1.1.2a, unchanged
    sequential loop) and each call to `attachBytes` awaits `uniqueOpfsFileName` then
    `opfsWriteFileBytes` before the next file starts,
    *Then* the first is written as `photo.png` and the second as `photo-1.png` — no collision.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`

##### Task 2.2.2a: Add `opfsFileExists` existence probe (~3 min)
- In `OpfsInterop.kt`, add a function that walks to the given directory and probes for a file
  via the existing `getFileHandle(parent, name, create = false)`, treating any thrown
  `Throwable` (the browser's `NotFoundError`) as "does not exist":
  ```kotlin
  internal suspend fun opfsFileExists(dirPath: String, fileName: String): Boolean = try {
      val root = getOpfsRoot()
      var dir: JsAny = root
      for (part in dirPath.removePrefix("/").split("/")) {
          dir = getDirectoryHandle(dir, part, true)
      }
      getFileHandle(dir, fileName, false)
      true
  } catch (e: Throwable) {
      false
  }
  ```
  Note: `getDirectoryHandle(..., create = true)` here is intentional — this is also how the
  assets directory gets created on first use (mirroring JVM's `assetsDir.mkdirs()`), so the
  dedup probe and directory creation share one walk.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`

##### Task 2.2.2b: Add `uniqueOpfsFileName` (~4 min)
- In the same file, add (reusing `sanitizeFileNameComponent` from commonMain's
  `AttachmentFileNaming.kt`, which is `internal` and visible to wasmJsMain within the same
  Gradle module):
  ```kotlin
  internal suspend fun uniqueOpfsFileName(dirPath: String, stem: String, ext: String): String {
      val safeStem = dev.stapler.stelekit.service.sanitizeFileNameComponent(stem, fallback = "attachment")
      val safeExt = dev.stapler.stelekit.service.sanitizeFileNameComponent(ext, fallback = "")
      val base = if (safeExt.isBlank()) safeStem else "$safeStem.$safeExt"
      if (!opfsFileExists(dirPath, base)) return base
      var counter = 1
      while (true) {
          val candidate = if (safeExt.isBlank()) "$safeStem-$counter" else "$safeStem-$counter.$safeExt"
          if (!opfsFileExists(dirPath, candidate)) return candidate
          counter++
      }
  }
  ```
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`

---

## Phase 3: `WasmMediaAttachmentService`

### Epic 3.1: Service implementation

**Goal**: A `MediaAttachmentService` implementation that turns `attachBytes` calls into OPFS
writes with correct `Either` semantics.

#### Story 3.1.1: `WasmMediaAttachmentService.attachBytes`

**As a** wasm build, **I want** `attachBytes` to write bytes into OPFS `assets/` and return the
relative markdown path, matching JVM's `AttachmentResult` shape, **so that** the dropped image
renders via the same `![alt](../assets/name)` convention on every platform.

**Acceptance Criteria**:
- Successful write returns `Either.Right(AttachmentResult(relativePath = "../assets/<uniqueName>", displayName = <uniqueName>))`.
  - *Given* `graphRoot = "/stelekit/default"` and `DroppedFileBytes("screenshot.png", <bytes>)`
    with no existing `screenshot.png` in `assets/`,
    *When* `attachBytes(bytes, "screenshot.png", "/stelekit/default")` is called,
    *Then* it returns `Either.Right(AttachmentResult("../assets/screenshot.png", "screenshot.png"))`.
- OPFS failure returns `Either.Left`, never throws past the service boundary.
  - *Given* `opfsWriteFileBytes` throws (e.g. simulated `QuotaExceededError`),
    *When* `attachBytes(...)` is called,
    *Then* it returns `Either.Left(DomainError.AttachmentError.CopyFailed("QuotaExceededError"))`
    and no exception propagates out of `attachBytes`.
- Runs on `PlatformDispatcher.IO`, matching every other platform's attach method (consistency,
  per `kmp/CLAUDE.md`'s dispatcher matrix — no thread-isolation benefit on wasmJs, but keeps the
  call shape uniform across platforms).

**Files**: new `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`

##### Task 3.1.1a: Implement `WasmMediaAttachmentService` (~5 min)
- Create `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`,
  package `dev.stapler.stelekit.service`, matching `IosMediaAttachmentService`'s
  "only-implement-what's-supported" shape:
  ```kotlin
  class WasmMediaAttachmentService : MediaAttachmentService {

      override suspend fun pickAndAttach(
          graphRoot: String,
          pageRelativePath: String
      ): Either<DomainError, AttachmentResult>? = null // no file picker yet — see follow-ups

      override suspend fun attachBytes(
          bytes: ByteArray,
          suggestedName: String,
          graphRoot: String
      ): Either<DomainError, AttachmentResult> = withContext(PlatformDispatcher.IO) {
          try {
              val assetsDirPath = "$graphRoot/assets"
              val stem = suggestedName.substringBeforeLast('.', suggestedName)
              val ext = suggestedName.substringAfterLast('.', "")
              val uniqueName = uniqueOpfsFileName(assetsDirPath, stem, ext)
              opfsWriteFileBytes("$assetsDirPath/$uniqueName", bytes)
              AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
          } catch (e: CancellationException) {
              throw e
          } catch (e: Throwable) {
              DomainError.AttachmentError.CopyFailed(e.message ?: "OPFS write failed").left()
          }
      }
  }
  ```
  Import `dev.stapler.stelekit.platform.opfsWriteFileBytes`,
  `dev.stapler.stelekit.platform.uniqueOpfsFileName`, `arrow.core.{Either,left,right}`,
  `dev.stapler.stelekit.coroutines.PlatformDispatcher`, `kotlinx.coroutines.{CancellationException,withContext}`.
  Note: Task 3.1.2a (below) adds a constructor parameter and one call to this class — shown here
  without it for clarity of the base write/dedup logic first.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`

#### Story 3.1.2: Wasm attach-failure toast (cross-artifact-review fix)

**As a** user dropping an image on the web build, **I want** to see a visible error if the drop
actually fails, **so that** OPFS-specific failures (e.g. quota exceeded) aren't silently
swallowed on the one platform where the existing log-only signal is invisible to any user
(research/ux.md §4).

> Originally this toast was added to `App.kt`'s shared `onFileDrop` `ifLeft` branch (draft Task
> 1.1.2b) — cross-artifact-consistency review flagged that branch as non-wasm-gated shared code,
> so a toast there would have changed JVM/Android/iOS's existing log-only failure behavior, a
> regression forbidden by requirements.md AC6. This story replaces that design: the toast is
> triggered from inside `WasmMediaAttachmentService.attachBytes` itself — code that only ever
> runs on wasm — via a `NotificationManager` instance injected at construction (Story 1.1.4,
> Task 3.2a). `design/ux.md`'s Surface 2 description ("App.kt's wasm onFileDrop failure branch")
> should be read as informative of the *design intent* (a toast appears on real attach failure)
> established during the design-review phase, not as the literal call site — `design/ux.md` is
> not being edited as part of this fix (out of scope for this patch), and this plan.md section is
> the source of truth for where the call actually lives.

**Acceptance Criteria**:
- A real (non-cancelled) attach failure inside `WasmMediaAttachmentService.attachBytes` shows a
  user-visible toast, using the exact message text/styling the original design called for.
  - *Given* `opfsWriteFileBytes` throws (e.g. simulated `QuotaExceededError`),
    *When* `attachBytes(...)`'s `catch (e: Throwable)` branch runs,
    *Then* it calls `notificationManager.show("Image attachment failed: ${e.message ?: "OPFS
    write failed"}", NotificationType.ERROR)` **before** returning
    `DomainError.AttachmentError.CopyFailed(...).left()`.
- No other platform's `MediaAttachmentService` implementation gains a toast call.
  - *Given* `JvmMediaAttachmentService`/`AndroidMediaAttachmentService`/`IosMediaAttachmentService`,
    *When* a reviewer diffs this project's changes,
    *Then* none of those files are touched by this story (AC6) — the toast is exclusive to
    `WasmMediaAttachmentService`.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`

##### Task 3.1.2a: Inject `NotificationManager` and call `.show(...)` on failure (~3 min)
- In `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`
  (Task 3.1.1a), add a constructor parameter and use it in the `catch (e: Throwable)` branch:
  ```kotlin
  class WasmMediaAttachmentService(
      private val notificationManager: dev.stapler.stelekit.ui.NotificationManager,
  ) : MediaAttachmentService {
      // ...pickAndAttach unchanged...

      override suspend fun attachBytes(
          bytes: ByteArray,
          suggestedName: String,
          graphRoot: String
      ): Either<DomainError, AttachmentResult> = withContext(PlatformDispatcher.IO) {
          try {
              // ...unique-name + write logic unchanged...
              AttachmentResult(relativePath = "../assets/$uniqueName", displayName = uniqueName).right()
          } catch (e: CancellationException) {
              throw e
          } catch (e: Throwable) {
              val message = e.message ?: "OPFS write failed"
              notificationManager.show(
                  "Image attachment failed: $message",
                  dev.stapler.stelekit.model.NotificationType.ERROR
              )
              DomainError.AttachmentError.CopyFailed(message).left()
          }
      }
  }
  ```
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentService.kt`

### Epic 3.2: Wire into `browser/Main.kt`

#### Story 3.2.1: Construct and pass `WasmMediaAttachmentService` and a shared `NotificationManager`

**As a** user of the real (OPFS-backed) web build, **I want** `StelekitApp` to receive a
non-null `attachmentService`, and that service to share the same `NotificationManager` instance
`StelekitApp` renders toasts from, **so that** `onFileDrop`/toolbar/paste all activate (only
`onFileDrop` is wired to a UI entry point this project builds — see Explicitly Out of Scope) and
Story 3.1.2's failure toast is actually observed by the UI it's meant to appear in.

**Acceptance Criteria**:
- OPFS-backed graphs get a non-null `attachmentService`, constructed with a `NotificationManager`
  that is the *same instance* passed to `StelekitApp`; the demo (non-OPFS) fallback gets neither.
  - *Given* `browser/Main.kt`'s existing `useDemoFallback` flag is `false` (OPFS/SQLite driver
    initialized successfully),
    *When* `main()` runs,
    *Then* one `NotificationManager()` is constructed and passed as both
    `WasmMediaAttachmentService(notificationManager = sharedNotificationManager)`'s constructor
    argument and `StelekitApp(..., notificationManager = sharedNotificationManager,
    attachmentService = thatService)`'s parameter — not two separate instances.
  - *Given* `useDemoFallback` is `true` (OPFS unavailable, demo graph in use),
    *When* `main()` runs,
    *Then* `StelekitApp(..., attachmentService = null)` is called with `StelekitApp`'s
    `notificationManager` parameter omitted (falls back to its own `remember`-scoped instance per
    Story 1.1.4) — unchanged from today, consistent with the demo graph not being OPFS-backed.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 3.2a: Pass `attachmentService` and a shared `notificationManager` conditionally in `Main.kt` (~4 min)
- In `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`, add
  `import dev.stapler.stelekit.service.WasmMediaAttachmentService` and
  `import dev.stapler.stelekit.ui.NotificationManager`. In the `ComposeViewport` block
  (lines 84-90), add:
  ```kotlin
  val sharedNotificationManager = remember { NotificationManager() }
  StelekitApp(
      fileSystem = fileSystem,
      graphPath = graphPath,
      graphManager = graphManager,
      notificationManager = sharedNotificationManager,
      attachmentService = if (useDemoFallback) {
          null
      } else {
          WasmMediaAttachmentService(notificationManager = sharedNotificationManager)
      },
  )
  ```
  `remember` requires `ComposeViewport`'s composable scope, which this call site already runs
  inside — confirm during implementation that `sharedNotificationManager` is constructed once per
  composition, not once per recomposition (same `remember` semantics `App.kt` already relied on
  before Task 1.1.4a's change).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

---

## Phase 4: JS drop-event capture

### Epic 4.1: `document.body`-level drop listener

**Goal**: Replace the wasmJs `pageDropTarget` no-op with a real implementation per Approach A.

#### Story 4.1.1: `DropZoneInterop` — raw JS glue

**As a** wasm drop target, **I want** `dragenter`/`dragover`/`drop` handled on `document.body`
with `preventDefault()` called on all three, **so that** the browser never navigates away with
the dropped file and `drop` reliably fires (research/pitfalls.md §2, research/ux.md §2).

**Acceptance Criteria**:
- `dragover` (and `dragenter`) call `preventDefault()`.
  - *Given* the listener installed via `installBodyDropListener` (Task 4.1.1a),
    *When* a file is dragged over `document.body`,
    *Then* the browser's default "reject as drop target" action does not fire (verified via the
    manual browser check in Task 5.2.1a — this is DOM behavior, not something a headless
    Kotlin/Wasm unit test can observe without a real event dispatch).
- `drop` calls `preventDefault()` and extracts only image-extension files.
  - *Given* a drop event with `dataTransfer.files = [photo.jpg, notes.txt]`,
    *When* the `drop` handler runs,
    *Then* only `photo.jpg`'s `File` handle is forwarded to the Kotlin callback — `notes.txt` is
    filtered out before any `arrayBuffer()` read is attempted (matches JVM's pre-filter,
    requirements.md AC3).
- A drop outside any page view — i.e. no active handler registered — produces no JS error.
  - *Given* no `pageDropTarget` composable is currently composed (e.g. on the graph-picker
    screen, not a page view),
    *When* a file is dropped on `document.body`,
    *Then* `preventDefault()` still runs (no navigation-away) and the handler no-ops silently if
    no active callback is registered — no thrown error, no console exception (requirements.md
    AC4).

**Files**: new `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/DropZoneInterop.kt`

##### Task 4.1.1a: JS listener registration + file/name extraction (~5 min)
- Create `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/DropZoneInterop.kt`,
  package `dev.stapler.stelekit.ui.components`. Declare, following `SqliteWorkerInterop.kt`'s
  style of passing a Kotlin function reference as a JS callback:
  ```kotlin
  internal fun installBodyDropListener(onFiles: (JsAny) -> Unit): Unit = js("""
      (function() {
          document.body.addEventListener('dragenter', function(e) { e.preventDefault(); });
          document.body.addEventListener('dragover', function(e) { e.preventDefault(); });
          document.body.addEventListener('drop', function(e) {
              e.preventDefault();
              onFiles(Array.from(e.dataTransfer.files));
          });
      })()
  """)

  internal fun jsFileArrayLength(arr: JsAny): Int = js("arr.length | 0")
  internal fun jsFileArrayGet(arr: JsAny, index: Int): JsAny = js("arr[index]")
  internal fun jsFileName(file: JsAny): String = js("file.name")
  private fun fileArrayBufferPromise(file: JsAny): kotlin.js.Promise<JsAny> = js("file.arrayBuffer()")

  internal suspend fun readFileBytes(file: JsAny): ByteArray {
      val buffer = fileArrayBufferPromise(file).await()
      return dev.stapler.stelekit.platform.jsArrayBufferToByteArray(buffer)
  }
  ```
  `installBodyDropListener` must be called exactly once (idempotency enforced in Task 4.1.2a, not
  here) — this file only declares the raw glue.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/DropZoneInterop.kt`

##### Task 4.1.1b: Add wasm-local `IMAGE_EXTENSIONS` filter (~2 min)
- In the same file, add a private extension-filter constant matching JVM's list verbatim:
  ```kotlin
  private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp")
  internal fun String.isImageFileName(): Boolean =
      substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
  ```
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/DropZoneInterop.kt`

#### Story 4.1.2: Rewrite `pageDropTarget` actual — Compose-facing wiring

**As a** `PageView`, **I want** `.pageDropTarget(capabilities.onFileDrop)` to actually deliver
`DroppedFileBytes` when a real image is dropped, **so that** `App.kt`'s existing handler inserts
markdown exactly as it does on JVM (requirements.md AC1).

**Acceptance Criteria**:
- Dropping a single valid image inserts markdown into the target block.
  - *Given* a composed `PageView` for page UUID `"page-abc"` with `capabilities.onFileDrop`
    non-null (i.e. `attachmentService` is non-null per Story 3.2.1),
    *When* the user drags `vacation.jpg` (2 MB) from their OS file manager onto the page and
    drops it,
    *Then* `blockStateManager.addBlockWithContent(pageUuid = "page-abc", content = "![vacation.jpg](../assets/vacation.jpg)")`
    is invoked (via the unchanged `App.kt` handler from Task 1.1.2a), matching JVM's behavior for
    the same interaction.
- The listener is installed once, not once per recomposition/page navigation.
  - *Given* the user navigates from page A to page B (both calling `.pageDropTarget(...)` during
    their own composition),
    *When* a file is then dropped while page B is showing,
    *Then* `onFilesDropped` fires exactly once (not once per prior page's now-stale registration)
    — verified by the active-handler-swap design in Task 4.1.2a, not a duplicate
    `addEventListener` call.
- The coroutine that reads file bytes and calls the Kotlin callback does not use
  `rememberCoroutineScope()` (per `kmp/CLAUDE.md`'s scope-ownership rule).
  - *Given* the implementation in Task 4.1.2a,
    *When* a reviewer inspects it,
    *Then* the `CoroutineScope` used to launch `readFileBytes`/`activeDropHandler.invoke(...)` is
    a module-level `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (matching
    `PlatformFileSystem.kt:11`'s existing precedent), not a value obtained from
    `rememberCoroutineScope()`.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt`

##### Task 4.1.2a: Rewrite `PageDropTarget.kt` actual (~5 min)
- Replace the full 8-line no-op body of
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt` with:
  ```kotlin
  package dev.stapler.stelekit.ui.components

  import androidx.compose.runtime.DisposableEffect
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.composed
  import dev.stapler.stelekit.logging.Logger
  import dev.stapler.stelekit.service.DroppedFileBytes
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.launch

  private val dropScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  // Matches App.kt's "GraphContent" tag convention for drag-and-drop-related logging (this file
  // has no access to App.kt's composable-local `graphContentLogger`, which is scoped inside
  // `StelekitApp`, not a top-level singleton).
  private val logger = Logger("GraphContent")
  // internal (not private): PageDropTargetListenerLifecycleTest (wasmJsTest, same module) drives
  // these directly to verify listener-swap behavior without a full Compose composition.
  internal var activeDropHandler: ((List<Any>) -> Unit)? = null
  internal var listenerInstalled = false

  internal fun ensureListenerInstalled() {
      if (listenerInstalled) return
      listenerInstalled = true
      installBodyDropListener { fileArray ->
          dropScope.launch {
              val handler = activeDropHandler ?: return@launch
              val count = jsFileArrayLength(fileArray)
              val dropped = mutableListOf<DroppedFileBytes>()
              for (i in 0 until count) {
                  val file = jsFileArrayGet(fileArray, i)
                  val name = jsFileName(file)
                  if (!name.isImageFileName()) continue
                  // Per-file try/catch: an unreadable file (e.g. a mid-drag permission error)
                  // must not abort the rest of a multi-file drop batch (architecture-review.md).
                  try {
                      dropped.add(DroppedFileBytes(name, readFileBytes(file)))
                  } catch (e: CancellationException) {
                      throw e
                  } catch (e: Throwable) {
                      logger.warn("Failed to read dropped file bytes for \"$name\": ${e.message}")
                  }
              }
              if (dropped.isNotEmpty()) handler(dropped)
          }
      }
  }

  actual fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier = composed {
      activeDropHandler = onFilesDropped
      DisposableEffect(Unit) {
          ensureListenerInstalled()
          onDispose { if (activeDropHandler === onFilesDropped) activeDropHandler = null }
      }
      this
  }
  ```
  Note the sequential `for` loop (not `map`/parallel) over dropped files, matching JVM's
  sequential processing so `uniqueOpfsFileName` dedup correctly sees earlier files in the same
  batch (Story 2.2.2's third acceptance criterion) — the actual dedup happens later in
  `WasmMediaAttachmentService.attachBytes`, called sequentially by `App.kt`'s unchanged
  `files.forEach`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt`

---

## Phase 5: Tests & verification

### Epic 5.1: wasmJsTest coverage

**Goal**: Cover every piece of new logic that does *not* require a live DOM drag event, using
the existing `wasmJsTest` source set (already present — `WasmBenchmarkTest.kt` — and run via
`wasmJsBrowserTest`, which executes in real headless Chromium per that file's own doc comment,
meaning real OPFS APIs are available in-test, not just pure data-structure checks).

#### Story 5.1.1: `WasmMediaAttachmentServiceTest` — real-OPFS round trip

**As a** developer changing this code later, **I want** an automated test that writes real bytes
through `WasmMediaAttachmentService` into headless-Chromium OPFS and reads them back, **so that**
a regression in the write/dedup path is caught by `wasmJsBrowserTest`, not just manual browser
testing.

**Acceptance Criteria**:
- A first attach with a fresh name succeeds and the file is readable back from OPFS.
  - *Given* a clean OPFS state under a unique test graph root (e.g.
    `/stelekit-test/<random-uuid>`),
    *When* `WasmMediaAttachmentService(notificationManager = NotificationManager()).attachBytes(byteArrayOf(1,2,3,4), "test.png", graphRoot)`
    is called (Task 3.1.2a's constructor parameter — the test's `NotificationManager` instance is
    unused/unobserved here, since this story only asserts the `Either` return value, not the
    toast),
    *Then* it returns `Either.Right(AttachmentResult("../assets/test.png", "test.png"))`, and a
    direct `readOpfsFile`-style read of `$graphRoot/assets/test.png` (via a byte-read helper) 
    returns `byteArrayOf(1,2,3,4)`.
- A second attach with the same suggested name gets the `-1` suffix.
  - *Given* the state after the previous criterion (`test.png` already exists),
    *When* `attachBytes(byteArrayOf(5,6), "test.png", graphRoot)` is called again,
    *Then* it returns `Either.Right(AttachmentResult("../assets/test-1.png", "test-1.png"))`.

**Files**: new `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentServiceTest.kt`

##### Task 5.1.1a: Write the OPFS round-trip test (~5 min)
- Create `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentServiceTest.kt`
  using `kotlin.test.Test`/`assertEquals`/`assertTrue`, following `WasmBenchmarkTest.kt`'s
  structure. Use a per-test unique graph root (e.g. a random suffix) to avoid cross-test OPFS
  state leakage, since `wasmJsBrowserTest` persists real OPFS state across tests in the same
  browser session.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/service/WasmMediaAttachmentServiceTest.kt`

#### Story 5.1.2: `ByteBufferInterop` round-trip test

**As a** developer, **I want** the Base64 bridge (ADR-002) covered by a test independent of
OPFS, **so that** a future encode/decode bug (e.g. chunking off-by-one) is caught directly.

**Acceptance Criteria**:
- Arbitrary bytes (including `0x00` and `0xFF`) round-trip exactly.
  - *Given* `val original = ByteArray(70_000) { (it * 7 % 256).toByte() }` (70 KB, exceeds one
    `String.fromCharCode` chunk per Task 2.1.1b's 0x8000 chunk size, covering the chunking edge),
    *When* the bytes are written to a real OPFS file via `opfsWriteFileBytes` and read back via
    `jsArrayBufferToByteArray(file.arrayBuffer())`,
    *Then* the round-tripped array equals `original` via `contentEquals`.

**Files**: new `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/ByteBufferInteropTest.kt`

##### Task 5.1.2a: Write the byte-bridge round-trip test (~4 min)
- Create `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/ByteBufferInteropTest.kt`,
  exercising `opfsWriteFileBytes` + a byte-read path (add a small `readOpfsFileBytes` test-only
  helper in the same file, or reuse `jsArrayBufferToByteArray` directly against a `getFile()`
  handle) to prove the full write→read cycle, not just the pure conversion functions in
  isolation — this is the test that specifically targets the 70KB chunking boundary from
  Task 2.1.1b.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/ByteBufferInteropTest.kt`

#### Story 5.1.3: Realistic-size Base64 bridge measurement gate (pre-mortem P1 #2)

**As a** reviewer of ADR-002, **I want** the Base64 bridge's "~33% overhead, negligible" claim
measured against a realistic phone-photo size — not just the 50-70 KB fixtures Stories 2.1.1 and
5.1.2 use — **so that** a real main-thread freeze or tab crash on an 8-10 MB image isn't
discovered by a user after ship.

> **Why this matters, briefly**: ADR-002's overhead estimate is the raw ~33% Base64 encoding
> inflation. It does not account for the JS-side string being stored as UTF-16 (roughly another
> 2x on top of the ASCII Base64 payload), nor for the fact that the original `ByteArray`, the
> Base64 `String`, and the decoded `Uint8Array` are all live in memory simultaneously during the
> synchronous `atob`/`fromCharCode`-chunking JS loop (Task 2.1.1a/2.1.1b) — which runs on the
> single UI thread. This was never measured at a realistic size; pre-mortem.md flags it P1.

**Acceptance Criteria**:
- Wall-clock round-trip time and (where measurable) peak memory are recorded for a realistic
  image size, with a stated pass/fail threshold checked before this project ships.
  - *Given* a synthetic or real JPEG in the 5-10 MB range (e.g. `ByteArray(8_000_000) { ... }` for
    an automated lower bound, plus one real photo-sized file for the manual check),
    *When* the full write path is exercised — `ByteArray.toJsUint8Array()` (or the
    `ByteArray`→OPFS write direction) → `opfsWriteFileBytes` → read back via
    `jsArrayBufferToByteArray` — timed end-to-end,
    *Then* the measured wall-clock time is **< 3 seconds** and a manual browser check (DevTools
    Performance panel, dragging the real photo-sized file onto the page) shows **no visible UI
    freeze** (page remains responsive to input; no "Page Unresponsive" browser dialog) for an
    8 MB JPEG. These are this project's explicit pass/fail thresholds — adjust only with a stated
    reason if implementation surfaces better data (e.g. actual median dropped-photo size from
    research/ux.md, if that changes).
- A failing measurement blocks ship and triggers ADR-002 reconsideration, not a silent shrug.
  - *Given* the measurement above fails the threshold,
    *When* Task 5.1.3a is executed,
    *Then* the task is marked failed (not skipped), and the documented fallback is a real
    `Uint8Array` bulk-transfer implementation (the alternative both research/stack.md §2 and
    research/build-vs-buy.md flagged, e.g. via `kotlinx-browser`'s typed-array adapters or a
    hand-rolled bulk `Int8Array` copy) rather than the Base64 string bridge — this becomes a
    required follow-up task before the project can be marked shipped, not an optional fast-follow.

**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/ByteBufferInteropTest.kt`
(extended), plus a manual verification step (no source changes for the manual half).

##### Task 5.1.3a: Measure and record realistic-size (5-10 MB) round-trip time/memory (~8 min)
- Extend `ByteBufferInteropTest.kt` (Task 5.1.2a) with a second test using an 8 MB synthetic
  `ByteArray`, wrapping the write→read round trip in `kotlin.time.measureTime` (or equivalent) and
  asserting the elapsed time is under the 3s threshold above — this gives an automated lower-bound
  signal in `wasmJsBrowserTest`'s headless Chromium, run on every CI execution once Story 5.2.2
  wires it in.
- Separately (manual, since headless Chromium's DevTools Performance panel isn't scriptable from
  `kotlin.test`), drag a real 8-10 MB JPEG onto a running `wasmJsBrowserDevelopmentRun` build in a
  real browser, watch the tab for visible freezing/"Page Unresponsive," and record the result
  alongside Task 5.2.1a's other verification evidence.
- If either check fails: do not ship. Open a follow-up task to replace the Base64 bridge with a
  bulk `Uint8Array` transfer (see AC above) and revisit ADR-002's "Consequences" section to record
  the measured numbers that triggered the change.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/ByteBufferInteropTest.kt`

### Epic 5.2: Manual/regression verification

#### Story 5.2.1: Full verification command set

**As a** reviewer of this PR, **I want** a single, complete list of commands that prove the
change is correct, **so that** "done" has evidence behind it (requirements.md AC7).

**Acceptance Criteria**:
- All commands below are run and their output captured before this project is marked shipped:
  - *Given* a clean worktree with this project's changes,
    *When* `./gradlew ciCheck` is run,
    *Then* it passes (covers detekt + jvmTest + Android unit tests + assembleDebug — proves no
    regression to JVM/Android per AC6; wasm is not part of `ciCheck` since the `wasmJs` target is
    gated behind `-PenableJs=true` and `ciCheck`'s `dependsOn` list has no wasm task).
  - *Given* the same worktree,
    *When* `./gradlew wasmJsBrowserTest -PenableJs=true` is run,
    *Then* it passes, including the new `WasmMediaAttachmentServiceTest` and
    `ByteBufferInteropTest` (this is the "wasm-specific test task" requirements.md AC7 calls for
    — confirmed to exist via `kmp/src/wasmJsTest`'s existing `WasmBenchmarkTest.kt`). This
    command is also now CI-enforced on every PR, not just developer-run — see Story 5.2.2.
  - *Given* a running wasm dev build (`./gradlew wasmJsBrowserDevelopmentRun -PenableJs=true` or
    equivalent), *When* a manual drag of an image file onto an open page in a real browser is
    performed, *Then* the image markdown is inserted and the file appears under the graph's
    OPFS `assets/` directory (verifiable via browser devtools' Application/Storage panel) — this
    step exists because `DropZoneInterop.kt`'s `dragover`/`drop` DOM behavior (Story 4.1.1's
    first acceptance criterion) cannot be observed by a headless-Chromium unit test without a
    real synthetic `DragEvent`, which `kotlin.test` does not provide tooling for in this repo.

**Files**: none (verification only — no source changes)

##### Task 5.2.1a: Run and record the verification commands (~5 min)
- Run `./gradlew ciCheck`, `./gradlew wasmJsBrowserTest -PenableJs=true`, and the manual
  browser drag-drop check described above; capture pass/fail output for each as this project's
  completion evidence (per this repo's "no completion claim without proof" convention).
- Files: none

#### Story 5.2.2: Wire `wasmJsBrowserTest` into CI (pre-mortem P1 #1)

**As a** maintainer of this repo, **I want** the new `wasmJsTest` suite
(`WasmMediaAttachmentServiceTest`, `ByteBufferInteropTest`) to actually run on every PR, **so
that** a future regression in the OPFS write/dedup path (e.g. a refactor of `OpfsInterop.kt`)
fails CI instead of shipping silently.

> **Decision (explicit, per pre-mortem P1 #1)**: `.github/workflows/ci.yml`'s `wasmjs-compile`
> job (`[gh:ci.yml:248-267]`) currently only runs `:kmp:compileKotlinWasmJs` — a compile check,
> not a test run — and `kmp/build.gradle.kts`'s `ciCheck` task has no wasmJs dependency, so
> Phase 5's new tests would otherwise exist without ever gating a merge. This project adds a
> `wasmJsBrowserTest -PenableJs=true` step to that same job (rather than deferring to a
> developer-run-only note) because the change is small and the job is already isolated and
> gated the same way (`-PenableJs=true`) — it does not require a new job, new secrets, or new
> permissions, so it comfortably fits this project's task-sizing norm even though it runs
> slightly longer than most other tasks here.

**Acceptance Criteria**:
- The `wasmjs-compile` job in `.github/workflows/ci.yml` runs the new wasm test suite, and a
  failure there fails the check.
  - *Given* `.github/workflows/ci.yml`'s `wasmjs-compile` job,
    *When* a PR is opened or updated,
    *Then* the job runs both `./gradlew :kmp:compileKotlinWasmJs --no-daemon --build-cache
    -PenableJs=true` (existing, unchanged) and a new
    `./gradlew :kmp:wasmJsBrowserTest --no-daemon --build-cache -PenableJs=true` step, and the
    job (and therefore the PR's required-checks gate) fails if either step fails.
  - *Given* a regression is introduced into `OpfsInterop.kt`/`WasmMediaAttachmentService.kt`
    after this project ships,
    *When* a PR containing that regression is opened,
    *Then* `WasmMediaAttachmentServiceTest`/`ByteBufferInteropTest` fail in this new CI step,
    blocking merge — closing the exact gap pre-mortem.md's Failure Mode #1 described.

**Files**: `.github/workflows/ci.yml`

##### Task 5.2.2a: Add the `wasmJsBrowserTest` step to `ci.yml`'s `wasmjs-compile` job (~5 min)
- In `.github/workflows/ci.yml`, rename the job's display name from `Wasm/JS Compile` to
  `Wasm/JS Compile & Test` (keep the job id `wasmjs-compile` unchanged to avoid an unrelated
  required-status-check rename), and add a step after the existing "Compile wasmJs target" step:
  ```yaml
  wasmjs-compile:
    name: Wasm/JS Compile & Test
    runs-on: ubuntu-latest
    if: github.event.pull_request.draft == false

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-home-cache-cleanup: true
          cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}

      - name: Compile wasmJs target
        run: ./gradlew :kmp:compileKotlinWasmJs --no-daemon --build-cache -PenableJs=true

      - name: Run wasmJs browser tests
        run: ./gradlew :kmp:wasmJsBrowserTest --no-daemon --build-cache -PenableJs=true

      - name: Publish wasmJs test report
        uses: mikepenz/action-junit-report@v4
        if: always()
        with:
          report_paths: '**/build/test-results/wasmJsBrowserTest/**/TEST-*.xml'
          fail_on_failure: true
          require_tests: true
  ```
  The `Publish wasmJs test report` step mirrors the existing `jvm-test` job's report-publishing
  pattern (`ci.yml` lines 44-49) for consistency; confirm the actual JUnit XML output path during
  implementation (Kotlin/Wasm browser test report paths can differ slightly by Kotlin Gradle
  plugin version — verify against a real local run before assuming the path above is exact).
  Headless Chromium itself needs no extra setup step here: Kotlin/JS's Karma-based test runner
  downloads and manages its own headless Chromium via npm, the same mechanism
  `WasmBenchmarkTest.kt`'s doc comment already relies on for local `wasmJsBrowserTest` runs — no
  `Xvfb`/`chrome-launcher` action needed, unlike the `jvm-test` job's Compose UI screenshot tests.
- Files: `.github/workflows/ci.yml`

---

## Explicitly out of scope (suggested follow-ups only — not tasks in this project)

Per requirements.md's Scope decision, the following are **not** implemented here, and no task
above should be expanded to cover them:

- **Toolbar file picker (`onAttachImage` / `WasmMediaAttachmentService.pickAndAttach`)** — left
  returning `null` (Task 3.1.1a). Genuinely cheap once this project's plumbing lands (a hidden
  `<input type="file" accept="image/*">` + `change` listener, reusing `attachBytes`) — flagged as
  a fast-follow by research/features.md §2 and research/ux.md §3 (closes a WCAG 2.5.7 gap for
  keyboard-only users).
- **Clipboard paste (`onPasteImage` / `hasClipboardImage` / `pasteFromClipboard`)** — not a
  same-triviality follow-up; research/features.md §2 identifies that the interface's synchronous
  `hasClipboardImage(): Boolean` contract doesn't map cleanly onto the browser's Promise-only
  Clipboard API. Needs its own scoping pass, not silently rolled into a future toolbar-picker
  project.
- **Visual drag-over hover affordance** — JVM has none today either (research/ux.md §1); adding
  one for wasm alone would create a cross-platform inconsistency this bug fix shouldn't
  introduce.
- **Toast-on-failure for `onAttachImage`/`onPasteImage` on any platform** — this project adds a
  toast only inside `WasmMediaAttachmentService.attachBytes` (Task 3.1.2a), i.e. only to the new
  wasm `onFileDrop` failure path, and does so without touching `App.kt`'s shared `ifLeft` branch
  (see Story 1.1.2's scope note and Story 3.1.2); the pre-existing silent failure on the other two
  entry points, on all platforms, is a separate cross-cutting cleanup (research/ux.md §4).
- **`FileSystem.writeFileBytes`/`readFileBytes` generic implementation** — intentionally bypassed
  (Pattern Decisions, "OPFS byte write path"); paranoid-mode byte I/O on wasm remains
  unimplemented, unchanged from today.
