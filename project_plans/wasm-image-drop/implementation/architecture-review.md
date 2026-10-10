# Architecture Review: wasm-image-drop
**Date**: 2026-09-22
**Verdict**: CONCERNS

## Method note

Ground-truthed against the real codebase, not just plan.md prose: `kibitzer` is on `PATH`, but
this repo has no `.claude/inspect.json`, so the component-deps/layering/content-rules/naming-rules
checks (which require named `architecture.components`) did not run. The generic batch checks
(file-size, function-size/nesting/param-count, duplicate-code, comment-quality) still ran and
surfaced real, confirmed findings, used below. Commands run:
`kibitzer run kmp/src/commonMain/kotlin/dev/stapler/stelekit/service --trigger batch`,
`kibitzer run kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui --trigger batch`,
`kibitzer run kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/{platform,ui/components} --trigger batch`.

## Constitution check

`docs/adr/ADR-000-architecture-constitution.md` does not exist in this repository (`docs/adr/`
itself does not exist — confirmed via `ls`/`find`). No constitution to check against; skipped.

---

## Blockers

None. No finding below is structural/unshippable-as-planned; all are fixable within the existing
task budget without replanning.

## Concerns

- [ ] **Story 2.2.2 / Task 3.1.1a — OPFS filename-dedup race across separate drop events (silent
  overwrite risk).** Story 2.2.2's third acceptance criterion proves the async
  `uniqueOpfsFileName` dedup is race-free only *within one drop batch* (App.kt's `files.forEach`
  awaits each `attachBytes` call before the next). Nothing serializes `attachBytes` calls
  *across* separate `drop` DOM events: each fires its own `dropScope.launch { ... }` on a shared,
  unserialized `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (Task 4.1.2a). Two
  near-simultaneous drops of a same-named file can both observe "does not exist" in
  `opfsFileExists` before either `opfsWriteFileBytes` write commits, so the second write silently
  clobbers the first — both calls return `Either.Right`, so nothing surfaces the loss. This
  directly undercuts requirements.md AC2's "no collision" guarantee. The repo already has the
  right pattern for this exact problem shape (`DatabaseWriteActor`, `kmp/CLAUDE.md`'s "Write
  enforcement" section, serializes concurrent writes to one coroutine) but the plan doesn't reuse
  or mirror it, and the Pattern Decisions table's dedup row discusses *why a new async dedup is
  needed* without addressing cross-batch concurrency. **Remediation**: wrap
  `WasmMediaAttachmentService.attachBytes`'s body in a module-level `kotlinx.coroutines.sync.Mutex
  .withLock`, or route all writes through a small wasm-local serializing actor. Small enough to
  fit inside Task 3.1.1a's existing scope.

- [ ] **Task 1.1.2a / Task 1.1.2b — new logic lands inside a confirmed God Function with no unit
  test coverage plan.** `kibitzer` confirms the enclosing `StelekitApp` composable
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt:347`) spans **1032 lines, nests 15
  levels deep, and takes 20 parameters** — well past kibitzer's long-function/deep-nesting/
  long-parameter-list thresholds (40 lines / 4 levels / 5 params). Both new tasks add branching
  logic (`when (file) { is DroppedFileBytes -> ... }`) and a new `notificationManager.show(...)`
  call directly inside this function, at `App.kt` ~1206-1224. Plan.md's Tech Debt Disposition
  table addresses two other pre-existing issues (wasmJsMain service gap, `writeFile` fire-and-
  forget) but is silent on this one, even though these two tasks touch it directly. Concretely,
  this isn't just a paperwork gap: Phase 5's test plan (`WasmMediaAttachmentServiceTest`,
  `ByteBufferInteropTest`) covers the OPFS/service layer but has **no test at all** for the new
  `when`-branch dispatch or the new toast-on-failure logic — both are reachable only via the
  manual browser check (Task 5.2.1a), because they're embedded in a function too large and
  UI-coupled to unit-test in isolation. **Remediation**: extract the `onFileDrop` lambda body
  (the `files.forEach { ... }` block, including the new branch and toast call) into a standalone
  top-level `suspend fun handleFileDrop(files: List<Any>, attachmentService: MediaAttachmentService,
  graphRoot: String, pageUuid: String, onInsert: (String) -> Unit, onError: (DomainError) -> Unit)`
  that Task 1.1.2a implements and a new `businessTest`/`jvmTest` exercises with a fake
  `MediaAttachmentService` — no Compose harness needed. This fits inside Task 1.1.2a's stated
  scope and nudges the God Function toward smaller, not larger, without attempting a full
  refactor of App.kt (correctly out of scope for this bug fix).

- [ ] **Task 4.1.2a — no error handling around the per-file read loop in `DropZoneInterop`'s
  callback.** `ensureListenerInstalled`'s coroutine body calls `readFileBytes(file)` (which
  awaits `File.arrayBuffer()`, a `Promise` that can reject — e.g. a file the OS reports but can
  no longer read) with no `try`/`catch`. Every *other* failure path this project adds is logged
  and toasted (Task 1.1.2b's `graphContentLogger.warn` + `notificationManager.show`), but a
  rejected `arrayBuffer()` promise here propagates as an unhandled exception in the coroutine
  with no log line and no user-visible signal — silently indistinguishable from AC4's intended
  graceful no-op for an out-of-scope drop, but for a different, unintended reason. **Remediation**:
  wrap the per-file loop body in `try`/`catch`, routing failures through the same
  `graphContentLogger.warn`/`notificationManager.show` path Task 1.1.2b establishes for the
  service-layer failure case.

## Nitpicks

- Tech Debt Disposition table doesn't mention the pre-existing 3x-duplicated "`AttachmentResult`
  → markdown" formatting block that `kibitzer`'s `duplicate-code` check confirms
  (`App.kt:599,1192,1244` — the `safeAlt`/`safePath`/markdown-construction lines repeated across
  `onAttachImage`, `onFileDrop`, and `onPasteImage`). Task 1.1.2a's edit sits directly beside the
  `onFileDrop` instance without touching it. Not a blocking issue — the same "don't do a
  gratuitous unrelated refactor" reasoning the plan already applies to the duplicated
  `IMAGE_EXTENSIONS` constant (Pattern Decisions table) applies here too — but worth one line in
  the disposition table for consistency/completeness rather than silence.
- The `List<Any>`-erased drop payload (`DroppedFileBytes` pattern-matched via `is` in `App.kt`)
  is the textbook case type-driven-design would prefer as a `sealed interface DroppedFile`
  instead of `Any` + runtime `is`-checks. The plan already evaluated and rejected this shape
  (rejected Alternative C) for sound minimal-diff reasons tied to not touching
  `EditorCapabilities`/`PageView.kt`, which research/architecture.md independently confirmed
  don't need to change — acceptable for this scope; revisit if a third platform-specific payload
  type is ever added to the same `List<Any>` contract.
- Task 2.2.2a's directory walk with `create = true` runs twice per attach — once inside
  `uniqueOpfsFileName` → `opfsFileExists`, once inside `opfsWriteFileBytes` — harmless (OPFS
  `getDirectoryHandle(create=true)` is idempotent) but an easy one-line dedup (thread the already-
  resolved directory handle through) if picked up during implementation.
- Task 4.1.2a's `activeDropHandler = onFilesDropped` assignment sits directly in the `composed {
  }` lambda body rather than inside an explicit `SideEffect { }`. Functionally safe here (the var
  isn't read during composition and isn't part of Compose's snapshot-state system), but stylistically
  worth wrapping in `SideEffect { }` to match Compose's documented "side effects belong in effect
  handlers" guidance, since `composed { }` bodies are otherwise treated as composition scope.

---

## Summary of what checked out clean

- Tech Debt Disposition's "Extend as-is" call for the wasmJsMain `MediaAttachmentService` gap
  matches research/architecture.md §3's independent recommendation exactly, and is a legitimate
  "Extend as-is" per the `code-architecture-best-practices` decision rule (the seam was
  purpose-built for this: three prior optional default-null methods already establish the
  pattern; adding a fourth doesn't compound a violation, it continues an intentional one).
- ADR-001 (hand-rolled interop over `kotlinx-browser`) and ADR-002 (Base64 bridge) are both
  consistent with research/build-vs-buy.md's recommendations (§1a/1c "not recommended"/"viable but
  marginal", §3 "recommended: reuse in-repo `OpfsInterop.kt` pattern").
  `attachBytes`'s default-`null` shape, doc-comment convention, and error contract
  (`Either<DomainError, AttachmentResult>?`) were verified against the real
  `MediaAttachmentService.kt` file and match the existing `attachFilePath`/`hasClipboardImage`/
  `pasteFromClipboard` precedent exactly.
- `sanitizeFileNameComponent`'s `internal` visibility (commonMain) is genuinely reachable from
  wasmJsMain (same Gradle module, different source set) — verified by reading
  `AttachmentFileNaming.kt`; the plan's cross-source-set reuse claim holds.
- `DomainError.AttachmentError.CopyFailed` and its `toUserMessage()` mapping were verified to
  exist exactly as the plan describes (`DomainError.kt:99,152`); `notificationManager` is
  confirmed in scope at the `onFileDrop` call site (a parameter of the enclosing function).
  JVM's `pageDropTarget` (verified by reading `jvmMain/.../PageDropTarget.kt`) is a plain
  non-composable function, confirming Task 4.1.2a's `composed { }` + `DisposableEffect` choice is
  the correct (not gratuitous) idiom for wasm's stateful-listener requirement, since a
  `DisposableEffect` can only be invoked from composition.
