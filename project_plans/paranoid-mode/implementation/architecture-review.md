# Architecture Review: paranoid-mode

**Date**: 2026-06-24
**Verdict**: CONCERNS

*(No `docs/adr/ADR-000-architecture-constitution.md` found — no constitution violations section applies.)*

---

## Blockers

*(None identified)*

---

## Concerns

- [ ] **Epic 3 / Story 3.1–3.3 (T3.1.1, T3.2.1, T3.3.1) — SRP violation via optional nullable injection**
  `GraphLoader` and `GraphWriter` each receive `CryptoLayer?` as optional constructor parameters. This means both classes now carry two distinct responsibilities: (1) their existing graph I/O logic, and (2) crypto plumbing conditional on whether paranoid mode is active. Every read and write path will contain `cryptoLayer?.let { ... } ?: plaintext` branching, which is Feature Envy toward the vault subsystem. A cleaner separation is a `FileSystem` port/adapter that `GraphLoader`/`GraphWriter` already depend on: introduce a `ParanoidFileSystem` decorator that wraps the real filesystem and performs encrypt/decrypt transparently. `GraphLoader` and `GraphWriter` remain unaware of encryption entirely; they call `fileSystem.readFile(path)` / `fileSystem.writeFile(path, bytes)` as today. This eliminates the nullable injection, keeps both classes closed for modification, and makes crypto logic independently testable without instantiating a full `GraphLoader`. **Remediation**: Define a `FileSystem` interface (or extend the existing platform I/O abstraction) and introduce `ParanoidFileSystem : FileSystem` that delegates to a real filesystem and wraps calls with `CryptoLayer`. `GraphManager` wires the correct `FileSystem` implementation at graph-open time.

- [ ] **Epic 2 / Story 2.1 (T2.1.4) — `KeyslotProvider.deriveSecret` breaks Interface Segregation and Dependency Inversion**
  `KeyslotProvider` is defined as a sealed class with a method `deriveSecret(engine: CryptoEngine): ByteArray`. This couples the domain value-object (`KeyslotProvider`) to the infrastructure service (`CryptoEngine`). Domain objects should not know about crypto infrastructure; the direction of dependency is inverted. Additionally, callers who only need to inspect which provider type a keyslot uses (e.g., the settings UI) must now have `CryptoEngine` on their classpath. **Remediation**: Remove `deriveSecret` from `KeyslotProvider`. Extract a separate `KeyslotSecretDeriver` service (or put the derivation logic on `VaultManager` or a `KeyslotUnlocker` helper) that accepts a `KeyslotProvider` and a `CryptoEngine` and returns the secret. `KeyslotProvider` becomes a pure sealed value type: `Passphrase(chars: CharArray)`, `KeyFile(path: String)`, `OsKeychain(graphId: String)`.

- [ ] **Epic 2 / Story 2.3 (T2.3.2) — `VaultManager.unlock` returns DEK as raw `ByteArray`; missing typestate / value-object boundary**
  The unlock method signature `unlock(path, provider): Either<VaultError, DEK>` uses `DEK` (presumably a `ByteArray` alias). A raw `ByteArray` is primitive-obsessed: nothing in the type system prevents callers from logging it, storing it in a `val`, passing it to unrelated functions, or forgetting to zero-fill it. A `DecryptionKey` value class (inline class wrapping `ByteArray`) with `internal` visibility on its raw accessor forces all callsites through controlled access points. Combined with `AutoCloseable` (or Kotlin's `use` extension), the zero-fill-on-close contract becomes structural rather than documentary. **Remediation**: Introduce `value class DecryptionKey internal constructor(private val raw: ByteArray) : AutoCloseable` in `vault/`. Expose `internal fun raw(): ByteArray` and `override fun close() { raw.fill(0) }`. `VaultManager.unlock` returns `Either<VaultError, DecryptionKey>`; callers use `decryptionKey.use { key -> ... }`.

- [ ] **Epic 3 / Story 3.1 (T3.1.1–T3.1.2) — `CryptoLayer.encrypt` returns `ByteArray` (no error path); asymmetry with `decrypt`**
  `decrypt` returns `Either<VaultError, ByteArray>` but `encrypt` returns `ByteArray` (implied by the task description's phrasing "HKDF subkey, random nonce, STEK header prepend"). Encryption can fail: secure-random failure (OS entropy exhaustion), JCE initialization failure, or HKDF failure. A function that silently throws exceptions instead of returning `Either` breaks the project's stated error-handling convention. **Remediation**: Change `encrypt` signature to `fun encrypt(filePath: String, plaintext: ByteArray): Either<VaultError, ByteArray>` (matching `decrypt`). Callers already use `.onLeft` / `.fold`; adding this makes the write saga in `GraphWriter` (T3.3.2) straightforward to integrate with the existing Arrow Saga rollback.

- [ ] **Epic 4 / Story 4.2 (T4.2.3) — Mutable injection of `CryptoLayer` into `GraphLoader`/`GraphWriter` post-construction**
  Story 4.2 describes "inject `CryptoLayer` into `GraphLoader` and `GraphWriter`" after unlock succeeds. If these classes accept the `CryptoLayer?` as a mutable var that is set post-construction, this is thread-unsafe (the file-watching thread and the coroutine dispatchers both access these objects). It also conflicts with the Coroutine section of CLAUDE.md (no mutable shared state injected from the outside). **Remediation**: Do not mutate existing `GraphLoader`/`GraphWriter` instances. Instead, `GraphManager` should construct a *new* `RepositorySet` (and therefore new `GraphLoader`/`GraphWriter` instances) after unlock, passing the now-available `CryptoLayer` (or the `ParanoidFileSystem` adapter from the earlier remediation) at construction time. This aligns with `GraphManager.switchGraph`'s existing pattern of rebuilding the `RepositorySet` on graph change.

- [ ] **Epic 6 / Story 6.1 (T6.1.3) — Hidden volume virtual filesystem is an unbounded in-memory structure**
  The plan specifies an "in-memory virtual filesystem for hidden pages/blocks" within the hidden volume's single encrypted archive. Given the project's explicit rules against unbounded in-memory data structures (CLAUDE.md: "graph-scale reads must be paginated, projected, or chunked — never O(graph)"), an in-memory FS for the hidden volume is a latent OOM vector and violates the design constraints already enforced at the repository layer. The "single archive" approach also means the hidden volume's working set is loaded entirely into memory on unlock, which contradicts the pagination architecture that the rest of the system enforces. **Remediation**: Either (a) store hidden volume files as individual encrypted `.stek` files in `_hidden_reserve/` using opaque, randomized filenames (trading some metadata-leakage risk for architectural consistency) so the normal `GraphLoader` pagination machinery works, or (b) implement a streaming virtual-FS with the same chunked-access semantics as `getAllPagesSnapshot()`. Defer the final decision to the ADR for OPEN-1/OPEN-2, but document the constraint explicitly before Epic 6 implementation begins.

- [ ] **Epic 1 / RISK-1 — `CryptoEngine` interface design is blocked on WASM initialization semantics; interface stability is not yet proven**
  The plan flags this as a High risk but defers the prototype to "before Phase 1." Because the `CryptoEngine` interface is the foundation for all 8 epics (T1.1.1), an interface that must be revised after the WASM prototype (e.g., making initialization `suspend`, adding a `ready(): Boolean`, or splitting into `SyncCryptoEngine` / `AsyncCryptoEngine`) would require retrofitting all Stories in Epics 1–3. The plan says "Resolution needed before Phase 1" but gives no gate condition for actually blocking Phase 1 from starting. **Remediation**: Make the WASM interop prototype an explicit, time-boxed spike (1–2 days) with a binary acceptance criterion: "WASM `CryptoEngine` initializes and encrypts/decrypts a 1 KB buffer via libsodium.js without requiring interface changes." Phase 1 Story 1.1 tasks are gated on this spike passing. If the spike fails, the fallback (split WASM bundles) must be chosen before `CryptoEngine` is finalized.

---

## Nitpicks

- **OPEN-1 resolution is stated in the plan ("use graph-root-relative path") but no ADR is written.** The plan says "Record this in an ADR." That ADR should be written before Phase 3 (T3.1.1) to prevent the path convention from drifting between `CryptoLayer`, `GraphLoader`, and `GraphWriter` implementations.

- **Story 7.4 (T7.4.2) — Plaintext padding to 4 KB boundaries is listed as "optional / flag as configurable" but has no corresponding setting field in `VaultSettingsScreen` (Story 5.2).** If this will be user-configurable, Story 5.2 must be updated; if it will be a build-time constant, the task description should say so.

- **Story 4.3 (T4.3.2) — "Reset countdown on any user interaction event (key press, pointer move)" has no proposed mechanism.** Compose does not have a single global interaction hook. A `Modifier.pointerInput` or `onKeyEvent` wrapper at the root composable level should be mentioned explicitly; otherwise each platform target may implement this inconsistently.

- **Story 2.5 (T2.5.6) — The constant-time test validates intent ("all 8 slots tried even if slot 0 succeeds") but not timing.** A unit test asserting that all 8 loop iterations execute is correct, but a wall-clock timing test would be brittle. The comment in the code and a code-review checklist item (not a timing assertion) are the right enforcement mechanism here. The task description should clarify this distinction to avoid a brittle microbenchmark being committed.

- **The `_hidden_reserve/` directory name is a mild deniability leak.** Any forensic analyst examining directory listings will see a directory with an unusual name and a large random-byte file. A more opaque name (e.g., a random UUID directory that is recorded only in the vault header's reserved area) would improve deniability. Minor UX cost (it cannot be shown in the UI by name), but the cryptographic benefit is non-trivial.

- **`VaultError` is a sealed class extending `DomainError` (T1.1.2), but the plan shows `VaultError.NotEncrypted` used in `CryptoLayer.decrypt` (T3.1.3) as a soft/fallback case rather than an error.** A better name would be `CryptoLayer.DecryptResult` with a `NotEncrypted` variant, or `VaultError.NotEncrypted` should be documented as a non-fatal sentinel, not an error, to prevent calling code from treating it identically to `VaultError.AuthenticationFailed`.
