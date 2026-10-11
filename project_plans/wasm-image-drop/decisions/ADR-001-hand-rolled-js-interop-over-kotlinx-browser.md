# ADR-001: Hand-rolled `external fun = js(...)` interop, not `kotlinx-browser`

**Status**: Accepted
**Date**: 2026-09-22

## Context

wasm-image-drop needs typed access to `DataTransfer`, `File`, `FileList`, and OPFS DOM APIs
from Kotlin/Wasm. Two options exist:

1. Add `org.jetbrains.kotlinx:kotlinx-browser:0.5.0` for typed `org.w3c.dom`/`org.w3c.files`
   bindings, including a ready-made `Int8Array.toByteArray()`.
2. Continue this repo's existing pattern (`OpfsInterop.kt`, `SqliteWorkerInterop.kt`): small,
   private `external fun ... = js("...")` declarations against `JsAny`, bridged with
   `kotlinx.coroutines.await()`.

`kotlinx-browser` has no OPFS bindings at all (confirmed by source inspection — no
`FileSystemDirectoryHandle`/`getDirectoryHandle` anywhere in the library), so OPFS interop must
be hand-rolled regardless of this decision. The only piece `kotlinx-browser` would genuinely
save is typed `DataTransfer`/`File`/`FileList` access for the drop listener (research/stack.md
§2, research/build-vs-buy.md §1c).

## Decision

Do not add `kotlinx-browser`. Hand-roll all new interop (drop-event capture, `File.arrayBuffer()`
bridge, OPFS byte write, byte marshaling) as `external fun ... = js("...")` declarations in
wasmJsMain, following `OpfsInterop.kt`'s existing style exactly.

## Consequences

- No new third-party dependency; no new experimental-API surface (`kotlinx-browser`'s own docs
  mark it API-subject-to-change).
- Every new wasmJsMain file uses one consistent interop idiom, rather than typed library calls
  for the drop half and raw `js()` strings for the OPFS half of the same feature.
- A handful of `external fun` declarations (~10–15 lines) are hand-written instead of imported;
  low cost given the surface is small and MDN-documented (`DataTransfer.files`, `File.name`,
  `File.arrayBuffer()`).
- Revisit if a future feature needs a much larger DOM-typing surface — at that point
  `kotlinx-browser`'s cost/benefit changes.
