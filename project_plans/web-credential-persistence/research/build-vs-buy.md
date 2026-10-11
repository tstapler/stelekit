# Build vs. Buy: wasmJs `CredentialStore`

Scope: which parts of the real `wasmJs` `CredentialStore` actual (encrypted-at-rest browser
credential storage) should be built in-house vs. sourced from an existing library, for a
Kotlin/Wasm (Compose Multiplatform for Web) target.

## 1. Existing OSS library for browser-side encrypted credential storage from Kotlin/Wasm

No library wraps the *whole* problem (WebCrypto + durable storage + a `CredentialAccess`-shaped
sync API). The candidates below only cover the crypto-primitive slice, and even then most fail on
wasmJs-target support specifically — assumed support did not hold up under verification.

| Library | wasmJs target? | Wraps native WebCrypto or reimplements cipher? | Maintenance | License | Verdict |
|---|---|---|---|---|---|
| **[whyoleg/cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)** | **Yes** — dedicated `cryptography-provider-webcrypto-wasm-js` artifact on Maven Central | Wraps native platform crypto per target (OpenSSL/CryptoKit/**WebCrypto**/JCA); wasmJs backend is WebCrypto, not reimplemented | Active — pushed 2026-09-02 (this month), 664 stars | Apache-2.0 | **Viable** |
| [ionspin/kotlin-multiplatform-crypto](https://github.com/ionspin/kotlin-multiplatform-crypto) | No official support — only an unofficial community fork adds wasmJs to the related `kotlin-multiplatform-libsodium` | Reimplemented ciphers | **Abandoned** — last push 2021-11-30 (~5 years) | Apache-2.0 | Not recommended |
| [korlibs/korlibs-crypto](https://korlibs.github.io/korlibs-crypto/) (formerly `krypto`) | Ambiguous — target list is `android/apple/common/js/jvm/linux/mingw/wasm`; "wasm" is not confirmed to mean the browser-facing `wasmJs` target rather than `wasmWasi` | **Reimplements AES** — its own docs credit the cipher as "Based on CryptoJS v3.1.2," a ported JS implementation, not a WebCrypto wrapper | Active — pushed 2026-09-02 | Apache-2.0-ish (bundled CryptoJS license terms) | **Not recommended** — violates the "never a reimplemented cipher" bar regardless of target-support ambiguity |
| [icure/kryptom](https://github.com/icure/kryptom) | **No** — own docs and klibs.io list only Android/JVM/Native/JS-IR; no "wasm" mention anywhere in the README | Wraps native primitives (JCA/CryptoKit/WebCrypto-on-JS) where supported | Active — pushed 2026-09-03 | MIT | Not recommended (no wasmJs target) |
| [andreypfau/kotlinx-crypto](https://klibs.io/project/andreypfau/kotlinx-crypto) | Klibs.io lists "Wasm" as a target for v0.0.5 | **Reimplements ciphers in pure Kotlin** (AES is Kotlin code, not a WebCrypto wrapper) | Early/experimental (v0.0.5) | Apache-2.0 | Not recommended — same reimplemented-cipher problem, plus pre-1.0 |
| [Kotlin/kotlinx-browser](https://github.com/Kotlin/kotlinx-browser) (JetBrains' own DOM-binding library) | Yes, by design (WasmJs + JS) | N/A — general DOM/browser bindings (`window`, `document`, `navigator`, etc.), **not a WebCrypto/SubtleCrypto wrapper** at all | JetBrains-official but **explicitly WIP**: its own README states "not published into public repository and not yet intended to use by end user," API "subject to change," requires a Kotlin **Beta** compiler (`2.2.20-Beta2`) | Apache-2.0 | Not applicable to this problem, and not stable enough to adopt regardless |

Note on `kotlinx.browser` already in this codebase: `Main.kt` and `PlatformSettings.kt` already
import `kotlinx.browser.{document,window,localStorage}` — but that's the older, stable
`kotlinx.browser` package bundled directly with the Kotlin/Wasm compiler's stdlib (no separate
Gradle dependency line exists for it), not the standalone WIP `Kotlin/kotlinx-browser` GitHub
project found above. Neither one has ever included SubtleCrypto bindings.

**`cryptography-kotlin` is the one real "buy" candidate** if the project decides not to hand-roll
crypto interop. It genuinely does what's being asked (native WebCrypto AES-GCM, real wasmJs
artifact, current AES-GCM/PBKDF2 support matching `JvmCredentialStore`'s existing algorithm
choices), is actively maintained, and is permissively licensed. It does **not**, however, remove
the project's core interop problem: its WebCrypto-backed operations are necessarily `suspend`
(WebCrypto's `subtle.encrypt`/`decrypt`/`deriveKey` are all Promise-based), so pulling it in still
requires solving the sync-`CredentialAccess`-interface-vs-async-crypto mismatch the requirements
doc already flags as a rabbit hole — it does not make that problem go away, only changes who wrote
the `external`/`js()` (or here, Kotlin `suspend`) call underneath.

## 2. SaaS/managed API

Not applicable, confirmed. This feature is 100% client-local: WebCrypto and browser storage
(`localStorage`/`IndexedDB`) both operate entirely within the browser sandbox with zero network
calls. There is no server component to build or buy — the requirements doc's own "Data residency:
Not applicable — client-local browser storage only, no new network destination" statement holds
up against every option surveyed above; none of them (including `cryptography-kotlin`) has a
managed/hosted variant relevant here, nor would one make sense for a local secret store.

## 3. LLM-generated implementation vs. battle-tested library — for the crypto primitive itself

**Calling the browser's native `crypto.subtle` (WebCrypto) directly for the actual AES-GCM
encrypt/decrypt operations is clearly correct, and there is no reasonable alternative that
reimplements the cipher.** This holds regardless of which "build" path is chosen (hand-rolled
interop vs. `cryptography-kotlin`):

- WebCrypto's AES-GCM implementation is browser-vendor-maintained, security-audited, and runs in
  the browser's native (often hardware-accelerated) crypto backend — not something any amount of
  careful LLM-assisted or hand-written Kotlin/JS code should try to reproduce.
- Every reimplemented-cipher candidate found in §1 (`korlibs-crypto`'s CryptoJS-derived AES,
  `andreypfau/kotlinx-crypto`'s pure-Kotlin AES) is explicitly **worse** than calling WebCrypto
  directly — reimplementing a cipher in Kotlin (by hand or via LLM) reintroduces exactly the class
  of risk (timing side-channels, subtle mode/padding bugs, lack of independent security review)
  that using a browser-native primitive avoids entirely. There is no scenario here where writing or
  generating a bespoke cipher is the right call.
- The only genuine engineering decision is the **interop wiring layer** around `crypto.subtle` —
  whether that thin layer is a few hand-rolled `external`/`js()` declarations (as this repo already
  does for OPFS and Web Locks) or a dependency like `cryptography-kotlin` that already did that
  wiring. Both call the same native primitive underneath; neither one is a "build the crypto"
  decision, only a "who writes the plumbing" decision. See §4 for that specific tradeoff.

**Verdict: Recommended (native WebCrypto, no alternative).** The only remaining question is thin
wiring layer vs. dependency — addressed below.

## 4. Fork or adapt — comparable OSS Kotlin/Wasm prior art

Searched for a comparable KMP note-taking or password-manager-adjacent app with a public wasmJs
`CredentialStore`-equivalent implementation to study or adapt.

- **No directly comparable prior art found.** The closest hit,
  [maniramezan/credential-keychain-kotlin](https://github.com/maniramezan/credential-keychain-kotlin)
  ("Kotlin Multiplatform credentials backed by platform secure storage"), is instructive by
  *exclusion*: it's brand-new (first pushed 2026-09-22, 2 stars) and its entire design principle is
  "no plaintext persistence fallback — every platform routes through its native OS secure-storage
  facility, or the call throws" (Android Keystore, Apple Keychain, macOS Keychain, Linux Secret
  Service, Windows DPAPI). It has **no web/browser target at all**, precisely because a browser
  sandbox has no OS-backed secure-storage facility to route through — which independently confirms
  this project's own Rabbit Holes section: there is no off-the-shelf "real keychain" analog for
  wasmJs, and any credible design here has to accept the JVM-parity threat model (defends against
  casual inspection, not a determined local attacker) rather than an OS-keychain-equivalent one.
- No public Logseq-adjacent, Obsidian-adjacent, or general KMP note-taking app was found with a
  published wasmJs encrypted-credential-storage module to study. Compose-for-Web + wasmJs is still
  a young enough target (see JetBrains' own 2025 "Present and Future of Kotlin for Web" post) that
  this specific pattern — browser-storage-backed, WebCrypto-encrypted, `expect`/`actual`-shaped
  credential store — doesn't appear to have prior public art yet.
- **Verdict: Not viable** — there's nothing concrete enough to fork or pattern-match against
  beyond the generic "call `crypto.subtle` from `external`/`js()`" idiom already covered in §3,
  which this codebase already knows how to do (see below).

## Consistency argument: hand-rolled interop (existing precedent) vs. `cryptography-kotlin`

`kmp/src/wasmJsMain/.../platform/OpfsInterop.kt` and `.../platform/WebLock.kt` establish a clear,
working precedent: wrap async/Promise-returning browser APIs (OPFS, Web Locks) with small
top-level `external`/`js()` functions returning `Promise<JsAny>`, `.await()` them from `suspend`
Kotlin, and keep all JS-string bodies short and single-purpose. Neither file pulls in a
third-party JS-interop library — both hand-roll it directly, with substantial doc comments
explaining browser-API-specific gotchas (transient user activation, lock-release ordering under
cancellation, etc.).

Weighing that precedent against pulling in `cryptography-kotlin`:

- **For hand-rolled interop (consistent with existing code):**
  - Zero new dependency to vet, pin, and keep current across Bazel/Gradle for a wasmJs target that
    (per the codebase's own comments) already has rough edges in third-party tooling.
  - The WebCrypto surface this project actually needs is narrow — `crypto.subtle.encrypt`,
    `.decrypt`, `.deriveKey`/`.importKey` (PBKDF2 or similar), `crypto.getRandomValues` for the IV
    — a small, bounded set of `external`/`js()` declarations comparable in size and shape to
    `OpfsInterop.kt`'s existing ~15 JS-interop functions. This is not "reimplement a cipher" (§3);
    it's the same thin-wiring pattern already proven out twice in this exact directory.
  - Matches this project's established interop idiom exactly, so a future maintainer reading
    `CredentialStore.kt` alongside `OpfsInterop.kt`/`WebLock.kt` sees one consistent pattern for
    "how this codebase talks to browser APIs from Kotlin/Wasm," not a third, different one.
- **For `cryptography-kotlin`:**
  - Removes the need to get IV handling, key-derivation parameters, and GCM tag length exactly
    right by hand — those are exactly the kind of crypto-adjacent details where a subtle mistake
    (e.g. reusing an IV, wrong tag length) is a real security bug and not just a style choice.
  - Costs a new dependency (plus its own transitive footprint) for what's ultimately a handful of
    WebCrypto calls this codebase's own precedent shows it's already comfortable wiring by hand.

**Recommendation: hand-rolled `external`/`js()` interop, matching `OpfsInterop.kt`/`WebLock.kt`,
calling `crypto.subtle` directly — no new dependency.** The WebCrypto surface needed here (AES-GCM
encrypt/decrypt, PBKDF2 key derivation, `getRandomValues`) is small, well-documented on MDN, and
squarely the same *shape* of problem (wrap a few Promise-returning browser APIs) this codebase has
already solved twice with no third-party library. `cryptography-kotlin` remains a credible fallback
if implementation discovers the hand-rolled surface is larger or trickier than expected (e.g. key
wrapping/unwrapping edge cases), but nothing found in this research forces that fallback — it's a
viable, not a required, alternative.

## Summary Verdicts

| Option | Verdict |
|---|---|
| Existing OSS crypto-wrapper library (`cryptography-kotlin`) | Viable (fallback), not required |
| Existing OSS crypto-wrapper library (ionspin / korlibs / kryptom / andreypfau) | Not recommended (stale, no wasmJs support, or reimplemented cipher) |
| SaaS/managed API | Not applicable (confirmed) |
| Native WebCrypto for the crypto primitive (vs. any reimplemented/LLM-generated cipher) | Recommended, no reasonable alternative |
| Fork/adapt a comparable OSS wasmJs `CredentialStore` | Not viable — no comparable prior art exists |
| **Hand-rolled `external`/`js()` interop over `crypto.subtle`, matching `OpfsInterop.kt`/`WebLock.kt`** | **Recommended** |
