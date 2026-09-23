# ADR-001: Hand-rolled IndexedDB interop for `FileSystemDirectoryHandle` persistence

**Status**: Accepted
**Date**: 2026-07-17
**Project**: web-local-folder-livesync

## Context

`web-local-folder-livesync` needs to persist a `FileSystemDirectoryHandle` across page reloads and
new tabs/sessions so a user gets "at most one click to resume access" instead of a full
`showDirectoryPicker()` re-pick every time. `FileSystemHandle` objects are structured-clone
serializable, so IndexedDB is the only browser storage mechanism that can hold them directly
(`localStorage`, used today by `platform/PlatformSettings.kt`, is string-only and cannot).

`kmp/src` has **zero existing IndexedDB usage** (`grep -rl indexedDB kmp/src` — no hits). Every
other browser-API interop point in this codebase — OPFS (`platform/OpfsInterop.kt`), Web Locks
(`git/GitWriteLock.kt`), the File System Access picker itself — is hand-rolled `js("...")` /
`external` glue with no interop wrapper library. `research/build-vs-buy.md` §1 surfaces one
credible alternative: `com.juul.indexeddb:core` (v0.12.0+), a Kotlin Multiplatform coroutines
wrapper over IndexedDB with real Kotlin/Wasm (`WasmGC`/`JsAny`) support and active maintenance.
Adopting it would be the **first interop-wrapper-library dependency** this platform layer has ever
taken — a deliberate departure from an established, consistent convention, which is exactly the
kind of choice this project's planning phase must decide explicitly rather than let default.

## Decision

**Hand-roll the IndexedDB interop**, following the exact `OpfsInterop.kt` idiom: small
`private fun ...(): kotlin.js.Promise<JsAny> = js("...")` wrappers paired with `internal suspend
fun` callers that `.await()` and wrap JS promise rejections in `try/catch (e: Throwable)`. This
lives in a new file, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt`,
alongside the `FileSystemObserver` and permission-query interop this project also needs.

**Do not adopt `com.juul.indexeddb:core`.**

## Rationale

- **Surface is small and well-bounded.** The entire IndexedDB need for this feature is: open one
  database with one object store, `put()` a handle keyed by `graphId`, `get()` it back, done — three
  `js()` functions (`idbOpenPromise`, `idbPutHandlePromise`, `idbGetHandlePromise`), matching the
  exact shape already sketched in `research/stack.md` §2. This is smaller than the OPFS surface
  `OpfsInterop.kt` already hand-rolls (181 lines covering directory traversal, file read/write,
  object-URL creation, visibility events) — the marginal interop-authoring cost of doing IndexedDB
  by hand is low relative to the surface already proven to be hand-rollable in this codebase.
- **Consistency has compounding value.** Every future contributor reading `wasmJsMain/platform/`
  currently finds one idiom for "talk to a browser API": small `js()` wrapper + suspend `.await()`
  caller. Introducing a wrapper library for exactly one browser API (IndexedDB) while every sibling
  API (OPFS, Web Locks, FS Access, `visibilitychange`) stays hand-rolled creates an inconsistent
  mental model for no correctness benefit — the wrapped and unwrapped code would sit side by side.
- **No upgrade-tracking burden.** A new Kotlin/Wasm-targeting dependency from a third party
  (`JuulLabs/indexeddb`) is a new thing to version-bump, watch for breaking changes in, and trust
  for WasmGC compatibility across future Kotlin releases — for a 3-function surface, that ongoing
  cost is not repaid by the type-safety gained.
- **The type-safety benefit does not fully apply here anyway.** The one payload this project stores
  (`FileSystemDirectoryHandle`) is itself an opaque, `JsAny`-typed structured-clone object on both
  sides — `JuulLabs/indexeddb`'s typed key/value API still hands this feature back a `JsAny` it has
  to trust matches the shape it wrote, same as the hand-rolled path. The library earns its keep for
  *complex* IndexedDB schemas (indexes, cursors, multi-store transactions); this feature needs none
  of that.

## Consequences

- New file `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` owns
  `idbOpenPromise`/`idbPutHandlePromise`/`idbGetHandlePromise` plus the envelope encode/decode
  helpers, tested indirectly via `PlatformFileSystemHandlePersistenceTest`
  (`kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/`).
- If a *second* IndexedDB consumer emerges later with a materially more complex schema need
  (multiple object stores, indexed queries), this ADR should be revisited rather than assumed to
  apply forever — the "small, well-bounded surface" rationale is specific to this feature's actual
  requirements, not a blanket rejection of interop libraries.
- No new Gradle/npm dependency line is added for this project.

## Alternatives Considered

| Option | Rejected because |
|---|---|
| `com.juul.indexeddb:core` (0.12.0+) | First interop-wrapper-library precedent in a codebase with a deliberate zero-wrapper convention, for a surface too small to repay the ongoing dependency-tracking cost |
| `com.eygraber.indexeddb:core-wasm-js` | Single 0.0.1-era release, much thinner adoption trail than JuulLabs' fork — lower confidence than the already-rejected option |
| JS `idb-keyval`/`idb` via hand-written `@JsModule` bindings | Still requires the same amount of hand-written Kotlin/Wasm boundary code as calling `indexedDB` directly (no `dynamic` typing in Kotlin/Wasm) — adds an npm dependency for no net interop-code reduction |
