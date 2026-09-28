# ADR-002: Base64-string bridge for `ArrayBuffer` ↔ `ByteArray`, not a per-element loop

**Status**: Accepted
**Date**: 2026-09-22

## Context

Kotlin/Wasm's JS interop boundary only allows `Number`, `Boolean`, `String`, `Unit`, function
types, and `JsAny`/subtypes to cross `external fun`/`js(...)` signatures (research/stack.md §2).
`ByteArray` is a Wasm-native value type, not `JsAny` — it cannot cross the boundary directly in
either direction. Two ways to bridge it were considered:

1. **Per-element loop**: `external fun int8ArraySet(arr: JsAny, i: Int, v: Byte)` /
   `int8ArrayGet(arr: JsAny, i: Int): Byte`, called once per byte in a Kotlin `for` loop. This is
   the pattern `SqliteWorkerInterop.kt` already uses (`jsArrayPushLong` etc.), but only for
   small row/column counts (tens of values). research/pitfalls.md §3 flags this explicitly as a
   "severe performance cliff" for multi-MB image bytes — one Wasm↔JS boundary call per byte.
2. **Base64-string bridge**: encode/decode the bytes as a `String` (an allowed boundary type) on
   one side, and do the actual byte-level work natively in JS (`atob`/`btoa` + a JS-side loop) or
   in Kotlin stdlib (`kotlin.io.encoding.Base64`, stable since Kotlin 1.8.20 — present at this
   repo's Kotlin 2.3.21 pin, not JS-specific).

`kotlinx-browser`'s own `Int8Array.toByteArray()` (ADR-001) was the other candidate the research
surfaced, but ADR-001 already rejects taking that dependency.

## Decision

Bridge `ArrayBuffer`/`Int8Array` ↔ `ByteArray` via Base64:

- **Read** (`File.arrayBuffer()` → `ByteArray`): one `js("...")` call converts the resulting
  `ArrayBuffer` to a base64 `String` JS-side (chunked `btoa` over the buffer to avoid
  `String.fromCharCode` argument-count limits on large buffers); `kotlin.io.encoding.Base64.decode(str)`
  turns that into a `ByteArray` in Kotlin.
- **Write** (`ByteArray` → OPFS `write()`): `kotlin.io.encoding.Base64.encode(bytes)` produces a
  `String`; one `js("...")` call decodes it JS-side (`atob` + a `Uint8Array` fill) and passes the
  resulting `Uint8Array` to `FileSystemWritableFileStream.write()`.

Both directions cross the Wasm↔JS boundary exactly once per file (a `String`), not once per byte.

## Consequences

- No per-byte boundary calls — resolves the performance risk research/pitfalls.md §3 flagged.
- ~33% size overhead versus raw bytes during the string crossing — negligible for typical
  dropped-image sizes (a few MB); no chunked/streaming write is in scope per requirements.md.
- Uses only Kotlin stdlib (`kotlin.io.encoding.Base64`) and native JS string/array functions —
  no new dependency, consistent with ADR-001.
- If a future feature needs much larger binary transfers, revisit — a chunked transfer or a
  different bridge (e.g. `kotlinx-browser`'s typed-array adapters) may be worth the dependency
  cost at that size.

## Update (2026-09-28 merge with origin/main)

`OpfsInterop.kt` on `main` independently grew a *second* `ByteArray ↔ JsAny` bridge
(`ByteArray.toJsArrayBuffer()`/`JsAny.toKotlinByteArray()`) for paranoid-mode encrypted writes, a
per-element JS-array-push loop whose own doc comment says it is "acceptable for markdown-page-sized
... content, not large blobs." This feature keeps its own base64 bridge (`ByteBufferInterop.kt`)
for `attachBytes`'s write path rather than switching to that one — the whole point of this ADR was
avoiding a per-element cliff on multi-MB image bytes, and paranoid-mode's bridge is exactly that
cliff, just accepted at a size (markdown pages) where it doesn't matter. The two bridges are
intentionally not unified; each is sized to its own caller.
