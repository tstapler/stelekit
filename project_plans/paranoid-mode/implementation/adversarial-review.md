# Adversarial Review: paranoid-mode

**Date**: 2026-06-24
**Verdict**: BLOCKED

## Blockers

- [ ] **Nonce reuse via HKDF subkey + file-path AAD conflation** — The plan derives the per-file subkey as `HKDF(DEK, salt=file_path, ...)` and also uses `file_path` as the AEAD AAD. This means every write to the same file path uses the **same subkey** with a **fresh random nonce**. 96-bit random nonces give a collision probability of ~1% after 2^46 writes to the same file — tractable for a long-lived daily journal page written thousands of times. The subkey must incorporate a per-write random component (e.g., nonce itself as part of the HKDF salt, or derive the subkey from `HKDF(DEK, salt=nonce || file_path, ...)`) so that each write produces an independent (subkey, nonce) pair and birthday attacks on the subkey domain are eliminated. Recommendation: derive subkey as `HKDF(DEK, salt=nonce, info=file_path_utf8, len=32)` — the nonce is already in the file header and is already random-per-write.

- [ ] **Hidden volume keyslot slot-range convention is detectable** — The plan documents that slots 0–3 are the outer namespace and slots 4–7 are the hidden namespace as a "protocol convention." An adversary who captures the vault header and knows this convention can enumerate which slot indices were tried first at unlock time via a timing side-channel (even with constant-time MAC comparison, Argon2id derivation for each slot is not cheap — an eight-slot scan with four outer + four hidden is distinguishable from a four-slot scan). More critically, the plan states the app tries all 8 slots and infers namespace from the `namespace_tag` byte — but if Argon2id is run for all 8 slots on every unlock regardless of whether a hidden volume exists, the 2× KDF overhead is observable on low-end hardware. Recommendation: restructure so that all 8 slots are identical in their unlock cost path and namespace inference does not depend on a fixed slot-range convention that can be extracted by code inspection.

- [ ] **`namespace_tag` leaks hidden-volume existence on memory inspection** — The `namespace_tag` byte (`0x00` outer, `0x01` hidden) lives inside the decrypted DEK blob and is held in plaintext memory after unlock. If a debugger, crash dump, or JVM heap dump captures the `VaultManager` object graph while a hidden volume is unlocked, the `namespace_tag` identifies which graph is active. The plan has no story for securing this in-memory value. Recommendation: either encode the namespace implicitly (e.g., outer DEK and hidden DEK are separate fields never held simultaneously) or zero the `namespace_tag` immediately after branching on it.

- [ ] **Full DEK rotation (T2.4.2) has no atomic failure-recovery story** — "Re-encrypt all files, wrap in all current keyslots, update header atomically" is described in a single task line, but this is a multi-GB operation on large graphs. If the process crashes mid-rotation, the vault is in an inconsistent state: some files use the old DEK, some the new DEK, and the header has been updated. The plan's existing Saga rollback pattern (used in GraphWriter) is not applied here. Recommendation: define a two-phase commit: write all re-encrypted files to a staging area, atomically rename, then update the header. Alternatively, keep the old header in the reserved area as a backup header (already allocated at offset 2061) and document the recovery procedure explicitly.

- [ ] **`CryptoLayer` reference swap is not atomic under concurrent I/O** — At unlock time, `CryptoLayer` is injected into `GraphLoader` and `GraphWriter` (Story 4.2.3), and at lock time it is cleared (Story 2.3.3). There is no synchronization primitive described between these swaps and in-flight `GraphLoader`/`GraphWriter` coroutines. If a file read begins while unlock is in progress (or a lock event fires while a write Saga is mid-flight), the `cryptoLayer` reference can be null-checked mid-operation. The existing `DatabaseWriteActor` serializes DB writes but does not coordinate with the vault layer. Recommendation: add a `ReadWriteLock` or equivalent `Mutex` in `VaultManager` that `GraphLoader` and `GraphWriter` hold shared during I/O and that `lock()`/`unlock()` acquire exclusively before swapping the `CryptoLayer` reference.

## Concerns

- [ ] **Key material in JVM `String` from passphrase intermediate path** — T5.1.1 specifies a `CharArray`-backed passphrase field, which is correct. However, `KeyslotProvider.Passphrase.deriveSecret(engine)` must convert the `CharArray` to bytes for Argon2id input. If this conversion passes through `String` at any point (e.g., `CharArray.concatToString()`, Kotlin default `toString()`), the key material becomes interned and GC-unpredictable. The plan does not specify the `CharArray → ByteArray` conversion path. Recommendation: define a mandatory conversion path (`CharArray → Charsets.UTF_8.encode() → ByteArray`, zero-fill the intermediate ByteArray after use) and add a detekt rule banning `String` construction from the passphrase `CharArray`.

- [ ] **BouncyCastle version pinned at 1.80 — FIPS/license concern** — BouncyCastle `bcprov-jdk18on` is LGPL. The LGPL imposes linking requirements that may conflict with future commercial or proprietary distribution of SteleKit. Additionally, BouncyCastle 1.80 is not FIPS-validated; if a future enterprise user requires FIPS 140-2, this is a re-implementation. Recommendation: document the licensing constraint explicitly; evaluate `bcpkix-jdk18on` vs. `bcprov` split to minimize exposure. This is a concern now because switching crypto providers post-ship is a large migration.

- [ ] **No story for `ExternalFileChange` replay correctness on re-unlock** — Story 3.2.3 says events are "queued" when `cryptoLayer` is null and "replayed on unlock." However, if the user locks and re-unlocks (with a different provider or a new DEK after rotation), the queued events contain ciphertext encrypted with the previous DEK. Replaying them after a DEK rotation would produce authentication failures. There is no story for draining the queue on lock or for attaching the DEK version to queued events. Recommendation: clear the external-change queue on every lock event; replay from the current filesystem state on unlock rather than replaying stale ciphertext events.

- [ ] **`_hidden_reserve/` is a detectable fingerprint** — The directory name `_hidden_reserve/` is a fixed conventional name. An adversary who lists the graph directory sees this directory and immediately knows a hidden volume may exist, undermining plausible deniability. The plan acknowledges that the contents are random bytes, but the directory's existence is not random. Recommendation: use a non-descriptive, random-looking name (e.g., a UUID or a name indistinguishable from a legitimate asset directory like `assets-cache`), or better, co-locate the hidden reserve as a fixed-size blob named like a normal encrypted file.

- [ ] **Argon2id OOM behavior is unspecified** — At 64 MiB / 3 iterations on a constrained device, `Argon2BytesGenerator` allocates a large scratch buffer. On Android (out-of-scope for v1 but the interface is shared) or a JVM under memory pressure, this allocation can throw `OutOfMemoryError`. The plan classifies this as a dispatcher concern but has no story for what happens when Argon2id throws an OOM mid-unlock: the DEK is never set, but what is the UI state? Is there a retry path? Does `VaultManager` remain in a clean state? Recommendation: wrap the Argon2id call in `runCatching { }.onFailure { if (it is OutOfMemoryError) emit VaultError.ResourceExhausted }` and add a corresponding UI state and test.

- [ ] **Backup header round-trip (T2.2.3) is tested only implicitly** — The reserved area at offset 2061 is described as containing "backup header / hidden namespace metadata," and T2.2.3 says to implement backup header logic, but there is no test story that exercises recovery from a corrupted primary header using the backup. This is a data-integrity gap: if the backup header exists but is never tested in a "primary header corrupted" scenario, it may be silently broken. Recommendation: add a test: corrupt bytes 0–12 of `.stele-vault`, open vault, assert recovery from backup header succeeds.

- [ ] **Fixed modification timestamp (T7.4.1) breaks filesystem sync tools** — Setting all `.stek` file timestamps to epoch zero will confuse `rsync`, cloud sync clients (Dropbox, iCloud, Google Drive), and backup tools that use mtime for change detection. Users who rely on these tools for backup will silently lose incremental-backup functionality. Recommendation: reconsider this mitigation; the timestamp of a file reveals when the user last edited a note, not what was edited — this is a metadata leakage concern but the cure may be worse than the disease. At minimum, make this opt-in and document the tradeoff clearly rather than defaulting it on.

- [ ] **No story for plaintext file detection at graph open** — If a user opens a paranoid-mode graph that has been partially decrypted (e.g., a previous migration tool left some `.md` files alongside `.md.stek` files), the `CryptoLayer` will return `VaultError.NotEncrypted` for the plaintext files (T3.1.3). The plan does not specify whether the loader silently ignores these, surfaces a warning, or fails. Ignoring them exposes sensitive data; failing may make the graph unreadable. Recommendation: add a story that detects plaintext `.md` files in paranoid-mode graphs at load time and surfaces a "vault integrity warning" rather than silently loading plaintext.

- [ ] **OS keychain slot on WASM is not durable across browser profile changes** — The plan stores the wrapped DEK as a non-extractable `CryptoKey` in IndexedDB. `CryptoKey` objects stored in IndexedDB are tied to the browser's origin and profile. A user who clears site data, switches browsers, or uses a private browsing session will lose this keychain slot permanently with no recovery path (T7.5.2 only warns about IndexedDB deletion but does not address the broader profile-change scenario). Recommendation: expand the warning to cover browser-profile changes and private mode; enforce that WASM OS-keychain slots can only be registered alongside at least one passphrase or key-file slot (T7.5.3 partially covers this but should be explicit).

- [ ] **No concurrency test for VaultManager.lock() during active GraphWriter Saga** — The plan tests lock/unlock state transitions (Story 4.4) but does not include a test for `lock()` being called while a multi-step GraphWriter Saga is in flight. The Saga's compensation step (`restores old encrypted bytes`) requires the `CryptoLayer` to still be valid. If `lock()` clears the `CryptoLayer` mid-Saga, compensation will fail. Recommendation: add a test that starts a write Saga, calls `lock()` concurrently, and asserts either the Saga completes atomically or rolls back cleanly without data loss.

## Minors

- The STEK file format header includes a 1-byte version field but no format for communicating the specific cipher suite in use. "Algorithm agility" (Goal 5) requires future formats to identify their algorithm, but the version byte alone does not encode this. A future v2 file that uses AES-GCM would need a separate algorithm field or a version-to-algorithm registry. The vault header has a version byte but files do not — consider adding a 1-byte cipher-suite field to the per-file header in v1 to avoid a format bump for algorithm changes.

- The plan specifies `HKDF-SHA256` for both subkey derivation and the header MAC key. Using the same KDF with different `info` labels is standard practice (and the plan does differentiate by `info`), but the header MAC uses `HMAC-SHA256` with a key derived from the DEK. An attacker who recovers the DEK can forge arbitrary headers. This is by design (the header is only authenticated, not kept secret), but it should be documented in the threat model so future engineers don't introduce a dependency on header authenticity for security properties stronger than integrity.

- T7.4.2 (padding plaintext to 4 KB boundary) is flagged as optional. Exact-size leakage for markdown files is a real coarse-grained signal (a 2-byte file is a tag; a 50 KB file is likely an image reference) but defaulting it off undermines its security value. Recommendation: enable padding by default with a modest boundary (1 KB) and make it configurable; "off by default" effectively means most deployments will not have it.

- Story 8.1.3 ("random pre-fill of graph directory with dummy data before first file write") adds implementation complexity and creates files that will confuse users who inspect the directory. The stated goal (prevent distinguishing encrypted from unencrypted graphs by file count) is not a threat in the requirements. Consider removing this scope entirely or deferring to a later epic to avoid feature creep.

- The plan benchmarks Argon2id at 64 MiB / 3 iterations and claims <1 s on desktop JVM. However, the JVM startup time for BouncyCastle's first Argon2id call can add 100–300 ms due to class loading. The benchmark claim should specify whether it includes or excludes class-loading warmup, and the vault creation benchmark (T8.1.2) should be conducted after JVM warmup to avoid misleading parameter calibration.

- The `CryptoEngine` interface is described as using `Interface injection pattern preferred over expect/actual`. This is a valid choice, but it means `commonMain` code can only access crypto via the interface, which must be instantiated somewhere platform-specific and injected. The plan does not specify the injection point (DI framework? manual wiring? `GraphManager`?). This wiring is load-bearing and should be specified before Phase 1 to avoid a refactor mid-Epic 2.

- The plan states "Verify header MAC after DEK recovery" (T2.3.2, Story 7.3). The MAC key is `HKDF(DEK, salt="vault-header-mac", ...)`. If an attacker can supply a crafted vault header that passes all 8 keyslot AEAD checks but produces a wrong MAC, the `HeaderTampered` error reveals that the AEAD step succeeded (i.e., the passphrase was correct). This is a minor oracle: the attacker learns passphrase validity before MAC validation. This is inherent to the two-step design and is unlikely to be exploitable in practice, but it should be documented.

---

## Re-Review — 2026-06-24 (post-patch)

Five BLOCKER-level issues were patched. This section confirms which are resolved and documents any new issues introduced by the patches.

### Blocker 1 — Nonce reuse via HKDF subkey: RESOLVED

The subkey derivation is now `HKDF-SHA256(DEK, salt=nonce_bytes, info=file_path_utf8, len=32)`. Because the HKDF salt is the per-write random nonce (already stored in bytes 5–16 of the STEK header), every write produces an independent (subkey, nonce) pair. The nonce is already on disk for decryption — no extra storage is needed. T1.2.4a adds a test asserting subkey independence across two writes to the same path with distinct nonces.

**Confirmed resolved.** The birthday-attack concern on same-file subkey reuse is eliminated: even if two writes happen to produce the same 96-bit nonce (probability ~2^{-96}), the HKDF salt == the nonce means a nonce collision would imply a subkey collision only if DEK and info are also identical, which is guaranteed by construction — so this reduces to standard AEAD nonce-collision safety, which is the same as any random-nonce scheme. No new issues introduced by this fix.

**One residual concern (not a new blocker):** The HKDF call signature in T1.2.3 describes the engine as imposing no convention — the convention is enforced solely by `CryptoLayer.encrypt` in T3.1.1. A future implementer adding a second call site to `hkdfSha256` could easily pass `salt=file_path` and silently reintroduce the old vulnerability. The spec should note that the `salt=nonce_bytes` convention is a security invariant that must be enforced at all `hkdfSha256` call sites, not just inside `CryptoLayer`.

---

### Blocker 2 — Hidden volume slot-range detectable: RESOLVED WITH CAVEAT

The fixed range convention is removed. All 8 slots are tried in a uniformly random order (SecureRandom permutation). Namespace is inferred from `namespace_tag` only, and hidden keyslots are written to randomly chosen indices at creation. The slot layout is now indistinguishable by index.

**Timing oracle:** The plan (section 2b) states "All 8 slots are always attempted in constant time regardless of which slot(s) succeed. Results are collected, then the valid one is selected post-loop." This resolves the slot-count timing side-channel from the original review.

**Argon2id 8× overhead concern:** The plan's constant-time approach means all 8 Argon2id derivations are always performed, even for a vault with no hidden volume and only 1 active slot. At 64 MiB / 3 iterations ≈ 350–1000 ms per derivation, a full 8-slot scan could take 2.8–8 s, violating NFR-6 (≤ 5 s) on low-end hardware. This is a **new concern introduced by the constant-time fix**. The original review noted the overhead; the patch's resolution does not resolve it — it trades a timing oracle for a UX/NFR violation.

**Mitigation path:** Argon2id derivation can be parallelized across slots (all 8 run concurrently on `Dispatchers.Default`). On a 4-core machine this reduces wall time to ~2 derivations' worth ≈ 700 ms–2 s, within NFR-6. The plan does not describe this parallelism. This should be added to T2.3.2 before Phase 2 implementation.

**Verdict on Blocker 2:** Cryptographic detectability is resolved. The constant-time guarantee introduces a performance concern that may violate NFR-6 on constrained hardware. **Recommend parallel Argon2id derivation to stay within NFR-6; not a blocker if accepted as a known risk.**

---

### Blocker 3 — `namespace_tag` leaks in memory: PARTIALLY RESOLVED

The fix zeroes the `namespace_tag` byte at index 32 of the decrypted DEK buffer immediately after namespace branching, before any `suspend` point. T2.3.2a adds a test asserting the byte is zero after `unlock()` returns. This is a meaningful improvement.

**Is it testable?** Yes — T2.3.2a directly tests the post-unlock state. However, the test only covers the return path. It does not verify that the zeroing happens before a `suspend` point mid-function. If the implementation uses `withContext(Dispatchers.Default)` for Argon2id and then branches on the tag, a suspension before the zeroing line could expose the tag during a heap dump between the suspension and the zeroing. The test as specified passes regardless.

**Is it structural or procedural?** It is procedural — a comment and a single imperative line. There is no type-level invariant preventing a future refactor from moving the branch-and-zero pattern across a `suspend` point (e.g., if unlock is restructured to call a `suspend` helper before zeroing). The zeroing protocol is described twice in section 3b (duplicate paragraph) but is not encoded in a type. A `SecretNamespaceTag` inline value class that auto-zeroes on `use { }` would make this structural; the current design requires future implementers to know and follow the convention.

**Residual risk:** Low but real. **Not a blocker; recommend a structural enforcement or at minimum a @RequiresZeroBeforeSuspend annotation/comment to prevent regression.**

---

### Blocker 4 — DEK rotation atomic crash-recovery: RESOLVED WITH NEW CONCERN

T2.4.2 now defines a two-phase commit: stage to `_rotation_staging/`, atomic rename per file, then update the vault header as the durable commit point. Crash recovery logic distinguishes "header not yet updated" (resume) from "header already updated" (clean up stale staging). T2.4.2a adds a mid-rotation crash simulation test.

**Is the manifest sufficient?** Yes — the manifest lists every `old_path → staging_path` mapping. On resume, the recovery logic can determine which files have already been renamed (they are missing from `_rotation_staging/` and present at the final path) and which remain. The protocol is sound.

**New issue: `_rotation_staging/` is a detectable artifact.** The directory `_rotation_staging/` is a fixed conventional name, visible to any process listing the graph root. Its presence signals that a rotation is in progress, revealing operational metadata. For a hidden-volume scenario, an adversary monitoring the filesystem in real time could correlate rotation events with vault activity. This is a lower severity than the original blockers — rotation is rare and brief — but it is a new fingerprint introduced by the patch.

**Mitigation:** Use a random-named staging directory (e.g., `.stele-{uuid}/`) and record its name inside the vault header's reserved area (offset 2061) so crash recovery can locate it. This simultaneously removes the fixed-name fingerprint and re-uses the already-allocated reserved region for its intended purpose.

**Verdict on Blocker 4:** Protocol is sound and recoverable. The fixed staging directory name is a new minor artifact concern. **Not a blocker; recommend random staging directory name recorded in the reserved area.**

---

### Blocker 5 — `CryptoLayer` swap unsynchronized: RESOLVED WITH RACE QUESTION

Constructor injection into a newly built `RepositorySet` eliminates the mutable reference swap on live instances. The existing `switchGraph()` rebuild pattern is explicitly cited and applied. `VaultManager.lock()` drains the old `RepositorySet` before teardown. A `kotlinx.coroutines.Mutex` serializes concurrent lock/unlock calls. T7.3.4 and T7.3.5 add the mutex task and a concurrency test.

**Race during RepositorySet rebuild:** Between the moment `lock()` signals the old `RepositorySet` to drain and the moment the new `RepositorySet` is constructed and registered, there is a window where `GraphManager` has no active `RepositorySet`. Any UI action that tries to trigger a load or save during this window will encounter a null/missing `RepositorySet`. The plan does not specify what `GraphManager` does during this window (queue requests? return error?). If requests are silently dropped, data could be lost. If they throw, the ViewModel must handle this gracefully. T7.3.5 only tests the Saga-vs-lock race, not the rebuild-window case.

**Mutex deadlock question:** `VaultManager` uses a `Mutex` to guard lock/unlock transitions (T7.3.4). If `unlock()` internally calls a `suspend` function that also tries to acquire the same mutex (e.g., a `VaultManager.withCryptoLayer` guard used in `GraphLoader` dispatching back into `VaultManager`), deadlock is possible. The plan does not describe `withCryptoLayer` — the `Mutex` is described only as guarding lock/unlock transitions, not as a reader-writer guard held during I/O. So this concern applies only if a future implementer adds per-I/O mutex acquisition. As written, the risk is low but the plan should document that the mutex must not be held during I/O operations.

**Verdict on Blocker 5:** The core race condition is resolved. Two residual concerns exist: (1) the rebuild window is undefined behavior, and (2) the mutex scope must be documented to prevent future deadlock. **Not a blocker; both are addressable in implementation without plan changes, but T4.2.3 should specify the rebuild-window behavior explicitly.**

---

### New Issues Introduced by Patches

**NEW-1 (Medium): Argon2id 8× wall-time may violate NFR-6 without parallelism**
The constant-time slot scan (all 8 slots always attempted) required by the Blocker 2 fix means up to 8 sequential Argon2id derivations. At 350–1000 ms each, sequential execution takes 2.8–8 s — potentially exceeding NFR-6's ≤ 5 s limit. The plan does not specify parallelism for the slot scan. Add a note to T2.3.2 requiring that all 8 Argon2id derivations run concurrently (e.g., via `async {}` blocks under `Dispatchers.Default`, results collected after all complete). This is straightforward and does not compromise the constant-time guarantee.

**NEW-2 (Low): `_rotation_staging/` presence is a new observable artifact**
The staging directory name is fixed and conventional (described above under Blocker 4). Recommend a random UUID name persisted in the vault header's reserved area.

**NEW-3 (Low): In-flight `BlockStateManager` changes lost on RepositorySet rebuild**
When `unlock()` rebuilds the `RepositorySet` (T4.2.3), any unsaved edits buffered in `BlockStateManager` (which holds local block state with a 500 ms debounce write) will be lost if the debounce timer has not fired before the rebuild. The plan specifies that `VaultManager.lock()` drains in-flight Sagas in the old `RepositorySet`, but it does not mention flushing `BlockStateManager`'s debounce buffer. On an unlock-triggered rebuild (e.g., after an idle-timeout auto-lock and re-unlock), the user may lose the last few seconds of edits. Recommend that the lock sequence calls `BlockStateManager.flush()` (or equivalent immediate-save API) before tearing down the old `RepositorySet`.

**NEW-4 (Low): Duplicate `namespace_tag` memory-safety paragraph in section 3b**
Section 3b contains the `namespace_tag` memory safety paragraph verbatim twice (lines describing the zero-before-suspend protocol appear at two separate locations in the plan). This is a copy-paste artifact from the patch. Should be deduplicated before the plan is finalized to avoid confusion about which description is canonical.

---

## Updated Verdict

**CONDITIONAL PASS** — All 5 original blockers are resolved or resolved-with-caveats. No new blockers were introduced.

Remaining work before implementation begins:
1. **(Medium, pre-Phase 2)** Add parallel Argon2id derivation to T2.3.2 to meet NFR-6 under constant-time slot scan.
2. **(Low, pre-Phase 5)** Specify rebuild-window behavior in T4.2.3 (queue, error, or await new RepositorySet).
3. **(Low, pre-Phase 6)** Replace fixed `_rotation_staging/` name with a random UUID persisted in vault header reserved area.
4. **(Low, pre-Phase 6)** Add `BlockStateManager.flush()` call to the lock sequence to prevent debounce-buffer data loss on rebuild.
5. **(Low, any phase)** Document that the `VaultManager` mutex must not be held during I/O operations.
6. **(Cosmetic)** Deduplicate the `namespace_tag` paragraph in section 3b.

The plan is ready to proceed to Phase 1 (CryptoEngine) with item 1 resolved before Phase 2 begins.
