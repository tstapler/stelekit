# Research: Stack — web-credential-persistence

Agent 1 (Stack), SDD Phase 2. Scope: browser APIs, Kotlin/Wasm interop patterns, and libraries
for a real `wasmJs` `CredentialStore`.

## 1. WebCrypto `SubtleCrypto` interop

**No wrapper library ships WebCrypto bindings.** `kotlinx-browser` (see §2) only covers
`org.khronos.webgl` (typed arrays, WebGL), `org.w3c.dom.*`, and `kotlinx.browser` (`window`,
`document`, `localStorage`, `sessionStorage`). `crypto.subtle` has no Kotlin/Wasm binding
anywhere — it must be hand-rolled with `js()`/`external`, exactly like this repo already does
for OPFS, Web Locks, and File System Access (`OpfsInterop.kt`, `WebLock.kt`,
`HostDirectoryInterop.kt` — none of those use a wrapper library either; this is the repo's
established idiom, not a gap to fill with a new dependency).

Kotlin/Wasm's `js()` function has hard restrictions (confirmed against
[kotlinlang.org/docs/wasm-js-interop.html](https://kotlinlang.org/docs/wasm-js-interop.html),
16 March 2026 revision):
- Must be a package-level (top-level) function — not inside a class/object/companion object.
  `WebLock.kt:10`'s own comment states this explicitly: `// js() calls must be top-level
  functions in Kotlin/Wasm — not inside a class or companion object.`
- Must be a string-literal argument, and the single expression of the function body.
- Return type must be explicit and one of the restricted interop types (`JsAny`/subtypes,
  `Promise`, primitives) — see the type-correspondence table in that doc.

The idiom this repo already uses for every async browser API (`crypto.subtle.*` included) is a
private top-level function returning `kotlin.js.Promise<JsAny>`, wrapped in a `suspend` function
that calls `.await()` from `kotlinx.coroutines`:

```kotlin
// Mirrors OpfsInterop.kt's opfsRootPromise/getOpfsRoot pattern exactly.
private fun subtleEncryptPromise(key: JsAny, iv: JsAny, data: JsAny): kotlin.js.Promise<JsAny> =
    js("crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, data)")
internal suspend fun subtleEncrypt(key: JsAny, iv: JsAny, data: JsAny): JsAny =
    subtleEncryptPromise(key, iv, data).await()
```

The same shape applies to `crypto.subtle.decrypt`, `.deriveKey` (PBKDF2 → AES-GCM, mirroring
`JvmCredentialStore`'s PBKDF2-HMAC-SHA256 pattern), `.deriveBits`, `.generateKey`, `.importKey`,
and `.exportKey` — all are async (`Promise`-returning) per the WebCrypto spec, so all need this
same `*Promise()` + `suspend fun ... .await()` pairing. `crypto.getRandomValues()` (used for
IVs/salts) is the one exception — it's synchronous and can be a plain `external`/`js()` function
with no `Promise`/`await` involved, same as `Math.random()`-style calls elsewhere in the repo.

Byte marshalling for ciphertext/key material needs the same manual `ArrayBuffer` ↔ `ByteArray`
bridge `OpfsInterop.kt` already implements for OPFS binary I/O — `ByteArray.toJsArrayBuffer()`
(byte-by-byte JS array push, `OpfsInterop.kt:236`) and `JsAny.toKotlinByteArray()`
(`OpfsInterop.kt:153`, reads via `Uint8Array` indexing). These are already `internal`, not
`private`, specifically so other wasmJs files can reuse them (see `OpfsInterop.kt:83`'s comment
about `HostDirectorySync.kt` reuse) — a `CredentialStore` actual encrypting/decrypting
`ByteArray` ciphertext can call these directly rather than re-deriving the marshalling.

**Secure-context requirement**: `crypto.subtle` is only defined in a
[secure context](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts) (HTTPS,
or `localhost`) — `window.crypto.subtle` is `undefined` on plain HTTP. Not a practical constraint
here: this repo's GitHub Pages deploy (`pages.yml`) and any realistic hosting target are HTTPS,
and `localhost` dev serving is exempt by spec. Worth a defensive `isAvailable()` check
(`typeof crypto?.subtle !== 'undefined'`) rather than assuming, mirroring how
`showDirectoryPickerSupported()` (`OpfsInterop.kt:6`) already feature-detects before use instead
of assuming API presence.

### Does this codebase's Kotlin/Wasm setup support `suspend`/`.await()`? — yes, extensively

`kotlinx-coroutines-core` (pulled in as a `commonMain` dependency, `kmp/build.gradle.kts:94`)
resolves to the `-wasm-js` platform artifact for the `wasmJs` target automatically via Gradle's
KMP dependency resolution — no separate `kotlinx-coroutines-core-wasm-js` line is needed in
`build.gradle.kts`. `kotlinx.coroutines.await` (the `Promise<T>.await(): T` extension) is used
throughout every wasmJs interop file already read for this research: `OpfsInterop.kt`,
`WebLock.kt`, `PlatformFileSystem.kt`. This is a settled, load-bearing pattern in the codebase,
not something to newly validate.

### Precedent: keeping a synchronous public API while doing async work underneath

This is the crux of the sync-vs-async mismatch flagged in the requirements
(`CredentialAccess.store/retrieve/delete` are non-`suspend`). **The codebase already has two
independent instances of the exact pattern needed, both in files directly relevant to this
project:**

1. **`VaultCredentialStore`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/VaultCredentialStore.kt`)
   — the sibling `CredentialAccess` implementation this project must stay compatible with.
   `retrieve()` (`:65`) is `cache?.get(key)` — a pure synchronous in-memory map read. `store()`
   (`:67-72`) synchronously updates `cache`, then calls `saveCredentials()` which performs
   synchronous file I/O (`fileSystem.writeFileBytes`, itself synchronous on JVM but — see next —
   backed by an async-flushed cache on wasmJs). The public `CredentialAccess` contract stays
   100% synchronous; durability underneath is whatever the injected `FileSystem` provides.

2. **`PlatformFileSystem`** (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`)
   — the concrete wasmJs mechanism `VaultCredentialStore` rides on when used on web, and the
   direct architectural template for the new `CredentialStore` actual:
   - Owns its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally (`:31`) —
     never a caller-supplied `rememberCoroutineScope()`, consistent with this repo's
     scope-ownership rule (`CLAUDE.md`'s "Coroutine scope ownership" section).
   - `readFile(path)` (`:458`) is `cache[path]` — synchronous, in-memory, no I/O.
   - `writeFile(path, content)` (`:503-526`) synchronously writes `cache[path] = content`
     *first*, then fires an async, non-blocking persistence job:
     `opfsWriteInFlight[path] = scope.async { opfsWriteFile(path, content) }`, tracked in a
     `Deferred` map (`opfsWriteInFlight`) so other code can `await` a specific path's write
     landing before depending on it (`opfsWriteDeferredFor`, used by `HostDirectorySync`).
   - A `flushPendingWrites()` suspend function (declared on the `FileSystem` interface,
     `FileSystem.kt:212`, default no-op) lets callers await all in-flight async writes at
     teardown — the mechanism this repo already uses to reconcile "instant synchronous API" with
     "eventually-durable async backend."
   - The cache is bootstrapped from durable storage via an async `preload()` step that must run
     (and complete) during app startup, before any `readFile()` call can rely on it returning
     real data — i.e., the synchronous read-cache pattern requires an async warm-up phase
     up-front, same as `VaultCredentialStore.onVaultUnlocked()` calling `loadCredentials()`
     before `cache` is first read.

**Conclusion for planning**: a wasmJs `CredentialStore` actual should follow this exact shape —
an internally-owned `CoroutineScope`, an in-memory `MutableMap<String, String>` cache read
synchronously by `retrieve()`, `store()`/`delete()` updating the cache synchronously and then
launching a fire-and-forget async encrypt-and-persist job (WebCrypto `encrypt` + durable-storage
write, both async), and a suspend `preload()`/`init` step run once at app startup (mirroring
`GraphManager`/`PlatformFileSystem`'s existing startup sequencing) so `retrieve()` has real data
available synchronously thereafter. This satisfies the constraint in
`requirements.md`'s "Constraints" section calling for "an in-memory cache backed by
async-flushed durable storage, mirroring how `VaultCredentialStore` already keeps an in-memory
`cache`" — that mirroring is available today, one level down, in `PlatformFileSystem`, not just
in `VaultCredentialStore` itself.

One gap to flag for planning: unlike `PlatformFileSystem.writeFile`, `CredentialAccess.store()`
has no async counterpart callers can await (`storeBlocking()` is documented "migration-only, not
for interactive credential entry"). A `store()` that returns before the WebCrypto
encrypt+persist actually lands means a page closed within that window loses the write — same
class of risk `PlatformFileSystem` accepts today (mitigated only by best-effort
`pagehide`/`visibilitychange` flush hooks, see `OpfsInterop.kt`'s `jsPageHidePromise`/
`jsVisibilityHiddenPromise`). Not this research task's call to resolve, but planning should
decide whether `CredentialStore` needs the same best-effort flush-on-unload wiring.

## 2. `kotlinx-browser` — what it exposes

Confirmed via the library's own README ([github.com/Kotlin/kotlinx-browser](https://github.com/Kotlin/kotlinx-browser)):
only `org.khronos.webgl` (typed arrays, WebGL types), `org.w3c.dom.*` (DOM API types), and
`kotlinx.browser` (DOM globals — `window`, `document`, and by extension `localStorage`/
`sessionStorage`, which are properties of `window`). **No `SubtleCrypto`/WebCrypto bindings.**
**No usable `IndexedDB` bindings** — per a JetBrains `kotlin-wrappers` issue
([JetBrains/kotlin-wrappers#2079](https://github.com/JetBrains/kotlin-wrappers/issues/2079)),
sealed-class type declarations for IndexedDB exist but there's no included database-open/
initializer helper (no `Window.indexedDB: IDBFactory?` extension) — using it directly would mean
hand-rolling the same amount of `js()`/`external` glue as not using the library at all, plus a
new dependency to track. A third-party JuulLabs `indexeddb` library (coroutines-based, Kotlin/JS
only per its own docs) exists but is unevaluated for Kotlin/Wasm-JS target compatibility and
would be a new dependency this repo doesn't otherwise need — out of proportion for "a handful of
credential key/value pairs."

**Version note — no explicit `kotlinx-browser` Gradle dependency exists in this repo today.**
`kmp/build.gradle.kts` has no `implementation("org.jetbrains.kotlinx:kotlinx-browser:...")`
line; `grep` across every `.kts`/`.toml` file in the repo confirms it. `PlatformSettings.kt`'s
`import kotlinx.browser.localStorage` and `browser/Main.kt`'s `kotlinx.browser` imports resolve
anyway because the Kotlin/Wasm Gradle plugin auto-adds `kotlinx-browser` as a default dependency
for any `wasmJs { browser() }` target (confirmed by the plugin transitively resolving the
import with no explicit line — this repo's `kmp/build.gradle.kts:36` declares `wasmJs { ... }`
with a browser executable). Current published version per the library's own README: **0.5.0**
(requires Kotlin ≥ `2.2.20-Beta2`; this repo is on Kotlin `2.4.10` per
`settings.gradle.kts:9`, well above that floor, so whatever version the plugin auto-resolves is
compatible). No action needed to add anything for `localStorage` use — only WebCrypto and (if
chosen) IndexedDB need hand-rolled `js()`/`external` interop regardless of `kotlinx-browser`.

## 3. IndexedDB vs `localStorage`

| | `localStorage` | IndexedDB |
|---|---|---|
| API shape | **Synchronous** — `getItem`/`setItem`/`removeItem` return immediately | **Asynchronous** — event-based (`onsuccess`/`onerror`) or Promise-wrapped by hand |
| Fits `CredentialAccess`'s non-suspend contract | Directly — no cache/flush machinery needed | Not directly — needs the same in-memory-cache-plus-async-flush pattern `PlatformFileSystem` already uses for OPFS |
| Kotlin/Wasm interop cost | **Zero new code** — `kotlinx.browser.localStorage` already works, already used by `PlatformSettings.kt` in this exact source set | New `js()`/`external` surface roughly the size of `OpfsInterop.kt`'s (indexed-DB has a more verbose open/transaction/request API than OPFS) |
| Per-origin size cap | ~5–10MB (browser-dependent) — [MDN Storage quotas and eviction criteria](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria) confirms `localStorage` is bound by this, historically the tightest of the browser storage mechanisms | Governed by the same overall origin storage quota, but no separate small fixed cap — designed for bulk data |
| Credential volume here | A handful of git-connection secrets + LLM API keys — comfortably under any `localStorage` cap by orders of magnitude (the "Feasibility Risks" section's "not a real risk" framing checks out) | Overkill for this volume |
| Browser support (2026, this project's WasmGC floor) | Universal since the mid-2000s, far below this project's floor | Universal in the same Chrome 119+/Firefox 120+/Safari 18.2+ set this project already requires for WasmGC itself (`docs/archive/tasks/browser-wasm-demo.md:248`, `stelekit-site.md:514`) — not a differentiator here |
| Existing precedent in this codebase | `PlatformSettings.kt` (git config keys, non-secret) already uses it for exactly this kind of small, frequently-read key/value data, respecting `EphemeralSettingsMode` | None — no file in `kmp/src/wasmJsMain` touches IndexedDB today |

**Recommendation input for planning**: `localStorage` is the lower-friction choice given the
credential volume (small), the sync-API constraint (satisfied natively, no cache/flush layer
required purely for the storage call itself — though one may still be desirable for the
WebCrypto async encrypt/decrypt step regardless of storage backend), and the total absence of
IndexedDB precedent or usable bindings in this codebase. IndexedDB's only structural advantage
(handling much larger payloads) doesn't apply to credential storage. This tracks the "Rabbit
Holes" section's framing in `requirements.md` — the real complexity is the *encryption* step's
async nature (WebCrypto), not the storage call, regardless of which backend is picked, so
`localStorage` doesn't actually dodge the cache/flush pattern — it just avoids *doubling up* two
independent async layers (storage-API async atop crypto-API async) with only one payoff.

**Browser support for `crypto.subtle` itself**: universally available in every evergreen browser
since well before this project's WasmGC-driven floor (Chrome 37+, Firefox 34+, Safari 11+, all
from the 2014–2017 era) — not a binding constraint given the project already requires Chrome
119+/Firefox 120+/Safari 18.2+ for WasmGC. The one caveat found during this research: Kotlin/Wasm's
*automatic* JS-exception-to-`JsException` translation for uncaught `crypto.subtle` promise
rejections requires `WebAssembly.JSTag` support — Chrome 115+, Firefox 129+, **Safari 18.4+**
(per the same kotlinlang.org interop doc) — slightly *above* this project's stated Safari 18.2+
floor. Worst case on Safari 18.2/18.3 without JSTag: a rejected WebCrypto promise surfaces as a
less-informative error rather than a clean Kotlin `JsException`/`Throwable` — not a functional
blocker (the `.await()` call still fails and can still be caught generically), but planning
should note this as a minor observability gap on the oldest supported Safari versions rather than
assume full `JsException` fidelity everywhere.

## 4. Current recommended versions (Kotlin 2.x KMP, this repo's baseline)

| Package | Version in use / recommended | Source |
|---|---|---|
| Kotlin (multiplatform plugin) | `2.4.10` (already pinned, `settings.gradle.kts:9`) | repo file |
| `kotlinx-coroutines-core` | `1.10.2` (already pinned in both `commonMain`/wherever declared, `kmp/build.gradle.kts:94,394`) | repo file — resolves the `-wasm-js` platform artifact automatically for the `wasmJs` target, no separate line needed |
| `kotlinx-browser` | `0.5.0` current per its README; auto-resolved transitively for `wasmJs { browser() }` targets today with no explicit dependency line in this repo — adding an explicit pin is optional, not required, for this project's scope (WebCrypto/IndexedDB aren't in this library regardless of version) | [github.com/Kotlin/kotlinx-browser](https://github.com/Kotlin/kotlinx-browser) |
| WebCrypto (`crypto.subtle`) bindings | No package — hand-roll `js()`/`external`, as this repo already does for every other browser API surface | — |

No version bump is needed anywhere for this project — the existing `kotlinx-coroutines-core`
and (transitively resolved) `kotlinx-browser` versions already support everything this feature
needs (`.await()`, `localStorage`). The only new surface area is hand-written WebCrypto
`js()`/`external` glue, matching the repo's established pattern for OPFS, Web Locks, and File
System Access — no new Gradle dependency required.

## Summary for planning

- **Storage backend**: `localStorage` is the pragmatic choice — synchronous (fits
  `CredentialAccess` natively), already used in this exact source set (`PlatformSettings.kt`),
  and IndexedDB's async-only API/lack of usable Kotlin bindings would only add a second
  independent async layer on top of the one WebCrypto already requires, for no payload-size
  benefit at credential-store scale.
- **Encryption**: WebCrypto `SubtleCrypto` (`crypto.subtle.deriveKey`/`encrypt`/`decrypt`/
  `generateKey`/`importKey`) has zero Kotlin binding coverage anywhere (not in `kotlinx-browser`,
  no wrapper library) and must be hand-rolled with `js()`/`external`, all async
  (`Promise`-returning) — this repo already has the exact idiom for this
  (`OpfsInterop.kt`'s `*Promise()` + `suspend fun ... .await()` pairs, plus its
  `ByteArray`↔`ArrayBuffer` marshalling helpers, directly reusable).
- **Sync/async bridge**: no new pattern needs inventing — `PlatformFileSystem.kt` (wasmJsMain)
  already implements "synchronous cache-backed public API, async persistence underneath, via an
  internally-owned `CoroutineScope`" for OPFS, and `VaultCredentialStore` implements the same
  shape one layer up at the `CredentialAccess` level itself. The new `CredentialStore` actual
  should combine both: an in-memory cache for synchronous `retrieve()`, and `store()`/`delete()`
  synchronously updating the cache while launching an async WebCrypto-encrypt-then-`localStorage`
  write job — with a `preload()`-style startup step to populate the cache from decrypted
  `localStorage` before first use, matching how `PlatformFileSystem` and
  `VaultCredentialStore.onVaultUnlocked()` both bootstrap their caches today.
