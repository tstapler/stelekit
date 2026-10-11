# Adversarial Review: wasm-image-drop

**Date**: 2026-09-22
**Verdict**: CONCERNS

## Blockers

None. The plan correctly implements the two hardest, highest-risk items research surfaced:
- `opfsWriteFileBytes` (Task 2.2.1a) awaits `createWritable()`/`write()`/`close()` before
  returning and lets failures propagate (no `println`-and-swallow like the existing
  `opfsWriteFile`), and `WasmMediaAttachmentService.attachBytes` (Task 3.1.1a) only returns
  `Either.Right` after that write has landed — this avoids the fire-and-forget bug
  pitfalls.md §1 flagged as the top risk, and Story 5.1.1's OPFS round-trip test proves it.
- `dragenter`/`dragover`/`drop` all call `preventDefault()` (Task 4.1.1a), addressing the
  "browser navigates away with the file" failure mode from pitfalls.md §2 / ux.md §2.

## Concerns

- [ ] **Graph-switch race (pitfalls.md §5) is undocumented, not just unguarded** — the plan
  implements no re-check of `graphRoot`/`pageUuid` staleness before inserting markdown after an
  async OPFS write, which pitfalls.md explicitly said to "guard against explicitly." The plan's
  own "Unresolved Questions: None" is therefore inaccurate — this was a research-flagged risk
  needing a plan-phase call. The omission may be defensible (features.md §2 point 8 notes
  wasm's `browser/Main.kt` currently hardcodes a single graph ID, `"default"`, so the race is
  presently unreachable), but the plan never states that reasoning anywhere. Recommendation:
  add one line to Pattern Decisions or Unresolved Questions stating the guard is deferred
  because wasm has no multi-graph switching today, and flag it as required if/when wasm gains
  that capability — cheap to add, closes a real traceability gap before implementation starts.

- [ ] **ADR-002's Base64 bridge doesn't evaluate the alternative both stack.md and
  build-vs-buy.md pointed toward, and its overhead claim is asserted, not measured.** ADR-002
  frames the choice as "per-element loop vs. Base64," dismissing `kotlinx-browser`'s
  `Int8Array.toByteArray()` only because ADR-001 already declined the dependency — but it never
  evaluates the *hand-rolled* bulk-transfer option pitfalls.md §3 explicitly suggested ("a
  single `js("new Uint8Array(buffer)")` handle... convert with the Kotlin/Wasm-supported
  `toByteArray()`/`toUint8Array()` bridge if the stdlib version in use provides one — confirm
  which conversion helper is actually available... during planning rather than assuming"). No
  task confirms whether such a stdlib bridge exists on this Kotlin/Wasm pin. Separately, ADR-002
  claims "~33% size overhead... negligible for typical dropped-image sizes" but this likely
  undercounts the real cost: a base64 string crossing the Wasm↔JS boundary is stored as UTF-16
  on the JS side, so the ASCII base64 payload (already ~1.33x the raw bytes) takes roughly 2x
  that in memory — closer to ~2.67x the original byte count, not 33% — and no task benchmarks
  actual encode/decode time for a realistic multi-MB image. Base64 will work correctly either
  way; this is a performance-tradeoff justification gap, not a correctness bug. Recommendation:
  either confirm no stdlib bulk-transfer helper exists (one search, cheap) and record that in
  ADR-002, or add a quick timing note to Task 5.1.2a's round-trip test extrapolating to a
  realistic image size (e.g. 5 MB) so the "negligible" claim has a number behind it.

- [ ] **The handler-swap/listener-lifecycle logic in the `PageDropTarget.kt` actual has no
  automated coverage**, even though it's pure Kotlin/Compose state (not DOM-timing-dependent
  like the `preventDefault()` behavior that genuinely can't be headless-tested). Story 4.1.2's
  ACs #1 ("delivers `DroppedFileBytes`") and #2 ("listener installed once, not per navigation")
  are verified only by code inspection ("verified by the active-handler-swap design... not a
  duplicate `addEventListener` call") and the manual browser check in Task 5.2.1a — there's no
  `wasmJsTest`/`commonTest` exercising `ensureListenerInstalled`/`activeDropHandler` swap
  behavior directly. Recommendation: extract that state machine (install-once flag + active-handler
  swap) into a small class that can be unit-tested independent of the real `js()` glue, or at
  minimum add it to Task 5.2.1a's manual-check checklist explicitly (navigate A→B, confirm one
  callback fire, not two).

- [ ] **`dragenter`/`dragover` unconditionally `preventDefault()` without checking
  `event.dataTransfer.types.includes('Files')`** — features.md §2 point 7 called this out
  explicitly, mirroring JVM's `shouldStartDragAndDrop` gate that never activates the drop target
  for non-file drags (text selections, links). Functionally harmless as implemented (a non-file
  drop yields an empty `dataTransfer.files`, so the filter silently produces nothing), but it
  changes the browser's native cursor affordance for every drag over the page — a link/text drag
  now always shows "can drop here" instead of the browser's correct default reject cursor. Low
  priority; either add the one-line type check or explicitly note it's accepted as out of scope.

## Minors

- Task 1.1.2b adds a new user-visible toast on wasm's `onFileDrop` failure path that isn't in
  requirements.md's draft acceptance criteria (1–7). It's well-justified via research/ux.md §4
  and scoped narrowly (only this one new entry point, not the pre-existing silent failures on
  `onAttachImage`/`onPasteImage`), so this is a scope note rather than a real concern — flagging
  so it's a visible, intentional addition rather than something that shows up unexplained in the
  diff.
- `PageDropTarget.kt`'s `composed { activeDropHandler = onFilesDropped; ... }` (Task 4.1.2a)
  mutates module-level state directly in the composable body instead of inside a `SideEffect{}`
  — a minor Compose anti-pattern (composition can in principle re-execute/discard a body without
  committing). Low practical risk since the assignment is simple and idempotent, but a
  `SideEffect { activeDropHandler = onFilesDropped }` would be the idiomatic-safe version.
- Stories 5.1.1 and 5.1.2 both hand-wave a "small test-only `readOpfsFileBytes`-style helper"
  into existence in their acceptance-criteria prose rather than giving it its own task — minor
  task-breakdown gap, low risk given the helper is small and test-only.
- `DropZoneInterop`'s raw `drop` handler (Task 4.1.1a) doesn't wrap `Array.from(e.dataTransfer.files)`
  in any error handling; a malformed/null `dataTransfer` (very unlikely per the DOM spec for a
  genuine `drop` event) would throw uncaught inside the listener. Extremely low probability, noted
  for completeness against the repo's general convention of guarding all JS-boundary calls.
