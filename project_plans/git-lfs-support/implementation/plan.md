# Implementation Plan: git-lfs-support

**Feature**: Git LFS support across SteleKit git sync: a safety guard on every platform (ships first), full LFS on JVM and Android (JGit filters plus our own authenticated commonMain client), web read and layered write (direct, user-owned proxy, optional workflow converter, read-only fallback), iOS guard plus placeholders, docs and demo, and the test fixtures that prove it.
**Date**: 2026-10-11
> **Owner decisions resolved 2026-10-11 (supersede any conflicting text below):**
> - **O1 = NO proxy at all.** Strategy ii (`Proxy` mode), Story E4.3, the `lfsProxyUrl` setting and the `docs/reference/lfs-cors-proxy/` template are **cancelled**. The web write resolver becomes `Direct` -> `Converter` (opt-in) -> `ReadOnly`; on github.com and gitlab.com, writes are therefore possible only through the converter or are read-only.
> - **O2 = YES, as an opt-in advanced mode.** Story E4.4 (staging-ref converter) is **unblocked**; it stays off by default behind `lfsConverterEnabled`, with the ADR-003 `StagingRefScope` exception and the token-scope/raw-blob warnings in the setup UI.
> - **Follow-up (not done):** reconcile the remaining Proxy wording in this plan, `validation.md`, `pre-mortem.md` and ADR-002 (grep `Proxy`, `lfsProxyUrl`, `E4.3`), and re-run the adversarial review on the result.
> - **Execution order chosen by the owner:** build Phase 1 (guard) first, on its own branch.

**Status**: Ready for implementation. Phase 1 (E1) has no dependency on a later phase (the `LfsMode` type, its minimal store and the platform capability flag are defined in Task E1.4f) and ships first. Story E2.2 onward waits on the E2.1 go/no-go; Story E4.4 is BLOCKED on owner decision O2 but fully specified.
**ADRs**: ADR-001 (JGit LFS reuse, own network client), ADR-002 (web LFS write strategy; qualifies `docs/adr/ADR-015-wasm-git-data-api-write-back.md`), ADR-003 (guard semantics and the staging-ref exception), ADR-004 (client in commonMain). All under `project_plans/git-lfs-support/decisions/`.

**Inputs read**: `requirements.md`; `research/stack.md` (S), `research/web-lfs.md` (W), `research/features.md` (F), `research/pitfalls.md` (P, risks R1..R22). Cross-references below cite them as `P R3`, `W section 5`, etc.

Path aliases (all exact, each expands under `kmp/src/<set>/kotlin/dev/stapler/stelekit`):
`{C}` commonMain, `{CT}` commonTest, `{BT}` businessTest, `{JC}` jvmCommonMain, `{J}` jvmMain, `{JT}` jvmTest, `{A}` androidMain, `{AT}` androidUnitTest, `{W}` wasmJsMain, `{WT}` wasmJsTest, `{I}` iosMain, `{IT}` iosTest. Repo-root files are written in full.
Every new `@Test` class in businessTest or in a commonTest file listed in `business_common_tests` must be added to `{BT}/AllBusinessTests.kt` (`AllBusinessTestsCompletenessTest` enforces it); a commonTest file also goes into `business_common_tests.srcs` in `kmp/src/commonTest/kotlin/BUILD.bazel`; a new wasm test class must be appended to the `--tests` allow-list in `.github/workflows/ci.yml` (the "Run wasmJs browser tests" step); an androidUnitTest class goes into `{AT}/AllAndroidUnitTests.kt`. Each test-creating task below lists those files.

## Cost classes (price-weighted, no human time)

Per `~/.claude/skills/sdd/skills/ESTIMATION.md`. One task = one worker-agent run in one context, max 3 to 5 files.

| Class | Raw tokens per run | Shape here |
|---|---|---|
| XS | under 30k | One config or one small function, pattern exists |
| S | 30k to 80k | A component plus its test, 2 to 3 files |
| M | 80k to 180k | A feature across layers or a tricky algorithm, 3 to 5 files |
| L | 180k to 300k | Not used: every L was split |

Cost unit (CU) = fresh-input-equivalent tokens = `in + 1.25*cache_write + 0.1*cache_read + 5*out`. Story cost = sum of task midpoints (XS 20k, S 55k, M 130k) x price factor x verification multiplier 1.75 (spec-compliance plus quality review per story, `6-verify`, CI reruns). The "Effort estimate" section at the end has the table. Bands and weights are INFERRED; recalibrate after the first implementer run.

## Domain Glossary

| Term | Definition | Notes |
|---|---|---|
| `LfsOid` | SHA-256 object id of LFS content, 64 lowercase hex chars | Value class; never a raw string |
| `LfsPointer` | Parsed pointer file: `oid`, `size`, optional `ext-N-name` extension lines | `{C}/git/lfs/LfsPointer.kt`; spec-strict; legacy version URLs accepted on read, canonical written |
| `LfsPointerParse` | Sealed: `Valid(pointer)`, `Corrupt(reason)`, `NotAPointer` | `Corrupt` = starts with the version line but violates the spec (CRLF, BOM, unsorted keys, 1024 bytes or more, bad oid) |
| `LfsPointerSniffer` | Cheap pre-check: size under 1024 and starts with the version prefix | Used before downloading anything on web and before reading file bodies |
| `AttrState` | Sealed: `Set`, `Unset`, `Unspecified`, `Value(v)` for one attribute | gitattributes(5) states |
| `AttrRule` | One `.gitattributes` line: pattern plus attribute map | Quoted patterns and macros supported |
| `AttributeRuleSet` | Ordered layers (`.git/info/attributes` over deepest directory over root) with `resolve(path, attr)` | Pure Kotlin matcher, differential-tested against JGit and `git check-attr` |
| `LfsPathPolicy` | `isTracked(path)`: resolved `filter` attribute equals `lfs`; `.gitattributes` itself never tracked | The one answer to "is this path LFS" on all platforms |
| `LfsRulesLoader` | Loads the attribute layers for a set of changed paths from a `FileSystem` (or a remote, on web) | Evaluated per operation, never cached across a pull (P R16) |
| `LfsContentProbe` | Port: `size(path)` and `readHead(path, 1024)` | Lets the guard avoid loading big binaries |
| `LfsViolation` | `path` plus reason `RawBinary`, `CorruptPointer`, `ConflictMarkers` | Guard output |
| `LfsGuardVerdict` | `held: List<LfsViolation>`, `allowed: List<String>` | Hold-back, not block (ADR-003) |
| `LfsGuard` | Pure function (paths, rules, probe, optional `StagingRefScope`) to `LfsGuardVerdict` | Property-tested on every platform |
| `LfsGuardCoordinator` | Thin service in `{C}/git/lfs/` that `GitSyncService` calls: loads rules, runs the guard, builds the hold-back set | Keeps `GitSyncService` from growing another concern |
| `StagingRefScope` | The only exception to the guard: an LFS-converter staging ref matching `refs/heads/stelekit/lfs-inbox/<id>` | Null by default; exists only if owner decision O2 is YES |
| `LfsIndexVerifier` | JVM and Android pre-commit check that every staged tracked blob is a pointer | Defense in depth behind the service guard |
| `LfsLegacyRawDetector` | Classifies tracked files whose HEAD blob is raw and whose working file is unchanged as `LegacyRawPending` | Never auto-converted (ADR-003 section 4) |
| `LfsMigrationConfirmation` | Per-graph persisted user confirmation to convert legacy raw files | `FirstSyncConfirmation` pattern |
| `LfsEndpoint` | Resolved LFS base URL plus how to authenticate (Basic, Bearer, none) plus optional proxy base | From `lfs.url`, `.lfsconfig`, or `<remote>.git/info/lfs` |
| `LfsAuth` | Port that yields the `Authorization` header for batch and verify calls only | Never attached to object hrefs unless the action's own `header` says so |
| `LfsBatchClient` | commonMain Ktor client: `batch`, `download`, `upload`, `verify`, retry, timeouts, resume | ADR-004 |
| `LfsObjectStore` | Port: streaming write with hash-while-writing, atomic commit, `has(oid)`, `resumeOffset` | JVM and Android `<gitdir>/lfs/objects`; web OPFS |
| `LfsTransferService` | jvmCommon orchestrator: prefetch before checkout or merge, upload before push, storage preflight, progress | Shared by JVM and Android |
| `LfsPointerScanner` | Finds pointer blobs: in a tree, in a tree diff, in commits to push (remote name configurable) | Reimplements `LfsPrePushHook.findObjectsToPush` without hard-coded `origin` |
| `LfsStoragePreflight` | Pure check: required bytes (objects x copies) versus free bytes | Counts the triple copy on Android |
| `LfsMode` | Enum `OFF`, `GUARD_ONLY`, `AUTO` | Per graph; defined in Phase 1 (Task E1.4f), stored under `git_lfs_mode_<graphId>`, default `GUARD_ONLY` |
| `LfsPlatformCapabilities` | Platform-provided constants: `transferSupported` (JVM true; iOS and web false) and `androidLfsEnabled` (Android, false until Task E7.5a) | A capability, not a user setting; effective mode is `GUARD_ONLY` whenever the platform flag is false regardless of the stored mode |
| `LfsModeResolver` | Pure: (stored `LfsMode`, `LfsPlatformCapabilities`, adapter kind, repo flags) to the effective `LfsMode` | Created in E1.4f, extended in E2.9b |
| `LfsAutoDownload` | Enum `NEVER`, `ON_DEMAND`, `ALL` | Per graph |
| `LfsSettings` | `mode`, `autoDownload`, `maxObjectBytes`, `maxSyncBytes`, `wifiOnly`, `proxyUrl`, `converterEnabled` | Stored as per-graph `Settings` keys (no SQL migration) |
| `LfsDownloadPolicy` | Pure: (settings, metered, pointer list) to what to fetch now, defer, or skip | Caps never exceeded (property test) |
| `LfsRepoSurvey` | First-sync scan result: tracked patterns, pointer count, summed size, legacy-raw count | From `LfsTreeSource` port |
| `LfsFirstSyncDecision` | Persisted user choice after the survey: download all, on demand, none | Automatic sync stays pending until chosen |
| `LfsObjectResolver` | commonMain port: `stateOf(pointer)`, `resolve(pointer)` returning a loadable URI or a typed state | Impls: JVM and Android local, wasm, `UnavailableLfsObjectResolver` (iOS default) |
| `LfsAssetState` | Sealed: `Ready(uri)`, `Pending(size)`, `Downloading(done, total)`, `Failed(error)`, `Blocked(reason)` | Drives `LfsAssetPlaceholder` |
| `LfsWriteMode` | Sealed: `Direct`, `Proxy`, `Converter`, `ReadOnly` | Web only (ADR-002) |
| `LfsWriteCapabilityResolver` | Pure: probe result, proxy health, converter flag/approval to `LfsWriteMode` | Precedence Direct, Proxy, Converter, ReadOnly |
| `WasmLfsCorsProbe` | Real `POST` batch with `objects=[]` and `Authorization`; `TypeError` means blocked | Cached per host with TTL |
| `LfsProxyEndpoint` | Proxy base URL plus `X-Target` mapping | Template rewrites hrefs server-side |
| `LfsHostStrategy` | Web read strategy per host: GitHub media URL, GitLab `raw?lfs=true`, direct batch | `{W}/git/lfs/WasmLfsHostStrategy.kt` |
| `FakeLfsServer` | In-process `com.sun.net.httpserver` LFS server with auth, fault and recording knobs | businessTest support; no new dependency |

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|---|---|---|---|---|
| LFS on JVM/Android | Hybrid: JGit filters and store, own client (ADR-001) | Ports and adapters | Stock `BuiltinLFS`; pure custom; shell `git lfs` | No https auth in JGit LFS (S headline 2) |
| Creative alt 1 | Guard-only on all platforms | - | Rejected | Owner asked for real support, not just a guard |
| Creative alt 2 | Single "convert on the server" path for web (workflow only) | - | Rejected as the only path | Async, needs repo workflow, raw staging blob; kept as optional strategy iii |
| Creative alt 3 | Layered capability-probed web write (ADR-002) | Strategy (GoF) plus capability probe | Chosen | Each user gets the cheapest mode their host allows |
| LFS client | Port and adapter in commonMain (ADR-004) | Hexagonal | JVM-only client; JGit global connection factory | One protocol implementation shared with web |
| Guard | Pure function (functional core) plus thin coordinator | type-driven-design | Logic inline in `GitSyncService` | Property-testable once for 4 platforms (CLAUDE.md) |
| Violations and modes | Sealed types and value classes | type-driven-design | Booleans, raw strings | Exhaustive `when`; `LfsOid` cannot be confused with a git SHA |
| `.gitattributes` matcher | Pure Kotlin plus differential oracle | Test oracle | Use JGit `TreeWalk.getAttributes()` in production | Web has no JGit; one matcher of record |
| Settings storage | Per-graph `Settings` keys | existing `FirstSyncConfirmation` | New `git_config` columns | Avoids `.sq` edit, `MigrationRunner.all` entry and regenerated SQLDelight sources (F section 3) |
| Web collaborators | Dedicated `WasmLfs*` classes | existing wasm platform-layer pattern (memory: new capabilities are collaborators, not new fields) | More methods in `WasmGitWriteService` | That file is 1,222 lines |
| Hold-back | Stage then un-stage held paths | - | Refuse whole commit | Text notes keep syncing (ADR-003 section 3) |

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|---|---|---|---|
| `{C}/git/GitSyncService.kt` (778 lines) | Staging concerns spread over `sync()`, `commitLocalChanges`, `resolveConflicts`; conflict-marker guard missing from `commitLocalChanges` (`:580`) | Isolate via seam | New `LfsGuardCoordinator` is the only new logic; service edits are call sites. Marker parity in `commitLocalChanges` fixed in Task E1.4d |
| `{W}/git/WasmGitWriteService.kt` (1,222 lines) | God-class; `fetchRawUrl` decodes text (`:375`); `createBlob` (`:457`) has no path context | Isolate via seam | `WasmLfs*` collaborators; edits are three call sites plus a `fetchRemoteFileBytes` sibling |
| `{A}/git/GitShadowWorktree.kt` (483 lines) | SAF mirror is `String` based (`readSafFile: suspend (String) -> String?` at `:196`, `writeText` at `:245`, `readText` at `:126`) | Refactor-first | Binary-safe path (Story E2.5) must exist before Android LFS is declared supported (P R2) |
| `JvmGitRepository.kt` (486) and `AndroidGitRepository.kt` (587) | Near-duplicate clone, fetch, merge, push sequences | Isolate via seam | Shared `LfsTransferService` in `{JC}`; each adapter only calls it |
| `kmp/src/commonMain/.../platform/FileSystem.kt:65` | `getFileSize` default reads the whole file; only Android overrides it (`androidMain/.../platform/PlatformFileSystem.kt:750`) | Refactor-first | JVM and wasm overrides added in Task E3.2a so sniffing never loads a big binary |

## Migration Plan

No SQL schema change: LFS settings are `Settings` keys (`git_lfs_*_<graphId>`), so no `.sq` edit, no `MigrationRunner.all` entry, no regenerated SQLDelight sources. Data migration: none; absence of every key means defaults. Reversibility: delete the keys. Rollback: revert the PR; `.git/lfs/objects` left on disk is inert and git-lfs CLI compatible (same layout).

## Observability Plan
- **Logs** (`Logger("Lfs...")`, secrets passed through `SecretRedaction`): guard verdict per sync (counts, never file contents), transfer start and end per batch (object count, bytes, host, never the token or signed hrefs), probe result per host, resolver mode chosen.
- **Metrics**: `lfs.guard.held` (count per sync), `lfs.download.bytes`, `lfs.upload.bytes`, `lfs.batch.latency_ms`, `lfs.resolve.latency_ms` (web), `lfs.write.mode` (which of Direct, Proxy, Converter, ReadOnly). Emitted through the existing `SpanEmitter` where one is wired; otherwise log lines.
- **Alerts**: no new alerts (client app). Debug-log summary line `lfs: mode=<m> held=<n> up=<bytes> down=<bytes>` per sync for support.

## Risk Control
- **Feature flags**: `lfs_mode` per graph (default `AUTO` on JVM, `GUARD_ONLY` on Android until `androidLfsEnabled` is true, `GUARD_ONLY` on web and iOS); `androidLfsEnabled` default false until the Android device pass; `lfsConverterEnabled` default false and absent unless owner decision O2 is YES; `lfsProxyUrl` default empty.
- **Rollback**: the guard is independent of LFS transfer: setting `lfs_mode=GUARD_ONLY` disables all transfer code and keeps the safety floor. Full revert: PR revert.
- **Staged rollout**: Phase 1 (guard) merges and releases alone. JVM transfer next, Android behind its flag, web read, web write modes in order.

## Unresolved Questions
- [x] O1: RESOLVED 2026-10-11 as NO PROXY AT ALL (was: user-owned proxy only, template only, SteleKit hosts nothing) (decided in the brief; owner acknowledges the token-exposure copy) - blocks Story E4.3b - owner: Tyler Stapler
- [x] O2: RESOLVED 2026-10-11 as YES, opt-in advanced mode (was: is the staging-ref exception allowed (strategy iii converter)?) - blocks Stories E4.4a to E4.4f only - owner: Tyler Stapler
- [ ] O3: defaults for auto-download and caps (proposal: desktop `ALL`; Android and web `ON_DEMAND`; 25 MiB per object and 200 MiB per sync on mobile; web cap 50 MiB) - blocks Task E2.4a defaults only (settings are user-changeable) - owner: Tyler Stapler
- [ ] O4: confirm iOS stance (guard plus read-only placeholders, native LFS deferred until iOS git ships) - blocks nothing; Epic E5 is written to this stance - owner: Tyler Stapler
- [ ] O5: provide a throwaway private LFS repo plus PAT on each of github.com, gitlab.com, Bitbucket (and Azure DevOps if available) for the real-host validation - blocks Story E7.6, and the "Accepted" status of ADR-002 - owner: Tyler Stapler
- [ ] O6: veto point on hold-back versus refuse-whole-commit (ADR-003 section 3 chooses hold-back) - blocks Task E1.4d only if vetoed - owner: Tyler Stapler
- [ ] O7: PR #413 (demo-help-feature-registry) merge ordering - blocks Task E6.4b - owner: Tyler Stapler
- [ ] Q1: private-repo behaviour of `media.githubusercontent.com` with a PAT (W section 5; requirements Open Question 3) - blocks the "private" row of Story E3.1 acceptance, resolved by Story E7.6 - owner: Tyler Stapler (needs a private LFS repo)
- [ ] Q2: exact GitHub/GitLab quota-exceeded response bodies (F section 1 GAP) - blocks the quota substring match in Task E2.2d; until captured, classification is by status code (403/507/509) and a generic message - owner: captured in Story E7.6
- [ ] Q3: object-storage CORS for action hrefs on Bitbucket, Azure DevOps, GitHub, GitLab (W section 1 UNVERIFIED) - blocks the `Direct` row for those hosts being listed as "works" in user docs - owner: Story E7.6
- [ ] Q5: whether JGit runs a `git lfs install` `pre-push` script hook alongside our upload and whether that breaks the push (P R20) - blocks Task E2.3g acceptance and ADR-001 Accepted - owner: spike S5 (Task E2.1g)
- [ ] Q6: bandwidth ledger default budget (Task E2.4i) and the pixel budget (Task E3.4d) are proposals (1 GiB per sync, 50 megapixels) - blocks nothing, user-changeable - owner: Tyler Stapler
- [ ] Q4: whether `com.sun.net.httpserver` runs under Robolectric (P section 1.5) - blocks only the choice in Task E2.5e; the task uses Ktor `MockEngine` in androidUnitTest regardless - owner: implementer of E2.5e

## Dependency Visualization

```
Phase 1  SAFETY FLOOR (all platforms, ships first)
  E1.1 LfsPointer ----------+
  E1.2 gitattributes matcher+--> E1.4 LfsGuard + GitSyncService wiring --+--> E1.5 JVM/Android index verifier
  E1.3 DomainError variants +                                            +--> E1.6 web guard (wasm)
                                                                         +--> E1.7 pointer-file protection
                                                                         +--> E1.8 held-files UI
  GATE G1: GitSyncServiceLfsGuardTest, LfsRawBinaryNeverCommittedTest, WasmGitWriteServiceLfsGuardTest green

Phase 2  JVM / ANDROID
  E7.1 FakeLfsServer --> E7.2 LFS bare-origin fixtures --+
  E2.1 SPIKES S1..S5 (Gradle dep first) --GO/NO-GO--> E2.2 commonMain client (needs E7.1 for tests)
                                                          |
                          E2.3 JVM adapter (store, scanner, transfer, wiring) --> E2.4 settings/first-sync
                                                          |                              |
                          E2.8 Bazel pin + graduate spikes <-- (after GO)               |
                                                          v                              v
                          E2.5 Android (byte-safe shadow --> storage guard --> wiring, flag off) --> [Android device pass = BLOCKER]
                          E2.6 pointer conflicts      E2.7 watcher suppression
  GATE G2: LfsEndToEndTest (E7.3) green on JVM; spikes recorded in ADR-001

Phase 3  WEB
  E2.2 client + E1.6 ---> E3.1 read resolver + OPFS cache --> E3.2 Coil fetcher --> E3.3/E3.4 placeholders and decode budget (tests in each story)
                      \-> E4.1 CORS probe --> E4.2 direct write --+
                          E4.3 proxy (client, health, template) --+--> E4.5 capability resolver --> E4.6 settings/UX
                          E4.4 converter [BLOCKED on O2] ---------+--> E4.7 live test (needs O5)
  GATE G3: web mocked tests in ci.yml allow-list green; real-host checklist (E7.6) done

Phase 4  iOS + DOCS
  E5.1 iOS guard/placeholder    E6.1..E6.4 docs, site, demo, registry (E6.4b waits on PR #413)

Phase 5  CLOSE-OUT
  E7.3 e2e + git-lfs interop --> E7.4 registration audit --> E7.5 Android device pass, E7.6 real-host validation (both wall-clock blockers)
```

---

## Phase 1: Safety floor (all platforms, ships first)

### Epic E1: commonMain LFS model, attribute matcher, guard, wiring

**Goal**: On every platform, SteleKit never commits or uploads a raw binary for a path whose `.gitattributes` says `filter=lfs`, regression-tested (requirements success metric 1; ADR-003). Nothing in this epic transfers LFS objects.

#### Story E1.1: Spec-conformant LfsPointer model, parser and serialiser
**As a** sync engine on any platform, **I want** one spec-strict pointer parser and serialiser, **so that** every "is this a pointer" decision agrees with the git-lfs spec rather than JGit's lenient reader (P section 1.0).
**Acceptance Criteria**:
- A canonical pointer round-trips byte for byte.
  - *Given* `LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393")` and size 12345, *When* `LfsPointerCodec.serialize` then `parse` run, *Then* the result is `Valid` with the same oid and size and the bytes equal `version https://git-lfs.github.com/spec/v1\noid sha256:4d7a...2393\nsize 12345\n`.
- A pointer rewritten to CRLF is `Corrupt`, not `NotAPointer`.
  - *Given* those bytes with every `\n` replaced by `\r\n`, *When* parsed, *Then* the result is `Corrupt("CRLF")`.
- Plain text that does not start with the version line is `NotAPointer`.
  - *Given* the bytes of `# My notes\n`, *When* parsed, *Then* the result is `NotAPointer`.
- Sniffing never needs the file body for large files.
  - *Given* a file size of 5,000,000, *When* `LfsPointerSniffer.looksLikePointer(size, head)` runs, *Then* it returns false without reading the head.
**Files**: `{C}/git/lfs/LfsPointer.kt`, `{CT}/git/lfs/LfsPointerTest.kt`, `{CT}/git/lfs/LfsPointerPropertyTest.kt`, `{CT}/git/lfs/LfsArbs.kt`

##### Task E1.1a: LfsPointer, LfsOid, LfsPointerParse, LfsPointerCodec, LfsPointerSniffer [M]
- Implement `LfsOid` (value class, validates 64 lowercase hex), `LfsPointer(oid, size, extensions: List<Pair<String,String>>)`, sealed `LfsPointerParse`, `LfsPointerCodec.parse(bytes)` and `serialize(pointer)`, `LfsPointerSniffer.looksLikePointer(size: Long, head: ByteArray?)`.
- Spec rules: UTF-8, `key value\n` lines, keys `[a-z0-9.-]`, `version` first, remaining keys sorted (`ext-N-name` lines sort before `oid`), no CR, no BOM, no NUL, total under 1024 bytes, trailing newline, `oid` method `sha256` only, `size` a non-negative Long. Read accepts legacy `https://hawser.github.com/spec/v1` and `http://git-media.io/v/2` (INFERRED from git-lfs `pointer.go`; the test labels it so); write always emits the canonical URL. Classification: first line starts with the version prefix then any violation gives `Corrupt(reason)`; otherwise `NotAPointer`.
- Files: `{C}/git/lfs/LfsPointer.kt`.
- Tests: written in E1.1b.

##### Task E1.1b: Pointer example and property tests plus shared Arbs [S]
- `LfsPointerTest` (examples above, legacy version accepted, extension line order, size 0 and 19-digit sizes). `LfsPointerPropertyTest` (`kotest-property` inside `runTest { checkAll(...) }` per CLAUDE.md): `roundTripForAnyOidAndSize`, `canonicalBytesAreStableUnderReserialise`, `everyRejectionCaseIsTypedNeverThrows` (missing keys, version not first, wrong-case URL, uppercase or 63 or 65 char or non-hex oid, `sha1:`, negative or overflowing size, duplicate keys, unsorted keys, missing trailing newline, key with `_` or uppercase, value with `\r`, 1024 bytes or more, BOM, NUL), `crlfRewriteIsCorruptNotNotAPointer`. `LfsArbs.kt` holds `arbOid`, `arbSize`, `arbPointerBytes` reused by later tests.
- Register: `{BT}/AllBusinessTests.kt` imports and `@Suite.SuiteClasses` entries for both classes; add the three `{CT}` files to `business_common_tests.srcs` in `kmp/src/commonTest/kotlin/BUILD.bazel`.
- Files: `{CT}/git/lfs/LfsPointerTest.kt`, `{CT}/git/lfs/LfsPointerPropertyTest.kt`, `{CT}/git/lfs/LfsArbs.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

#### Story E1.2: Pure-Kotlin .gitattributes matcher and LFS path policy
**As a** guard on a platform with no JGit (web), **I want** a `.gitattributes` evaluator that equals git's, **so that** "is this path LFS" has one answer everywhere (P section 1.6).
**Acceptance Criteria**:
- Last matching line wins per attribute; deeper files override shallower.
  - *Given* root `.gitattributes` `*.png filter=lfs` and `assets/raw/.gitattributes` `*.png -filter`, *When* `LfsPathPolicy.isTracked("assets/raw/a.png")` and `isTracked("assets/b.png")` run, *Then* the first is false and the second is true.
- A pattern without a slash matches the basename at any depth; with a slash it is anchored.
  - *Given* `*.pdf filter=lfs` and `/docs/*.pdf filter=lfs`, *When* `isTracked("a/b/c.pdf")` and `isTracked("docs/x.pdf")` run, *Then* both are true; `isTracked("a/docs/x.pdf")` still true via the first rule only.
- `.gitattributes` is never tracked.
  - *Given* `* filter=lfs`, *When* `isTracked(".gitattributes")` runs, *Then* it is false.
- The matcher equals git.
  - *Given* 200 generated attribute files and 20 paths each, *When* compared with `git check-attr filter` and JGit `TreeWalk.getAttributes()`, *Then* all answers agree, with every divergence documented in the test.
**Files**: `{C}/git/lfs/GitAttributesModels.kt`, `{C}/git/lfs/GitAttributesParser.kt`, `{C}/git/lfs/GitAttributesMatcher.kt`, `{C}/git/lfs/LfsPathPolicy.kt`, `{C}/git/lfs/LfsRulesLoader.kt`

##### Task E1.2a: Attribute models and parser [M]
- `AttrState`, `AttrRule`, `GitAttributesFile(dirPrefix, rules)`. Parse comments, blank lines, quoted patterns with C-style escapes, `[attr]name ...` macros; built-in `binary` expands to `-diff -merge -text` only in the root file, `info/attributes` and global layers (not in subdirectories).
- Files: `{C}/git/lfs/GitAttributesModels.kt`, `{C}/git/lfs/GitAttributesParser.kt`.

##### Task E1.2b: Wildmatch and layered resolution [M]
- Implement gitignore-style wildmatch (`*`, `?`, `[...]`, `**` forms `**/x`, `x/**`, `a/**/b`), basename rule for slash-free patterns, anchoring to the file's directory otherwise, trailing-slash patterns never match, patterns do not recurse into directories, optional `ignoreCase`. `AttributeRuleSet.resolve(path, attr)`: `.git/info/attributes` over deepest directory over root, last line wins per attribute.
- Files: `{C}/git/lfs/GitAttributesMatcher.kt`.

##### Task E1.2c: LfsPathPolicy and LfsRulesLoader [S]
- `LfsPathPolicy(rules).isTracked(path)`; `.gitattributes` basename exempt. `LfsRulesLoader(fileSystem, repoRoot)` reads `.gitattributes` for each ancestor directory of the given changed paths plus `.git/info/attributes` through `FileSystem.readFile`; returns `AttributeRuleSet`. A `RemoteRulesSource` interface lets web supply rules from the network (Task E1.6a).
- `LfsRulesLoader` takes `ignoreCase` (from `core.ignorecase`, Task E2.9c).
- Files: `{C}/git/lfs/LfsPathPolicy.kt`, `{C}/git/lfs/LfsRulesLoader.kt`.

##### Task E1.2d: Matcher unit and property tests [S]
- `GitAttributesMatcherTest` (hand-labelled table incl. every acceptance case above, `-filter`, `!filter`, `filter=other`, macros, case flipping under `ignoreCase`). `GitAttributesMatcherPropertyTest`: `unrelatedLineNeverChangesResult`, `reorderingUnrelatedLinesIsNoOp`, `rootedAndRecursiveGlobEquivalence`, `laterLineWins`, `deeperFileWins`, `matchesNaiveReferenceOracle` (a deliberately simple second implementation inside the test). `LfsPathPolicyTest` (`gitattributesFileNeverTracked`, `nestedOverrideUnsets`).
- Register the three classes in `{BT}/AllBusinessTests.kt`; add the files to `business_common_tests.srcs`.
- Files: `{CT}/git/lfs/GitAttributesMatcherTest.kt`, `{CT}/git/lfs/GitAttributesMatcherPropertyTest.kt`, `{CT}/git/lfs/LfsPathPolicyTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E1.2e: Differential test against JGit and git check-attr [M]
- `GitAttributesDifferentialTest` in businessTest: build temp repos with generated nested `.gitattributes`, compare `LfsPathPolicy` with JGit `TreeWalk.getAttributes()` (always) and `git check-attr filter` (`Assume` git on PATH). Document divergences in assertions' messages. Also, when `git lfs` is on PATH (`Assume`), verify that `git lfs clean` of a zero-byte file yields an empty blob, confirming the ADR-003 empty-file exemption; otherwise the exemption is reported as unconfirmed in the test output.
- CI must not skip it (pre-mortem N8): `.github/workflows/ci.yml` gets a step installing `git` and `git-lfs` before the business tests, and the env var `LFS_DIFFERENTIAL_REQUIRED=true` turns the `Assume` into an assertion; a corpus of real-world attribute files under `kmp/src/businessTest/resources/lfs/attributes-corpus/` is run through the comparison. Local runs without the binaries still skip.
- Register in `{BT}/AllBusinessTests.kt`.
- Files: `{BT}/git/lfs/GitAttributesDifferentialTest.kt`, `{BT}/AllBusinessTests.kt`, `.github/workflows/ci.yml`, `kmp/src/businessTest/resources/lfs/attributes-corpus/`.

##### Task E1.2f: LfsRulesLoader tests [S]
- `LfsRulesLoaderTest` (businessTest, `StubFileSystem`): reads each ancestor directory's `.gitattributes`, `.git/info/attributes` precedence, missing files, `core.ignorecase` passthrough, working-tree edit visible on the next call (no caching across calls, P R16). Register.
- Files: `{BT}/git/lfs/LfsRulesLoaderTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E1.3: New DomainError.GitError variants and both message maps
**As a** user, **I want** LFS failures to read as plain language, **so that** I know what to do (F section 1 table, F section 3 messages).
**Acceptance Criteria**:
- Every new variant has an entry in both exhaustive maps.
  - *Given* `DomainError.GitError.LfsRawBinaryRefused(listOf("assets/photo.png"))`, *When* `toSyncErrorMessage()` and `toUiMessage()` run, *Then* both return "Refusing to commit assets/photo.png: it must be stored with Git LFS." and the project compiles with no non-exhaustive `when`.
- Quota and missing-object wording matches the proposal.
  - *Given* `LfsQuotaExceeded(null)`, *When* mapped, *Then* the text starts "Your Git host's LFS quota is used up".
**Files**: `{C}/error/DomainError.kt`, `{CT}/error/DomainErrorTest.kt`

##### Task E1.3a: Add variants and map entries [S]
- Add to `DomainError.GitError` (`{C}/error/DomainError.kt:73-162`): `LfsRawBinaryRefused(paths: List<String>)`, `LfsPointerCorrupt(path: String)`, `LfsAuthFailed(readOnly: Boolean)`, `LfsQuotaExceeded(detail: String?)`, `LfsObjectMissing(oid: String)`, `LfsObjectIntegrityFailed(oid: String)`, `LfsNotSupportedHere(reason: String)`, `LfsProxyUnavailable(detail: String)`, `LfsStorageInsufficient(neededBytes: Long, availableBytes: Long)`. Add entries to `toUiMessage` (`:276`) and `toSyncErrorMessage` (`:354`), and extend the third exhaustive `when` at `{CT}/error/DomainErrorTest.kt:122`. Texts from F section 3 table. `grep -rn "is DomainError.GitError.FileTooLarge" kmp/src` to find any other exhaustive `when` and update it.
- Files: `{C}/error/DomainError.kt`, `{CT}/error/DomainErrorTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.
- Tests: `DomainErrorTest` (existing, extended). It is NOT registered anywhere today (`grep -rn DomainErrorTest kmp/src` finds only the class): this task adds it to `business_common_tests.srcs` and to `{BT}/AllBusinessTests.kt`, a first-time registration of an existing class.

#### Story E1.4: LfsGuard and GitSyncService wiring with hold-back
**As a** user syncing a graph whose `assets/` is LFS-tracked, **I want** a pasted image never committed raw while my text notes keep syncing, **so that** the remote repo is not corrupted (P R3).
**Acceptance Criteria**:
- A raw PNG at a tracked path is held, other files commit.
  - *Given* `GitStatus(modifiedFiles=["pages/a.md","assets/photo.png"])`, `.gitattributes` `assets/** filter=lfs`, and `assets/photo.png` holding PNG bytes, *When* `GitSyncService.sync(graphId)` runs, *Then* `stageSubdir` is called with `excludePaths={"assets/photo.png"}`, commit and push proceed, and `syncState` ends as `LfsActionRequired(held=[assets/photo.png])`.
- A valid pointer at a tracked path passes.
  - *Given* `assets/photo.png` holding a canonical 3 MB pointer, *When* `sync` runs, *Then* nothing is held.
- Every staging entry point is guarded.
  - *Given* the same raw file, *When* `commitLocalChanges(graphId)` runs, and when `resolveConflicts(...)` marks it resolved, *Then* it is excluded or refused in both, and `StubGitRepository.commit` is never called with it staged.
- Guard input depends on whether the clean filter is active (ADR-003 section 2a).
  - *Given* `lfs_mode=AUTO` on JVM with the clean filter configured and a raw 3 MB `assets/b.bin` in the working tree, *When* `sync` runs, *Then* the working-tree raw check is skipped for it, `stageSubdir` converts it, `stagedLfsViolations` returns empty because the index blob is a pointer, and it is pushed as a pointer (never held).
  - *Given* `lfs_mode=GUARD_ONLY` (or AUTO on a platform whose flag is off), *When* the same file is changed, *Then* it is held from the working tree.
  - *Given* AUTO and a clean filter that did not run so the index holds raw bytes, *When* `stagedLfsViolations` runs, *Then* the path is reported, un-staged and held.
- `.gitattributes` evaluated from working tree plus index, not HEAD.
  - *Given* `.gitattributes` modified but uncommitted to add `*.png filter=lfs`, *When* a raw `x.png` is modified, *Then* it is held.
**Files**: `{C}/git/lfs/LfsGuard.kt`, `{C}/git/lfs/LfsGuardCoordinator.kt`, `{C}/git/GitSyncService.kt`, `{C}/git/GitRepository.kt`, `{C}/git/model/SyncState.kt`

##### Task E1.4a: LfsGuard, LfsContentProbe, LfsViolation, LfsGuardVerdict [M]
- Pure `LfsGuard.evaluate(changedPaths, policy, probe, staging: StagingRefScope? = null)`: path tracked and not (valid pointer or empty) gives a violation; reads only `probe.size` and `probe.readHead(path, 1024)`; any size of 1024 or more at a tracked path is `RawBinary` without reading; `Corrupt` pointer gives `CorruptPointer`; pointer content containing conflict markers gives `ConflictMarkers`.
- Files: `{C}/git/lfs/LfsGuard.kt`, `{C}/git/lfs/LfsContentProbe.kt`.

##### Task E1.4b: Guard unit and property tests [S]
- `LfsGuardTest` (acceptance cases, empty file allowed, `.gitattributes` exempt, nested override). `LfsGuardPropertyTest`: `allowedIffPointerOrEmptyAtTrackedPath`, `untrackedPathNeverHeld`, `bomAndCrlfPointersAreHeld`, `over1024BytesIsHeld`, `versionPrefixTextFileIsHeld`, `neverReadsMoreThan1024Bytes` (probe counts bytes). Register both in `{BT}/AllBusinessTests.kt` and `business_common_tests.srcs`.
- Files: `{CT}/git/lfs/LfsGuardTest.kt`, `{CT}/git/lfs/LfsGuardPropertyTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E1.4c: stageSubdir excludePaths and stagedLfsViolations on the GitRepository interface [S]
- Add `suspend fun stagedLfsViolations(config: GitConfig): List<LfsViolation> = emptyList()` (JVM and Android implement it in E1.5a and E1.5b via `LfsIndexVerifier`, which also un-stages what it reports; default empty for iOS and wasm). Also `val cleanFilterActive: Boolean get() = false` on the interface (JVM and Android return true only after `JgitLfsSetup` verified the filter config for that repo, Task E2.3a). Change `suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String> = emptySet())` in `{C}/git/GitRepository.kt:68`; update `StubGitRepository` (record the argument), `IosGitRepository` (still `NotSupported`) and `WasmGitRepository` (dirty-set path ignores it; hold-back happens in E1.6b). JVM and Android implementations land in E1.5a and E1.5b.
- Files: `{C}/git/GitRepository.kt`, `{CT}/git/testsupport/StubGitRepository.kt`, `{I}/git/IosGitRepository.kt`, `{W}/git/WasmGitRepository.kt`.

##### Task E1.4d: LfsGuardCoordinator, SyncState.LfsActionRequired and call sites [M]
- Web decision (review C2): on wasm the service-level coordinator runs with input `Delegated`, which returns an empty verdict and ignores `excludePaths`; `WasmGitWriteService` (Task E1.6b) is the sole web guard, its rules come from the remote at the base sha (Task E1.6a, one rule source), and it decides held versus converted (Direct and Proxy convert; ReadOnly holds). `LfsGuardCoordinator(fileSystem, configRepoRoot, input: LfsGuardInput)`: the effective mode comes from `LfsModeResolver` (Task E1.4f); `LfsGuardInput` is `WorkingTree` (GUARD_ONLY, OFF, iOS, or any repo where `cleanFilterActive` is false: judge the working file; Android with `androidLfsEnabled` false resolves to `GUARD_ONLY` first, so the "AUTO with the flag off" combination cannot occur) or `PostCleanIndex` (AUTO with `cleanFilterActive` true, meaning a CONVERTING filter (builtin or `jgit://stelekit/lfs/{clean,smudge}`) read back from config; the `GUARD_ONLY` passthrough filter never sets it: skip the working-tree raw check, still check pointer files for conflict markers, and after `stageSubdir` treat `gitRepository.stagedLfsViolations(config)` as authoritative). The input is chosen from `LfsMode` plus the repository-reported `cleanFilterActive`, never from settings alone. Builds `LfsRulesLoader` rules for the changed paths, runs `LfsGuard`, returns `LfsGuardVerdict`. Add `SyncState.LfsActionRequired(held: List<LfsViolation>)` (`{C}/git/model/SyncState.kt`). In `GitSyncService`: call right after the conflict-marker guard in step 5 (`:321-331`) and pass `excludePaths` to `stageSubdir`; in `commitLocalChanges` before `stageSubdir` (`:580`) (also add the existing `findFilesWithConflictMarkers` check there, the parity gap); in `resolveConflicts` before each `markResolved` (`:618`, `:641`, `:692`). Return a non-fatal result; the held list is exposed on `syncState`.
- Files: `{C}/git/lfs/LfsGuardCoordinator.kt`, `{C}/git/GitSyncService.kt`, `{C}/git/model/SyncState.kt`.

##### Task E1.4e: Service-level guard tests per entry point [M]
- `GitSyncServiceLfsGuardTest` using `StubGitRepository` and `StubFileSystem` like `GitSyncServiceConflictMarkerGuardTest` and `GitSyncServiceFirstSyncGateTest` (existing): per-mode cases `autoModeCleanFilterActiveNeverHoldsWorkingFile`, `autoModeRawInIndexIsHeldAndUnstaged`, `guardOnlyModeHoldsWorkingFile`; also cases = sync raw held and text commits, pointer passes, empty passes, `commitLocalChanges`, `resolveConflicts` each of the three `markResolved` sites, nested `.gitattributes`, uncommitted `.gitattributes` edit, `.gitattributes` fetch irrelevant on JVM. Register in `{BT}/AllBusinessTests.kt`.
- Files: `{BT}/git/GitSyncServiceLfsGuardTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E1.4f: LfsMode, platform capabilities and LfsModeResolver (Phase 1 owner of the type) [S]
- `LfsMode` enum `OFF`, `GUARD_ONLY`, `AUTO`; `LfsModeSource` reads `git_lfs_mode_<graphId>` from `Settings` (default `GUARD_ONLY`); `LfsPlatformCapabilities(transferSupported, androidLfsEnabled)` provided per platform (JVM transfer true; Android `androidLfsEnabled=false` until Task E7.5a; iOS and web false); `LfsModeResolver.effective(stored, capabilities)` returns `GUARD_ONLY` whenever the platform flag is false, regardless of the stored value. `GitSyncService` and `LfsGuardCoordinator` read modes only through the resolver. Tests `LfsModeResolverTest` (moved here from E2.9b) and `LfsModeRollbackTest` (`sync_should_skipAllTransferAndKeepGuard_When_modeGuardOnly`, defaults per platform). Register both; the commonTest file goes into `business_common_tests.srcs`.
- Files: `{C}/git/lfs/LfsMode.kt`, `{C}/git/lfs/LfsModeResolver.kt`, `{CT}/git/lfs/LfsModeResolverTest.kt`, `{BT}/git/lfs/LfsModeRollbackTest.kt`, `{BT}/AllBusinessTests.kt` (plus `kmp/src/commonTest/kotlin/BUILD.bazel` in Task E7.4a).

##### Task E1.4g: Coordinator and structural call-site tests [M]
- `LfsGuardCoordinatorTest` (businessTest): per-input behaviour (`WorkingTree`, `PostCleanIndex`, `Delegated`), rule loading, hold-back set. `LfsStagingCallSitesStructuralTest` (pre-mortem N3): reads the sources of `GitSyncService.kt`, `JvmGitRepository.kt`, `AndroidGitRepository.kt` and `WasmGitWriteService.kt` (bundled as classpath resources the way `git_shadow_worktree_as_resource` is bundled for `GitShadowWorktreeNoCoroutineScopeTest`, with a Gradle path fallback) and fails if any call to `stageSubdir`, `markResolved`, `git.add(` or `createBlob(` appears outside an allow-listed method that is preceded by an `LfsGuardCoordinator`, `LfsIndexVerifier` or `LfsGuard` call, so a new staging caller fails the build. Register both.
- Files: `{BT}/git/lfs/LfsGuardCoordinatorTest.kt`, `{BT}/git/lfs/LfsStagingCallSitesStructuralTest.kt`, `kmp/src/businessTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

#### Story E1.5: JVM and Android repository backstop and hold-back implementation
**As a** maintainer, **I want** the repository adapters to refuse a raw tracked blob even if a caller skips the service guard, **so that** a missed entry point cannot corrupt history (P R3: the conflict-marker fix `5d00781169` showed an entry point was missed once).
**Acceptance Criteria**:
- Held paths are un-staged after the pattern add.
  - *Given* a real JGit repo with `assets/** filter=lfs`, raw `assets/a.bin` and `pages/a.md` modified, *When* `JvmGitRepository.stageSubdir(config, setOf("assets/a.bin"))` runs, *Then* the index contains `pages/a.md` only.
- Commit refuses a raw tracked blob that reaches the index.
  - *Given* `assets/a.bin` staged raw by a direct `git.add()`, *When* `JvmGitRepository.commit` runs, *Then* it returns `LfsRawBinaryRefused(["assets/a.bin"])`, no commit exists, and the entry is un-staged.
**Files**: `{JC}/git/lfs/LfsIndexVerifier.kt`, `{J}/git/JvmGitRepository.kt`, `{A}/git/AndroidGitRepository.kt`

##### Task E1.5a: LfsIndexVerifier and JVM wiring [M]
- `LfsIndexVerifier.verify(repo): List<LfsViolation>`: iterate `DirCache` entries; rules from the index version of `.gitattributes` blobs (staged) falling back to the working tree; for tracked paths read at most 1024 bytes of the blob via `ObjectReader` and apply `LfsPointerCodec`. Wire into `JvmGitRepository.commit` (`:249-259`, before `git.commit()`), implement `stageSubdir(excludePaths)` after the existing `add` calls (`:243`, `:245`) with `git.reset().addPath(p)` per held path, and verify after `markResolved` staging (`:430`).
- Also override `stagedLfsViolations` (verify then un-stage) and `cleanFilterActive` (false until Task E2.3a sets it). Files: `{JC}/git/lfs/LfsIndexVerifier.kt`, `{J}/git/JvmGitRepository.kt`.

##### Task E1.5b: Android parity [M]
- Same three changes in `AndroidGitRepository.kt` (`stageSubdir` `:256`, `:258`; `commit` `:266`; `markResolved` `:495`), operating on the shadow worktree JGit repo. Hold-back paths are git-relative.
- Files: `{A}/git/AndroidGitRepository.kt`.

##### Task E1.5c: Real-JGit safety-floor tests [M]
- `LfsIndexVerifierTest` (real JGit temp repo: raw blob staged is reported, pointer passes, empty passes, nested attributes, un-staging). `LfsRawBinaryNeverCommittedTest`: with LFS disabled (`lfs_mode=GUARD_ONLY`), a 3 MB `assets/a.bin` is never committed by `stageSubdir`+`commit`, and a direct `git add` plus `commit` is refused; asserts by walking every blob of the resulting repo. Register both in `{BT}/AllBusinessTests.kt`.
- Files: `{BT}/git/lfs/LfsIndexVerifierTest.kt`, `{BT}/git/lfs/LfsRawBinaryNeverCommittedTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E1.5d: Android guard test and JVM generated-input property [M]
- `AndroidGitRepositoryLfsGuardTest` (androidUnitTest, Robolectric shadow repo: a raw tracked blob staged directly is refused at commit and un-staged), registered in `{AT}/AllAndroidUnitTests.kt`. `LfsRawBinaryNeverCommittedPropertyTest` (businessTest): generated attribute files, paths and contents through `stageSubdir` plus `commit` on real JGit, asserting every blob at a tracked path in the result is a pointer or empty; registered.
- Files: `{AT}/git/AndroidGitRepositoryLfsGuardTest.kt`, `{BT}/git/lfs/LfsRawBinaryNeverCommittedPropertyTest.kt`, `{AT}/AllAndroidUnitTests.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E1.5e: Post-push audit [S]
- `LfsPostPushAudit` (jvmCommon, pre-mortem N4): after a successful `git.push()` scan the pushed range for blobs at tracked paths that are not pointers; log the count, set `LfsActionRequired` with reason `RawBinaryPushed` (the data is already remote, so this is detection, not prevention). Wire into `JvmGitRepository.doPush` and the Android push. Test `LfsPostPushAuditTest` (real JGit: a bypass commit made with a plain `git add` is reported). Register.
- Files: `{JC}/git/lfs/LfsPostPushAudit.kt`, `{J}/git/JvmGitRepository.kt`, `{A}/git/AndroidGitRepository.kt`, `{BT}/git/lfs/LfsPostPushAuditTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E1.6: Web guard on every write entry
**As a** web user attaching an image under a tracked folder, **I want** the browser never to upload it as a raw blob, **so that** the remote repo stays clean (W section 5 hooks 2 and 3).
**Acceptance Criteria**:
- No blob POST for a tracked path.
  - *Given* a `MockEngine` GitHub host, dirty entries `pages/a.md` (WRITE) and `assets/p.png` (WRITE, PNG bytes) and remote `.gitattributes` `assets/** filter=lfs`, *When* `commitGitHub` runs, *Then* the request log has a `POST .../git/blobs` for `pages/a.md` and none for `assets/p.png`, and `assets/p.png` remains dirty.
- GitLab commit actions exclude it.
  - *Given* the GitLab host, *When* `pushViaGitLab` runs, *Then* the `actions` array in `POST .../repository/commits` has no entry for `assets/p.png`.
- Fail closed when rules cannot be fetched.
  - *Given* the `.gitattributes` GET returns 503, *When* `commitGitHub` runs, *Then* it returns `NetworkFailure`, no blob is posted, and the dirty set is unchanged.
**Files**: `{W}/git/lfs/WasmLfsRulesSource.kt`, `{W}/git/lfs/WasmLfsContentProbe.kt`, `{W}/git/WasmGitWriteService.kt`, `{WT}/git/WasmGitWriteServiceLfsGuardTest.kt`, `.github/workflows/ci.yml`

##### Task E1.6a: WasmLfsRulesSource and WasmLfsContentProbe [M]
- `WasmLfsRulesSource` implements `RemoteRulesSource`: fetch root and nested `.gitattributes` (paths from the tree listing already fetched at `fetchGitLabTreePaths` / the GitHub tree call) at the base sha through `fetchRemoteFileContent` (`:346`); 404 means no rules; any other failure fails closed. `WasmLfsContentProbe` implements `LfsContentProbe` over `fileSystem.getContentBytes` (`PlatformFileSystem.kt:471`).
- Files: `{W}/git/lfs/WasmLfsRulesSource.kt`, `{W}/git/lfs/WasmLfsContentProbe.kt`, `{W}/git/WasmGitWriteService.kt` (expose internal accessor only).

##### Task E1.6b: Hold-back in commitGitHub, pushViaGitLab and createBlob [M]
- In `commitGitHub` (`:385`) filter `dirty` through `LfsGuard` before `createBlobsBounded` (`:436`); in `pushViaGitLab` (`:991`) before `buildGitLabActions`; `createBlob` (`:457`) gains a `path` parameter and a `stagingScope: StagingRefScope? = null` parameter and re-asserts (structural second check; with a null scope a tracked path whose content is not a pointer throws even if the dirty entry is flagged LFS; the converter in Task E4.4b passes a non-null scope only for the inbox ref). Held paths stay in the dirty set; surface `LfsActionRequired` through `WasmGitRepository`. `applyRemoteContent` never decodes a tracked path as text beyond pointer text (remote content for a tracked path is written as received).
- Files: `{W}/git/WasmGitWriteService.kt`, `{W}/git/WasmGitRepository.kt`.

##### Task E1.6c: Wasm guard tests and CI allow-list [M]
- `WasmGitWriteServiceLfsGuardTest` modelled on `WasmGitWriteServiceMockedIntegrationTest` (existing, real `WasmGitWriteService` + `MockEngine` + real OPFS dirty state): the three acceptance cases plus `createBlobNeverCalledForTrackedPath`, `createBlobTrackedPathNullScopeThrows`, and `everyBlobBodyAtTrackedPathIsPointerOrAbsent` (a property over 50 seeded generated dirty sets, run through the concurrent `createBlobsBounded` batching and `applyRemoteContent`, asserting from the `MockEngine` request log that no blob request body at a tracked path is non-pointer). Append `--tests "dev.stapler.stelekit.git.WasmGitWriteServiceLfsGuardTest"` to the allow-list in `.github/workflows/ci.yml` (the "Run wasmJs browser tests" step).
- `WasmLfsRulesSourceTest` (nested `.gitattributes` fetched from the tree listing, 404 means no rules, 503 fails closed) is added in the same task and appended to the allow-list.
- Files: `{WT}/git/WasmGitWriteServiceLfsGuardTest.kt`, `{WT}/git/WasmLfsRulesSourceTest.kt`, `.github/workflows/ci.yml`.

#### Story E1.7: Pointer files are never parsed or rewritten as pages
**As a** user with a pointer left in the working tree, **I want** the loader to skip it, **so that** a 130-byte pointer is never shown as a page or saved back as text (P R4).
**Acceptance Criteria**:
- *Given* `pages/Big.md` containing a canonical pointer, *When* `GraphLoader` loads the directory, *Then* no page `Big` is created, one warning is logged, and the file bytes are unchanged afterwards.
**Files**: `{C}/db/GraphLoader.kt`, `{BT}/db/GraphLoaderLfsPointerTest.kt`

##### Task E1.7a: GraphLoader skips pointer files [S]
- In `GraphLoader`'s file read path (`readFileDecrypted`, `{C}/db/GraphLoader.kt:114`, wired at `:352`), after reading, `LfsPointerCodec.parse(content.encodeToByteArray())` `Valid` or `Corrupt` means skip loading that file and log once; never throw. Test `GraphLoaderLfsPointerTest` (businessTest) registered in `{BT}/AllBusinessTests.kt`.
- Files: `{C}/db/GraphLoader.kt`, `{BT}/db/GraphLoaderLfsPointerTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E1.8: Held-files UI
**As a** user, **I want** to see which files were held back and why, **so that** I can fix them (ADR-003 section 8).
**Acceptance Criteria**:
- *Given* `SyncState.LfsActionRequired([RawBinary assets/photo.png])`, *When* the user taps the sync badge, *Then* a sheet lists `assets/photo.png` with the text "this repository stores that path with Git LFS and SteleKit cannot upload it here yet. The file was not committed." and the remedies.
**Files**: `{C}/ui/components/git/LfsHeldFilesSheet.kt`, `{C}/ui/components/SyncStatusBadge.kt`, `{AT}/ui/components/git/LfsHeldFilesSheetTest.kt`, `{AT}/AllAndroidUnitTests.kt`

##### Task E1.8a: LfsHeldFilesSheet and badge state [M]
- Composable `LfsHeldFilesSheet(held, onDismiss)`; `SyncStatusBadge` renders `LfsActionRequired` as a warning (not error) chip that opens the sheet. Robolectric `LfsHeldFilesSheetTest` (Compose behavior test belongs in androidUnitTest per CLAUDE.md) registered in `{AT}/AllAndroidUnitTests.kt`.
- Each held row shows the reason, the matching `.gitattributes` file and line (`LfsViolation` carries `ruleSource`; N7) and a copy-diagnostics action. A graph with no `filter=lfs` rule renders no LFS UI at all (N11); `LegacyRawPending` files appear in the review sheet and settings count only, never as a per-sync held nag (ADR-003 section 4). `LfsHeldFilesSheetTest` covers both.
- Files: `{C}/ui/components/git/LfsHeldFilesSheet.kt`, `{C}/ui/components/SyncStatusBadge.kt`, `{AT}/ui/components/git/LfsHeldFilesSheetTest.kt`, `{AT}/AllAndroidUnitTests.kt`.

**Phase 1 gate G1 (safety floor ships)**: `GitSyncServiceLfsGuardTest`, `LfsRawBinaryNeverCommittedTest`, `LfsIndexVerifierTest`, `WasmGitWriteServiceLfsGuardTest`, `GitAttributesDifferentialTest`, `LfsPointerPropertyTest`, `LfsGuardPropertyTest` green; `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests ...` or `bazel test //kmp:business_tests` run and output shown.

---

## Phase 2: JVM and Android full LFS

Prerequisite stories from Epic E7 (listed under E7 below): E7.1 FakeLfsServer and E7.2 LFS bare-origin fixtures run before any E2 story except E2.1a.

### Epic E2: JGit filters plus our own authenticated commonMain LFS client

**Goal**: Clone and pull materialise LFS content; commit and push of changed LFS files uploads objects and commits pointers, verified against the local fake server (requirements success metric 2; ADR-001, ADR-004).

#### Story E2.1: Spikes S1 to S5 with explicit go/no-go (runs first)
**As a** planner, **I want** the five unverified JGit and Android assumptions proven or refuted before building on them, **so that** ADR-001 is Accepted on evidence (S section "Open spikes").
**Acceptance Criteria**:
- S1: filter activation works with `useJGitBuiltin` unset.
  - *Given* a temp repo with `.gitattributes` `*.bin filter=lfs` and config `filter.lfs.clean=jgit://builtin/lfs/clean`, `filter.lfs.smudge=jgit://builtin/lfs/smudge`, *When* a 3 MB `a.bin` is added, committed, deleted and checked out again, *Then* the committed blob is a pointer, `.git/lfs/objects/<aa>/<bb>/<oid>` exists, the working file equals the original bytes, `status` is clean, and `FakeLfsServer` recorded zero requests.
- S2: `setNoCheckout` clone sequencing.
  - *Given* a bare origin holding pointers and a store pre-seeded with the objects, *When* `Git.cloneRepository().setNoCheckout(true).setDepth(50)` runs, pointers are found by `TreeWalk`, and `git.checkout()` is called, *Then* the working files hold real content with no network.
- S3: SSH `git-lfs-authenticate`.
  - *Given* an in-process MINA SSH server answering `git-lfs-authenticate repo download` with a JSON href and header, *When* the transport's session runs the command, *Then* the href and header are returned; if the MINA server classes are unavailable on the test classpath the spike records NO-GO and the fallback applies.
- S5: deferred-object checkout, pointer-in-worktree status and hooks under the builtin filters (ADR-001 section 3a).
  - *Given* a repo configured as in S1, a pointer whose object is NOT in `.git/lfs`, and `lfs.url` pointing at a `FakeLfsServer` that requires auth, *When* `git.checkout()` restores that path, *Then* GO requires zero requests reaching the server (or a refusal we intercept before any request) and the pointer text left in the working file; any unauthenticated request reaching the server is NO-GO. With the pointer text left in the working tree, `git.status()` must not report the path modified and `git add` must not produce a pointer to a pointer. A repo with a `git lfs install` `.git/hooks/pre-push` script must still push exactly once through our upload (outcome recorded; Q5).
- S4: Android byte corruption is demonstrated, then fixed in E2.5.
  - *Given* a SAF file containing bytes `0xFF 0xFE 0x00 0x80`, *When* `GitShadowWorktree.syncFromSafRoot` mirrors it, *Then* the spike test shows the shadow copy differs from the source (negative result recorded).
**Files**: `kmp/build.gradle.kts`, `{JT}/git/lfs/spike/LfsSpikeFilterActivationTest.kt`, `{JT}/git/lfs/spike/LfsSpikeCloneNoCheckoutTest.kt`, `{JT}/git/lfs/spike/LfsSpikeSshAuthenticateTest.kt`, `{JT}/git/lfs/spike/LfsSpikeDeferredObjectCheckoutTest.kt`, `{AT}/git/GitShadowWorktreeBinaryRoundTripSpikeTest.kt`, `project_plans/git-lfs-support/decisions/ADR-001-jgit-lfs-vs-alternatives.md`

##### Task E2.1a: Add the lfs artifact to Gradle only [XS]
- `implementation("org.eclipse.jgit:org.eclipse.jgit.lfs:7.3.0.202506031305-r")` next to `kmp/build.gradle.kts:175` (jvmMain) and `:342` (androidMain). Bazel waits for the GO (Task E2.8a) so a NO-GO causes no lockfile churn.
- Files: `kmp/build.gradle.kts`.

##### Task E2.1b: Spike S1 filter activation [M]
- `LfsSpikeFilterActivationTest` (jvmTest, not registered in `AllJvmTests` until graduated), parameterised over the builtin names and the custom `jgit://stelekit/lfs/*` names (implemented as a minimal spike-local `FilterCommand`): call `BuiltinLFS.register()`, write config, run the add, commit, delete, checkout, status sequence; assert zero requests on `FakeLfsServer` (Story E7.1). Output the result table to the test log.
- Files: `{JT}/git/lfs/spike/LfsSpikeFilterActivationTest.kt`.

##### Task E2.1c: Spike S2 clone sequencing [M]
- `LfsSpikeCloneNoCheckoutTest`: sequence in the acceptance criterion at shallow depth `DEFAULT_CLONE_DEPTH` (`GitConfig.kt`); also record whether `status` after the checkout is clean.
- Files: `{JT}/git/lfs/spike/LfsSpikeCloneNoCheckoutTest.kt`.

##### Task E2.1d: Spike S3 SSH git-lfs-authenticate [M]
- `LfsSpikeSshAuthenticateTest` using Apache MINA `SshServer` if its server classes resolve on the jvmTest classpath (they ship with `sshd-core`, a dependency of `org.eclipse.jgit.ssh.apache` at `kmp/build.gradle.kts:176`; INFERRED server classes are included, the spike confirms). If not resolvable, record NO-GO and stop. Real-host SSH confirmation is a wall-clock blocker (Story E7.6, optional row).
- Files: `{JT}/git/lfs/spike/LfsSpikeSshAuthenticateTest.kt`.

##### Task E2.1e: Spike S4 Android shadow bytes [S]
- Robolectric `GitShadowWorktreeBinaryRoundTripSpikeTest` constructing `GitShadowWorktree` like `GitShadowWorktreeTest` (existing) and mirroring a non-UTF-8 file; assert and record the corruption. Not registered in `AllAndroidUnitTests` until E2.5e replaces it with the passing `GitShadowWorktreeBinaryTest`.
- Files: `{AT}/git/GitShadowWorktreeBinaryRoundTripSpikeTest.kt`.

##### Task E2.1g: Spike S5 deferred objects, pointer in working tree, hooks [M]
- `LfsSpikeDeferredObjectCheckoutTest` (jvmTest, unregistered until graduated): the S5 scenarios with `FakeLfsServer` recording requests. If NO-GO, the baseline filters become our own `jgit://stelekit/lfs/{clean,smudge}` `FilterCommand`s registered in `FilterCommandRegistry`, which never touch the network, leave pointer input unchanged on clean (recognised with `LfsPointerCodec`), and leave pointer text in place on smudge when the object is absent, so caps, metered rules and the first-sync review cannot be bypassed. Rejected as sole fix: pre-seeding `.git/lfs` with placeholder files (breaks the verified-object invariant).
- Files: `{JT}/git/lfs/spike/LfsSpikeDeferredObjectCheckoutTest.kt`.

##### Task E2.1f: Go/no-go gate and ADR-001 update [S]
- Expected outcome (INFERRED from `research/stack.md:22,39`): S5 is NO-GO because stock smudge downloads unauthenticated, so our own `jgit://stelekit/lfs/*` filters are the likely baseline. S1 therefore also runs against the custom filter names (Task E2.1b). ADR-001 becomes Accepted in either case: with all of S1, S2, S5 GO on the builtin filters, or with the custom filters passing S1 and S2 equivalents (Accepted-with-fallback, decision item 2 rewritten to name the filters actually chosen). A spike failing for both filter families keeps ADR-001 Proposed and stops Phase 2.
- Run S1..S5, paste results (command and output) under "Spike results" in ADR-001, apply the fallback table for any NO-GO, flip ADR-001 to Accepted if S1, S2 and S5 are GO. If S1 or S5 is NO-GO the next tasks use `jgit://stelekit/lfs/*` filters (ADR-001 fallback); the task list is otherwise unchanged.
- Files: `project_plans/git-lfs-support/decisions/ADR-001-jgit-lfs-vs-alternatives.md`.

#### Story E2.2: commonMain LFS client (ADR-004)
**As a** sync engine on any platform, **I want** an authenticated LFS batch client with timeouts, retry, verify and resume, **so that** private repos and uploads to github.com and gitlab.com work (P R1).
**Acceptance Criteria**:
- Download verifies hash and size before the object becomes visible.
  - *Given* `FakeLfsServer` returning 4 MB whose SHA-256 differs from the pointer oid, *When* `LfsBatchClient.download` runs, *Then* it returns `LfsObjectIntegrityFailed(oid)` and `store.has(oid)` is false.
- Token only on the batch host.
  - *Given* the batch URL on `127.0.0.1:A` and an action href on `127.0.0.1:B` with no `header`, *When* a download runs, *Then* the request log shows `Authorization` on port A only.
- Retry and Retry-After.
  - *Given* the server answering 429 with `Retry-After: 1` once then 200, *When* a batch runs, *Then* it succeeds after one retry.
- Error mapping.
  - *Given* batch responses 401, 403 (upload), 404, 413, 422, 429, 507, 509, *When* `LfsErrorMapper` classifies each, *Then* results are `LfsAuthFailed(false)`, `LfsAuthFailed(true)`, repo-not-found `FetchFailed`, split-batch-then-`PushFailed`, `LfsPointerCorrupt`, `RateLimited`, `LfsQuotaExceeded`, `LfsQuotaExceeded`.
- Resume.
  - *Given* a download cut after 1 MB with `Accept-Ranges: bytes`, *When* retried, *Then* the second request has `Range: bytes=1048576-` and the final object verifies.
**Files**: `{C}/git/lfs/client/LfsBatchModels.kt`, `{C}/git/lfs/client/LfsObjectStore.kt`, `{C}/git/lfs/client/LfsBatchClient.kt`, `{C}/git/lfs/client/LfsErrorMapper.kt`, `{C}/git/lfs/client/LfsRemoteResolver.kt`

##### Task E2.2a: Batch models, LfsEndpoint, LfsAuth [S]
- `@Serializable` request and response DTOs (`operation`, `transfers`, `ref`, `objects[{oid,size}]`, `hash_algo`, per-object `actions{download|upload|verify}{href,header,expires_at,expires_in}`, per-object `error{code,message}`), `LfsEndpoint`, `LfsAuth` (Basic, Bearer, None). Content type `application/vnd.git-lfs+json`.
- Files: `{C}/git/lfs/client/LfsBatchModels.kt`.

##### Task E2.2b: LfsObjectStore port and hashing writer [S]
- Interface per ADR-004 with okio `Source`/`Sink`; common helper `HashingLfsWriter` using okio `HashingSink.sha256` to verify oid and size on `commit()`; temp-then-rename left to the platform implementations via an `atomicMove` hook.
- Files: `{C}/git/lfs/client/LfsObjectStore.kt`.

##### Task E2.2c: LfsBatchClient [M]
- `batch(op, objects, ref)` with chunks of 100 and one halving retry on 413; `download(action, writer)` streaming from `bodyAsChannel`, `Range` resume; `upload(action, source, size)` with `verify` call when present; per-request timeouts (connect 15 s, request 120 s, per object configurable); retry with exponential backoff and `Retry-After` for 429 and 5xx using a local `LfsRetryPolicy`; progress callback `(oid, done, total)`; supports `LfsEndpoint` proxy mapping (Task E4.3a).
- Files: `{C}/git/lfs/client/LfsBatchClient.kt`.

##### Task E2.2d: LfsErrorMapper [S]
- Pure mapping of (HTTP status, headers, body text, operation) to `DomainError.GitError` per the table in F section 1; quota detection by status (403 on upload with quota wording, 507, 509) plus a case-insensitive substring match on `quota` or `bandwidth`, falling back to the status-only mapping because the real body is a GAP (Q2). `LfsErrorMapperTest` (commonTest) registered in `{BT}/AllBusinessTests.kt` and `business_common_tests.srcs`.
- Files: `{C}/git/lfs/client/LfsErrorMapper.kt`, `{CT}/git/lfs/LfsErrorMapperTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E2.2e: LfsRemoteResolver [S]
- Derive `LfsEndpoint`: `lfs.url`, then `.lfsconfig` `lfs.url`, then `<remote-url minus .git>.git/info/lfs`; SSH remotes map to the HTTPS URL (GitHub `git@github.com:o/r.git` to `https://github.com/o/r.git/info/lfs`) when an https token exists, else `LfsNotSupportedHere("ssh")` unless S3 is GO. Never puts credentials in the URL. `LfsRemoteResolverTest` (commonTest) registered.
- Files: `{C}/git/lfs/client/LfsRemoteResolver.kt`, `{CT}/git/lfs/LfsRemoteResolverTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E2.2f: Client tests against FakeLfsServer [M]
- `LfsBatchClientTest` (businessTest, real HTTP via the JVM OkHttp engine at `kmp/build.gradle.kts:180`): the five acceptance cases plus `uploadSkippedWhenServerAlreadyHasObject`, `verifyActionCalled`, `expiredActionRejected`, `tokenNeverInUrlOrLogs`, `quotaExceededMidBatchKeepsAcceptedObjectsAndReportsRest`. Register in `{BT}/AllBusinessTests.kt`.
- Files: `{BT}/git/lfs/LfsBatchClientTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E2.3: JVM adapter: store, scanner, transfer service, repository wiring
**As a** desktop user, **I want** clone and pull to materialise LFS files and push to upload them before the ref moves, **so that** no other client ever sees a pointer without its object (P R1).
**Acceptance Criteria**:
- Clone materialises content.
  - *Given* a bare origin with `assets/a.bin` stored as a pointer (3 MB object on `FakeLfsServer`), `lfs.url` set to the fixture, *When* `JvmGitRepository.clone` completes, *Then* `assets/a.bin` holds the 3 MB original bytes and `.git/lfs/objects/<aa>/<bb>/<oid>` exists.
- Push uploads before the ref update, and a failed upload leaves the remote unchanged.
  - *Given* a local commit adding two LFS files and `FakeLfsServer` failing the second PUT with 500 on every attempt, *When* `JvmGitRepository.push` runs, *Then* it returns a failure, the bare origin's branch tip is unchanged, and no pointer blob for either file exists in the origin.
- Push of a successful run leaves only pointers in the origin.
  - *Given* a new 3 MB `assets/b.bin` committed locally, *When* `sync` pushes, *Then* the server holds exactly one new object whose SHA-256 equals its oid and every blob at an LFS path in the origin parses as a pointer.
- Shallow push uploads only new objects.
  - *Given* a depth-50 clone and one new LFS file, *When* pushed, *Then* the server received exactly one upload.
- Merge prefetch.
  - *Given* remote commits changing two pointers, *When* `merge` runs, *Then* both objects are downloaded before `git.merge()` and the merged working files hold real bytes.
- Credentials.
  - *Given* an https token in the vault and `FakeLfsServer` requiring Basic auth, *When* any transfer runs, *Then* the recorded `Authorization` decodes to `<user>:<token>` on batch calls only.
**Files**: `{JC}/git/lfs/JgitLfsSetup.kt`, `{JC}/git/lfs/JvmLfsObjectStore.kt`, `{JC}/git/lfs/LfsPointerScanner.kt`, `{JC}/git/lfs/LfsTransferService.kt`, `{J}/git/JvmGitRepository.kt`

##### Task E2.3a: JgitLfsSetup [S]
- `BuiltinLFS.register()` once (idempotent, guarded by a lazy); per-repo `configure(repo, endpoint)`: `filter.lfs.clean`, `filter.lfs.smudge` to the builtin names (or the `jgit://stelekit/lfs/*` fallback from E2.1f), `filter.lfs.required=false`, `lfs.url`; leave `filter.lfs.useJGitBuiltin` unset (ADR-001 item 2). If the config contains external `git-lfs` commands (`filter.lfs.process` or `git-lfs clean -- %f`) the repo-local config overrides them with the builtin names (store layout is shared with the git-lfs CLI); log it. The user's global `~/.gitconfig` is covered too (JGit reads it). In `GUARD_ONLY` and `OFF` the repo-local config is ALSO written, pointing at a stateless registered passthrough filter `jgit://stelekit/lfs/passthrough` (bytes in, bytes out, no process exec), so a global external `git-lfs` filter never runs for those graphs. `BuiltinLFS.register()` is process-global but only registers filter names and is inert without per-repo config (`useJGitBuiltin` stays unset), so graphs with different modes in one JVM do not leak. Sets `cleanFilterActive` after reading the config back (read the mutation back, do not assume it applied).
- Hook detection (review C4b): if `.git/hooks/pre-push` exists and mentions `git lfs`, apply the S5 outcome: return `LfsNotSupportedHere("hook")` when S5 recorded a conflict, otherwise proceed; SteleKit never edits user hooks. `cleanFilterActive` is true only for converting filters.
- Tests: `LfsModeIsolationTest` (two graphs, AUTO and GUARD_ONLY, one JVM, `SystemReader` replaced so the global config has `filter.lfs.clean=git-lfs clean -- %f`: the GUARD_ONLY graph never executes it, the AUTO graph uses the builtin); `LfsRawBinaryNeverCommittedTest` (Task E1.5c) is also run under that global config.
- Files: `{JC}/git/lfs/JgitLfsSetup.kt`, `{BT}/git/lfs/LfsModeIsolationTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3b: JvmLfsObjectStore [S]
- `LfsObjectStore` over `<gitdir>/lfs/objects/aa/bb/<oid>`, temp dir `<gitdir>/lfs/tmp`, atomic move, hash and size verification in `commit()`, `.part` retention for resume, delete on mismatch. Test `JvmLfsObjectStoreTest` registered.
- Files: `{JC}/git/lfs/JvmLfsObjectStore.kt`, `{BT}/git/lfs/JvmLfsObjectStoreTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3c: LfsPointerScanner [M]
- Three scans, all filtered through `LfsPathPolicy` and the under-1024-byte size check: (1) pointers in a tree id, (2) pointers added or changed between two tree ids, (3) pointer blobs introduced by commits reachable from the pushed refs but not from any `refs/remotes/<config.remoteName>/*` (remote name from `GitConfig`, never hard-coded `origin`). Reuses `LfsPointerCodec`, not JGit's parser. Test `LfsPointerScannerTest` (real JGit): includes a shallow repo and a custom remote name; registered.
- Files: `{JC}/git/lfs/LfsPointerScanner.kt`, `{BT}/git/lfs/LfsPointerScannerTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3d: LfsTransferService and LfsStoragePreflight [M]
- `prefetch(repo, pointers, endpoint, auth, policy, progress)`: skip objects already in the store, preflight with `LfsStoragePreflight` (commonMain pure: `required = sum(size) x copies` versus free; returns `LfsStorageInsufficient`), batch, stream into the store, emit `LfsProgress`. `uploadForPush(repo, pointers, ...)`: every pointer's object must exist locally (else `LfsObjectMissing`, abort), batch `upload`, PUT only where the server returned an action, `verify`. `LfsStoragePreflightTest` (commonTest) and `LfsTransferServiceTest` registered.
- Files: `{JC}/git/lfs/LfsTransferService.kt`, `{C}/git/lfs/LfsStoragePreflight.kt`, `{CT}/git/lfs/LfsStoragePreflightTest.kt`, `{BT}/git/lfs/LfsTransferServiceTest.kt`, `{BT}/AllBusinessTests.kt` (the `business_common_tests.srcs` entry for the commonTest file is made in Task E7.4a to keep this task at 5 files).

##### Task E2.3e: LfsCredentialBridge and leak audits [S]
- `LfsCredentialBridge` turns `credentialAccess.retrieve(config.httpsTokenKey or oauthTokenKey)` into `LfsAuth.Basic` using the same username mapping as `GitOperationSupport.kt:501-526` (`""` for `HTTPS_TOKEN`, `x-oauth-basic` for `GITHUB_OAUTH`). Extend `PatLeakageAuditTest` (`{JT}/git/PatLeakageAuditTest.kt`, existing) and `SecretRedactionTest` (`{CT}/git/SecretRedactionTest.kt`, existing) with LFS URL and signed-href cases. New `LfsCredentialBridgeTest` registered.
- Files: `{JC}/git/lfs/LfsCredentialBridge.kt`, `{BT}/git/lfs/LfsCredentialBridgeTest.kt`, `{JT}/git/PatLeakageAuditTest.kt`, `{CT}/git/SecretRedactionTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3f: Wire clone, fetch and merge in JvmGitRepository [M]
- `clone` (`:108-132`): after `cmd.call()` configure LFS (E2.3a); per ADR-001 sequence use `setNoCheckout(true)`, scan HEAD tree, prefetch per `LfsDownloadPolicy` (Story E2.4), then `checkout`. `fetch` (`:168`, `:195`): after fetch, scan tree diff HEAD vs fetched ref and prefetch. `doMerge` (`:261-296`): prefetch changed pointers right before `git.merge()` and after the shallow guard `isShallowHistoryInsufficientForMerge`. Respect `lfs_mode`: `GUARD_ONLY` skips all transfer.
- Files: `{J}/git/JvmGitRepository.kt`.

##### Task E2.3g: Wire push, checkoutFile and markResolved [M]
- `doPush` (`:307-352`): inside the `runGitTransportOpWithRetry` lambda, before `git.push()` call `uploadForPush`; failure returns the mapped error and the push is not attempted. `checkoutFile` (`:398-423`): prefetch that side's pointer first. `markResolved` (`:425-430`): keep the E1.5a verify. Uploads are idempotent (server dedupes by oid).
- Files: `{J}/git/JvmGitRepository.kt`.

##### Task E2.3h: Clone and merge integration tests [M]
- `JvmGitRepositoryLfsCloneTest`, `JvmGitRepositoryLfsMergeTest` (two-sided pointer changes, one-sided change resolves to that side, both-sided pointer conflict surfaces as a conflict), over `LfsBareOriginFixtures` (Story E7.2) and `FakeLfsServer`. Register both.
- Files: `{BT}/git/lfs/JvmGitRepositoryLfsCloneTest.kt`, `{BT}/git/lfs/JvmGitRepositoryLfsMergeTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3i: Push integration tests [M]
- `JvmGitRepositoryLfsPushTest` (success, auth required, 3 MB), `LfsPushOrderingTest` (second PUT fails, origin tip unchanged: R1 half-failed push), `LfsShallowPushTest` (depth 50, exactly one upload), over the same fixtures. Register all three.
- Files: `{BT}/git/lfs/JvmGitRepositoryLfsPushTest.kt`, `{BT}/git/lfs/LfsPushOrderingTest.kt`, `{BT}/git/lfs/LfsShallowPushTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3j: Differential test of the pointer parser against JGit [S]
- `LfsPointerDifferentialTest`: for spec-canonical generated pointers our parser and `org.eclipse.jgit.lfs.LfsPointer.parseLfsPointer` agree on oid and size; for the rejection set each divergence (JGit skips comments, blank lines, unsorted keys, CRLF) is asserted so the documented difference cannot silently change. Register.
- Files: `{BT}/git/lfs/LfsPointerDifferentialTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3k: Mode-independent push-time pointer-without-object check [S]
- In `doPush` (JVM) and Android push: always, in every `LfsMode` and flag state, run `LfsPointerScanner` scan 3 over the commits to push and require each referenced object to exist in the local store or to be reported present by a batch `upload` probe; otherwise return `LfsObjectMissing(oid)` before `git.push()`. In `GUARD_ONLY` or flag-off the probe is a batch call with no transfer. Test `LfsModeSwitchPushTest`: pointers committed under AUTO, mode switched to GUARD_ONLY, objects absent from the server: push refused and origin unchanged; with objects present on the server: push allowed. Register.
- Files: `{J}/git/JvmGitRepository.kt`, `{A}/git/AndroidGitRepository.kt`, `{BT}/git/lfs/LfsModeSwitchPushTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3l: Register the existing extended tests [S]
- First-time registration of existing classes extended by this plan: `PatLeakageAuditTest` in `{JT}/AllJvmTests.kt` (the Bazel `jvm_tests` `test_class`); `SecretRedactionTest` into `business_common_tests.srcs` and `{BT}/AllBusinessTests.kt`. (`DomainErrorTest` is Task E1.3a, `SteleKitAssetMapperTest` is Task E3.4c, `AndroidGitRepositoryStorageGuardTest` is Task E2.5e.) Run `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests ...` for the jvm suite (display tests) and show output.
- Files: `{JT}/AllJvmTests.kt`, `{BT}/AllBusinessTests.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`.

##### Task E2.3m: Own filters (expected baseline if S5 is NO-GO) [M]
- `StelekitLfsFilters` (jvmCommon): `jgit://stelekit/lfs/clean` (pointer input passes through unchanged, otherwise hash into `JvmLfsObjectStore` and emit the pointer; never touches the network), `jgit://stelekit/lfs/smudge` (object present: content; absent: leave the pointer text, never download), `jgit://stelekit/lfs/passthrough`. Registered in `FilterCommandRegistry` by `JgitLfsSetup`. Test `StelekitLfsFiltersTest`: all three, plus `status` clean with a pointer in the working tree, and `add` of a pointer does not create a pointer to a pointer. Built only if S5 is NO-GO; otherwise the task is closed as not needed and the test is deleted (decision recorded in ADR-001).
- Files: `{JC}/git/lfs/StelekitLfsFilters.kt`, `{JC}/git/lfs/JgitLfsSetup.kt`, `{BT}/git/lfs/StelekitLfsFiltersTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.3n: Post-push read-back and re-upload [S]
- After a successful push (JVM and Android), probe each newly pushed pointer's object with a batch `download` (no transfer) and re-upload from the local store on any missing object (pre-mortem N12); surface `LfsObjectMissing` with a retry action if the local copy is gone. Test `LfsPostPushReadBackTest` (fake server drops an object after PUT: detected and re-uploaded). Register. Web counterpart: Task E4.2b checks pointer-without-object before commit.
- Files: `{JC}/git/lfs/LfsTransferService.kt`, `{J}/git/JvmGitRepository.kt`, `{BT}/git/lfs/LfsPostPushReadBackTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E2.4: Settings, download policy, metered networks, first-sync review
**As a** user, **I want** to choose how much LFS data downloads and to see the estimated size before the first sync pulls it, **so that** mobile data and host quota are not burned unexpectedly (F section 3, requirements Open Question 2).
**Acceptance Criteria**:
- Policy never exceeds caps.
  - *Given* `LfsSettings(autoDownload=ALL, maxObjectBytes=25 MiB, maxSyncBytes=200 MiB, wifiOnly=true)` and `metered=true`, *When* `LfsDownloadPolicy.plan(pointers)` runs, *Then* it returns zero immediate downloads and every object deferred.
  - *Given* the same settings and `metered=false` and 30 pointers of 10 MiB, *When* planned, *Then* at most 20 objects are immediate (200 MiB cap) and the rest deferred.
- First-sync review blocks automatic LFS pulls until decided.
  - *Given* a freshly cloned repo whose HEAD tree has 120 pointers totalling 1.4 GiB and no stored decision, *When* `sync(graphId, SyncTrigger.Automatic)` runs, *Then* it returns `FirstSyncReviewPending`, no LFS object is requested, and the review sheet shows "120 files, about 1.4 GiB".
- Metered detection.
  - *Given* Android `ConnectivityManager.isActiveNetworkMetered()` true, *When* `NetworkMonitor.isMetered()` is read, *Then* it returns true.
**Files**: `{C}/git/lfs/LfsSettings.kt`, `{C}/git/lfs/LfsDownloadPolicy.kt`, `{C}/git/lfs/LfsRepoSurvey.kt`, `{C}/platform/NetworkMonitor.kt`, `{C}/ui/screens/git/LfsReviewSheet.kt`

##### Task E2.4a: LfsSettings, LfsSettingsStore, LfsDownloadPolicy [S]
- `LfsMode` and `git_lfs_mode_<graphId>` already exist from Task E1.4f. Other keys: `git_lfs_auto_<graphId>`, `git_lfs_max_object_<graphId>`, `git_lfs_max_sync_<graphId>`, `git_lfs_wifi_only_<graphId>`. Defaults per O3 proposal, with a non-zero desktop per-sync cap (1 GiB) so `ALL` cannot burn the host owner's bandwidth unboundedly (pre-mortem N10). `LfsDownloadPolicy.plan(settings, metered, pointers)` pure. Tests `LfsSettingsStoreTest`, `LfsDownloadPolicyTest` (property: `neverExceedsPerObjectCap`, `neverExceedsSyncCap`, `meteredAndWifiOnlyDefersAll`) registered.
- Files: `{C}/git/lfs/LfsSettings.kt`, `{C}/git/lfs/LfsDownloadPolicy.kt`, `{CT}/git/lfs/LfsDownloadPolicyTest.kt`, `{CT}/git/lfs/LfsSettingsStoreTest.kt`, `{BT}/AllBusinessTests.kt` (BUILD.bazel srcs entry in Task E7.4a).

##### Task E2.4b: NetworkMonitor.isMetered [S]
- Add `fun isMetered(): Boolean` to the expect class `{C}/platform/NetworkMonitor.kt:12`; actuals: Android (`ConnectivityManager.isActiveNetworkMetered`), iOS (`NWPath.isExpensive`), JVM (false), wasm (false; `navigator.connection.saveData` could approximate it but is INFERRED and not used in v1). `grep -rn "NetworkMonitor" kmp/src` to update any test double.
- Files: `{C}/platform/NetworkMonitor.kt`, `{A}/platform/AndroidNetworkMonitor.kt`, `{I}/platform/IosNetworkMonitor.kt`, `{J}/platform/JvmNetworkMonitor.kt`, `{W}/platform/NetworkMonitor.kt`.

##### Task E2.4c: LfsRepoSurvey, decision store and sync gating [M]
- `LfsTreeSource` port (`trackedPatterns()`, `pointers()` as (path, oid, size) from tree blobs, `legacyRawCount()`); JGit impl `{JC}/git/lfs/JgitLfsTreeSource.kt`; `LfsRepoSurvey.build(source)`; `LfsFirstSyncDecision` store (`git_lfs_decision_<graphId>`, `NONE|ALL|ON_DEMAND|PLACEHOLDERS`) and `LfsMigrationConfirmation`. `GitSyncService` returns `FirstSyncReviewPending` for Automatic triggers while a survey with pointers has no decision (same mechanism as `GitSyncService.kt:256-260`).
- Files: `{C}/git/lfs/LfsRepoSurvey.kt`, `{JC}/git/lfs/JgitLfsTreeSource.kt`, `{C}/git/lfs/LfsFirstSyncDecision.kt`, `{C}/git/GitSyncService.kt`.

##### Task E2.4g: LfsLegacyRawDetector and LfsMigrationConfirmation [M]
- `LfsLegacyRawDetector` (jvmCommon): tracked paths whose HEAD blob is raw (not a pointer) and whose working file's git blob sha equals that HEAD blob are `LegacyRawPending`; they go into `excludePaths` silently (no per-sync `LfsActionRequired` nag; reconciles ADR-003 section 4) and are reported only in the review sheet and as a persistent count in `LfsSettingsSection`, until `LfsMigrationConfirmation` (commonMain, key `git_lfs_migration_confirmed_<graphId>`, `FirstSyncConfirmation` pattern) is set, after which the conversion commit is allowed. A tracked file the user edited converts normally. `LfsRepoSurvey.legacyRawCount` uses the same detector. Test `LfsLegacyRawDetectorTest` (real JGit): unchanged raw tracked file is pending; edited file converts; after confirmation all convert; no mass conversion unconfirmed. Register.
- Files: `{JC}/git/lfs/LfsLegacyRawDetector.kt`, `{C}/git/lfs/LfsFirstSyncDecision.kt`, `{C}/git/GitSyncService.kt`, `{BT}/git/lfs/LfsLegacyRawDetectorTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.4d: Review sheet and settings section [M]
- `LfsReviewSheet` (patterns, file count, summed size, quota note "GitHub downloads count against the repository owner's bandwidth", legacy-raw count, three choices); `LfsSettingsSection` composable inside `GitSetupStep4Branch` after LFS detection and in the per-graph git settings dialog (`GraphContentGitSyncSetup.kt`); the wasm-only proxy field is added in Task E4.3b.
- Files: `{C}/ui/screens/git/LfsReviewSheet.kt`, `{C}/ui/screens/git/LfsSettingsSection.kt`, `{C}/ui/screens/git/GitSetupStep4Branch.kt`, `{C}/ui/GraphContentGitSyncSetup.kt`.

##### Task E2.4e: First-sync and sheet tests [S]
- `LfsFirstSyncReviewTest` (businessTest, `StubGitRepository` plus a fake `LfsTreeSource`: pending until decided, "None" resumes, decision persisted). Robolectric `LfsReviewSheetTest` (counts and size text, choices). Register in `{BT}/AllBusinessTests.kt` and `{AT}/AllAndroidUnitTests.kt`.
- Files: `{BT}/git/lfs/LfsFirstSyncReviewTest.kt`, `{AT}/ui/screens/git/LfsReviewSheetTest.kt`, `{BT}/AllBusinessTests.kt`, `{AT}/AllAndroidUnitTests.kt`.

##### Task E2.4f: SyncState.LfsTransferring and progress [S]
- `SyncState.LfsTransferring(done: Int, total: Int, bytes: Long)`; `GitSyncService` maps `LfsProgress` to it during prefetch and upload; badge text "Downloading large files 3 of 12". Each new `SyncState` member is a registry candidate for PR #413 (Task E6.4b).
- Files: `{C}/git/model/SyncState.kt`, `{C}/git/GitSyncService.kt`, `{C}/ui/components/SyncStatusBadge.kt`.

##### Task E2.4h: Metered and transferring-state tests [S]
- `AndroidNetworkMonitorMeteredTest` (androidUnitTest, `isMetered()` true and false via a shadowed `ConnectivityManager`), `LfsTransferringStateTest` (businessTest: `LfsProgress` maps to `SyncState.LfsTransferring` with monotonic done counts). Register both.
- Files: `{AT}/platform/AndroidNetworkMonitorMeteredTest.kt`, `{BT}/git/lfs/LfsTransferringStateTest.kt`, `{AT}/AllAndroidUnitTests.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.4i: LfsBandwidthLedger [S]
- `LfsBandwidthLedger` (commonMain): per-host monthly downloaded and uploaded bytes in `Settings` (`git_lfs_ledger_<host>_<yyyymm>`), a soft monthly budget that moves `ALL` to `ON_DEMAND` with a notice when exceeded, and the conversion size shown before `LfsMigrationConfirmation` (pre-mortem N10). Test `LfsBandwidthLedgerTest` (commonTest, property: monotonic, month rollover). Register.
- Files: `{C}/git/lfs/LfsBandwidthLedger.kt`, `{CT}/git/lfs/LfsBandwidthLedgerTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

#### Story E2.5: Android: binary-safe shadow path, storage guard, wiring (flag off until device pass)
**As an** Android user, **I want** LFS files to arrive in my SAF graph as real bytes and never be corrupted or deleted by the mirror, **so that** Android can be declared supported (P R2, R7).
**Acceptance Criteria**:
- Binary round trip.
  - *Given* a SAF file with bytes `FF FE 00 80 7F` and size 5, *When* `syncFromSafRoot` mirrors it, *Then* the shadow file is byte-identical (the S4 negative test now passes).
- Smudged media are not deleted before write-back.
  - *Given* JGit smudged `assets/a.png` into the shadow tree and it is not yet in the SAF listing, *When* `deleteOrphanedShadowFiles` runs, *Then* the file survives until the flush actor writes it to SAF.
- Storage preflight counts copies.
  - *Given* pointers summing 500 MB and 450 MB free in `filesDir`, *When* clone preflight runs, *Then* it returns `LfsStorageInsufficient(neededBytes=1_000_000_000+base, availableBytes=450_000_000)` before any download byte (objects x 2 in private storage; the third copy is on the SAF volume and checked there).
**Files**: `{A}/git/GitShadowWorktree.kt`, `{A}/git/AndroidGitRepositoryShadow.kt`, `{A}/git/GitShadowFlushActor.kt`, `{A}/git/GitWriteBackQueue.kt`, `{A}/git/AndroidGitRepository.kt`

##### Task E2.5a: Byte-safe SAF to shadow mirror [M]
- Replace the `String` read with a streamed copy, never a whole-file `ByteArray`: `GitShadowWorktree.syncFromSafRoot(listRecursive, openSafStream: suspend (String) -> okio.Source?, force)` (`:194-250`); change detection uses the listing's size plus mtime and the manifest (`isEntryStale` at `:281`, `manifestSafMtimeFor` at `:323`), never a content read; the copy goes through a temp file and atomic rename (CLAUDE.md: an uncaught OOM kills the process on Android); after mirroring, a streaming SHA-256 of each mirrored LFS-tracked binary is compared with the source stream's hash before any commit can include it (N6); update callers `AndroidGitRepositoryShadow.kt:118` and `AndroidGitRepository.kt:420`; add stream-based `openShadowSource` and `writeShadowFromSource` beside `readShadowFile` (`:122`) and `writeShadowFile` (`:143`) and keep the text ones for markdown.
- Files: `{A}/git/GitShadowWorktree.kt`, `{A}/git/AndroidGitRepositoryShadow.kt`, `{A}/git/AndroidGitRepository.kt`.

##### Task E2.5b: Shadow to SAF write-back of smudged binaries and orphan rule [M]
- `GitShadowFlushActor` (`:58` uses `readShadowFile` text) and `GitWriteBackQueue` write bytes to SAF via `FileSystem.writeFileBytes`; an `LfsMaterialisedSet` (persisted in the shadow manifest, `GitShadowWorktree.kt:296-310`) protects those paths from `deleteOrphanedShadowFiles` (`:261`) until flushed. Quarantine, never delete (pre-mortem N5): an orphaned shadow media file or one whose SAF grant was revoked is moved to `<shadow>/.stele-quarantine/` instead of being deleted, and a binary deletion is never committed on a single signal. Flush recovery (review C6): SAF writes go to a temp name and rename; on the next launch every `LfsMaterialisedSet` entry is re-flushed from the shadow copy, and the shadow copy is deleted only when the SAF size and SHA-256 match.
- Files: `{A}/git/GitShadowFlushActor.kt`, `{A}/git/GitWriteBackQueue.kt`, `{A}/git/GitShadowWorktree.kt`, `{A}/platform/PlatformFileSystem.kt`.

##### Task E2.5c: Android storage preflight [S]
- In `AndroidGitRepositoryShadow.kt` (clone guard at `:68-78`, `MIN_SHADOW_FREE_BYTES` at `:136`): when LFS applies, `required = MIN_SHADOW_FREE_BYTES + 2 x lfsBytes` through `LfsStoragePreflight`; separately check SAF volume free space for the third copy; fail with `LfsStorageInsufficient`.
- Files: `{A}/git/AndroidGitRepositoryShadow.kt`.

##### Task E2.5d: Wire AndroidGitRepository behind androidLfsEnabled [M]
- Same call sites as E2.3f and E2.3g: clone (`:118`), fetch (`:181`, `:208`), merge (`:296`), push (`:364`), `checkoutFile` (`:454`), `markResolved` (`:495`), plus `AndroidGitRepositoryMerge.kt` writes at `:95`, `:122` (use bytes variants for tracked paths). All guarded by `androidLfsEnabled` (default false): when false, behaviour is guard-only plus placeholders.
- Files: `{A}/git/AndroidGitRepository.kt`, `{A}/git/AndroidGitRepositoryMerge.kt`, `{A}/git/AndroidGitRepositoryShadow.kt`.

##### Task E2.5e: Android tests [M]
- `GitShadowWorktreeBinaryTest` (adds `largeFileMirrorDoesNotLoadWholeFile`: a 100 MB sparse file mirrored with a heap-bound assertion and a counting `Source`; `quarantineNotDeleteOnOrphan`; `mirrorSha256RoundTrip`; replaces the spike test), `GitShadowFlushActorBinaryTest` (adds `flushDiesMidwayLeavesNoPartialSafFileAndKeepsMaterialisedFlag` and `restartReflushesFromShadowUntilSafHashMatches`), `AndroidGitRepositoryStorageGuardTest` (existing, not in `AllAndroidUnitTests` today, so this task registers it for the first time; add LFS cases), `AndroidGitRepositoryLfsTest` (Robolectric, `LfsBatchClient` over Ktor `MockEngine` because `com.sun.net.httpserver` under Robolectric is UNVERIFIED, Q4). Add `io.ktor:ktor-client-mock:3.1.3` to the `androidUnitTest` block (`kmp/build.gradle.kts:354`). Register in `{AT}/AllAndroidUnitTests.kt`; delete the spike test.
- Files: `{AT}/git/GitShadowWorktreeBinaryTest.kt`, `{AT}/git/GitShadowFlushActorBinaryTest.kt`, `{AT}/git/AndroidGitRepositoryLfsTest.kt`, `{AT}/git/AndroidGitRepositoryStorageGuardTest.kt`, `{AT}/AllAndroidUnitTests.kt` (Gradle and Bazel dependency lines in Task E2.8b).

#### Story E2.6: Pointer conflicts are object-level, never text merges
**As a** user whose binary changed on both sides, **I want** "keep mine" or "keep theirs" for the file, **so that** I never edit conflict markers inside a pointer (P R9).
**Acceptance Criteria**:
- *Given* a merge leaving `assets/a.png` with conflict markers inside a pointer, *When* `GitSyncService` computes unresolved files, *Then* `assets/a.png` is listed as an LFS pointer conflict (not only `.md` files), `ConflictFile.isLfsPointer` is true, and resolving "theirs" runs `checkoutFile(THEIRS)` plus prefetch of that object.
**Files**: `{C}/git/GitSyncService.kt`, `{C}/git/model/ConflictModels.kt`, `{C}/ui/screens/git/ConflictResolutionScreen.kt`, `{BT}/git/lfs/LfsConflictPointerTest.kt`

##### Task E2.6a: Detect and resolve pointer conflicts [M]
- `findFilesWithConflictMarkers` (`GitSyncService.kt:478-488`) also considers any tracked path (via `LfsPathPolicy`), flagging `isLfsPointer`; `ConflictFile` gains `isLfsPointer`; resolution of such files only offers sides (whole-file path in `resolveConflicts`), then prefetch.
- Files: `{C}/git/GitSyncService.kt`, `{C}/git/model/ConflictModels.kt`, `{C}/git/ConflictResolver.kt`.

##### Task E2.6b: UI and test [S]
- `ConflictResolutionScreen` hides the hunk editor for `isLfsPointer` files and shows two buttons. `LfsConflictPointerTest` registered; Robolectric `ConflictResolutionLfsTest` (two buttons, no hunk editor) registered in `{AT}/AllAndroidUnitTests.kt`.
- Files: `{C}/ui/screens/git/ConflictResolutionScreen.kt`, `{BT}/git/lfs/LfsConflictPointerTest.kt`, `{AT}/ui/screens/git/ConflictResolutionLfsTest.kt`, `{BT}/AllBusinessTests.kt`, `{AT}/AllAndroidUnitTests.kt`.

#### Story E2.7: Watcher and self-write suppression for LFS materialisation
**As a** user, **I want** smudge writes not to appear as external edits, **so that** a pull does not raise `DiskConflict` prompts or re-parse every asset (P R14).
**Acceptance Criteria**:
- *Given* a pull that smudges 40 binary files, *When* `GraphFileWatcher` observes the writes, *Then* zero `DiskConflict` events and zero page re-parses occur for them, and non-markdown paths are skipped before any read.
**Files**: `{C}/db/GraphFileWatcher.kt`, `{C}/git/GitSyncService.kt`, `{BT}/git/lfs/LfsWatcherSuppressionTest.kt`

##### Task E2.7a: Quiet window around LFS prefetch and smudge [M]
- Read how the existing merge brackets self-writes (`EditLock.kt`, `FileRegistry.kt:18`, `GraphFileWatcher.kt:50`) and extend the same bracket to cover LFS prefetch plus checkout; make the watcher drop non-markdown, non-asset-index events early. The bracket also wraps `LfsLocalObjectResolver`'s single-path `git.checkout().addPath(path)` (Task E3.2b) and, on Android, the shadow to SAF flush of materialised binaries (`GitShadowFlushActor`), so on-demand downloads and SAF write-back raise no `DiskConflict`.
- Files: `{C}/db/GraphFileWatcher.kt`, `{C}/git/GitSyncService.kt`, `{A}/git/GitShadowFlushActor.kt`.

##### Task E2.7b: Test [S]
- `LfsWatcherSuppressionTest` with a fake watcher fed 40 binary writes during a sync bracket. Register.
- Files: `{BT}/git/lfs/LfsWatcherSuppressionTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E2.8: Bazel and Gradle graduation (after GO)
**As a** maintainer, **I want** the dependency in both build systems and the spikes graduated into regular tests, **so that** `bazel test //kmp:business_tests` exercises LFS (P section 1.5).
**Acceptance Criteria**:
- *Given* the lfs coordinate added and pinned, *When* `bazel test //kmp:business_tests` runs, *Then* it passes and `AllBusinessTestsCompletenessTest` reports no unregistered LFS class.
**Files**: `MODULE.bazel`, `rules_jvm_external++maven+maven_install.json`, `kmp/src/jvmMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`, `kmp/src/businessTest/kotlin/BUILD.bazel`

##### Task E2.8a: MODULE.bazel pin [S]
- Add `"org.eclipse.jgit:org.eclipse.jgit.lfs:7.3.0.202506031305-r",` next to `MODULE.bazel:311-312`; correct the stale comment at `:139` ("Android uses 5.13.x"); run `bazel run @maven//:pin` on the 16 GB machine (note at `:157`); commit the lock file. gson 2.8.9 is already in the lock (9 matches in the lock file).
- Files: `MODULE.bazel`, `rules_jvm_external++maven+maven_install.json`.

##### Task E2.8b: BUILD deps [S]
- Add `@maven//:org_eclipse_jgit_org_eclipse_jgit_lfs` beside `@maven//:org_eclipse_jgit_org_eclipse_jgit` (`kmp/src/jvmMain/kotlin/BUILD.bazel:181`, and the android library target in `kmp/src/androidMain/kotlin/BUILD.bazel`); add `@maven//:io_ktor_ktor_client_mock_jvm` to `business_tests` deps in `kmp/src/businessTest/kotlin/BUILD.bazel` (`ktor-client-mock-jvm` is on `jvm_tests` only today); add the androidUnitTest ktor mock dependency to `kmp/src/androidUnitTest/kotlin/BUILD.bazel`.
- Files: `kmp/src/jvmMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`, `kmp/src/businessTest/kotlin/BUILD.bazel`, `kmp/src/androidUnitTest/kotlin/BUILD.bazel`.

##### Task E2.8c: Graduate spikes [S]
- Move S1, S2 and S5 into businessTest as regular regression tests (`LfsFilterActivationTest`, `LfsCloneNoCheckoutTest`, `LfsDeferredObjectCheckoutTest`; S5 decides the baseline filter, so it must outlive the spike), register them, delete the `spike` directory; S3 becomes `LfsSshAuthenticateTest` only if GO, otherwise deleted with the NO-GO recorded in ADR-001.
- Files: `{BT}/git/lfs/LfsFilterActivationTest.kt`, `{BT}/git/lfs/LfsCloneNoCheckoutTest.kt`, `{BT}/git/lfs/LfsDeferredObjectCheckoutTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E2.8d: JGit version parity check [S]
- `scripts/check-jgit-version-parity.sh` fails unless `org.eclipse.jgit`, `org.eclipse.jgit.lfs`, `ssh.apache` and `ssh.jsch` carry the same version string in `kmp/build.gradle.kts` and `MODULE.bazel` (pre-mortem N9); run as a step in `.github/workflows/ci.yml`. No dependency-bot config exists in the repo (`.github/dependabot.yml` is absent), so grouping is moot; the script is the control.
- Files: `scripts/check-jgit-version-parity.sh`, `.github/workflows/ci.yml`.

#### Story E2.9: Pitfall closures (R6, R15, R17 to R20)
**As a** maintainer, **I want** each remaining research pitfall closed or explicitly accepted, **so that** none is silently dropped (see "Pitfall to task mapping").
**Acceptance Criteria**:
- R6: *Given* a pointer whose line endings became CRLF, *When* the repair runs, *Then* it is rewritten to canonical LF bytes (oid and size unchanged) and a tracked path with `* text=auto` and no `-text` produces a warning in the held-files sheet.
- R15: *Given* a graph that syncs through the system-git path (ADR-012) or whose repo config has `filter.lfs.process` set and no builtin, *When* the effective mode is resolved, *Then* it is `GUARD_ONLY` and the service guard still applies (it works through the filesystem, independent of the adapter).
- R18: *Given* a tracked file with the exec bit, *When* converted and pushed, *Then* the pointer blob carries mode 100755 on JVM; on Android, where `disableFileModeTracking` is set, it is documented as not preserved.
**Files**: `{C}/git/lfs/LfsPointerRepair.kt`, `{C}/git/lfs/LfsModeResolver.kt`, `{BT}/git/lfs/LfsExecBitTest.kt`

##### Task E2.9a: LfsPointerRepair and text-attribute warning [S]
- Pure `LfsPointerRepair.normalise(bytes)` for `Corrupt("CRLF")` only (and BOM), applied by `LfsTransferService` before prefetch; `LfsPathPolicy.textAttr(path)` exposes `text` and `eol` so `LfsGuardCoordinator` can add a `TextAutoMayRewritePointers` note. Test `LfsPointerRepairTest` (commonTest). Register in `{BT}/AllBusinessTests.kt` and `business_common_tests.srcs`.
- Files: `{C}/git/lfs/LfsPointerRepair.kt`, `{C}/git/lfs/LfsPathPolicy.kt`, `{CT}/git/lfs/LfsPointerRepairTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E2.9b: Extend LfsModeResolver with R15 and R20 inputs [S]
- Extend the `LfsModeResolver` created in Task E1.4f with `adapter: SYSTEM_GIT|JGIT|WASM` and repo config flags: forces `GUARD_ONLY` for system-git sparse graphs and for repos with external `filter.lfs.process` that the builtin override could not replace; `GitSyncService` uses it everywhere `lfs_mode` is read. `LfsModeResolverTest` (commonTest) registered; `GitSyncServiceLfsGuardTest` gains `systemGitPathStillGuarded`.
- Files: `{C}/git/lfs/LfsModeResolver.kt`, `{CT}/git/lfs/LfsModeResolverTest.kt` (edited, registered in E1.4f), `{BT}/git/GitSyncServiceLfsGuardTest.kt`.

##### Task E2.9c: Exec bit test and R17, R19, R20 notes [S]
- `LfsExecBitTest`; `LfsRulesLoader` (Task E1.2c) passes `core.ignorecase` from repo config (JVM, Android) or false (web) as the matcher's `ignoreCase` (R17; also exercised by `GitAttributesDifferentialTest`); R19 force-push cache growth is accepted and deferred with the storage preflight counting the cache; R20 hook coexistence is decided by spike S5 (`git lfs` hook scripts are never modified by SteleKit).
- Files: `{BT}/git/lfs/LfsExecBitTest.kt`, `{BT}/AllBusinessTests.kt`, `{C}/git/lfs/LfsRulesLoader.kt`.

**Phase 2 gate G2**: Story E7.3 `LfsEndToEndTest` green under `bazel test //kmp:business_tests`; ADR-001 Accepted with spike evidence. Android is not declared supported until Story E7.5 records the device pass.

---

## Phase 3: Web (wasmJs) read and write

### Epic E3: Web read path: pointer detection, resolution, caching, lazy UI

**Goal**: LFS assets are viewable on web and on every platform's UI instead of rendering blank (requirements success metric 3, read half; W section 5; F section 2).

#### Story E3.1: Binary-safe resolution and OPFS cache
**As a** web user, **I want** an LFS image to load from the host's CORS-open URL and be cached by content hash, **so that** it works with no infrastructure (W section 7 table, read rows).
**Acceptance Criteria**:
- GitHub resolution via the media URL with verification.
  - *Given* a pointer `oid sha256:4d7a...2393 size 5242880` for `assets/p.png` on `tstapler/steno-wiki@main` and a `MockEngine` serving 5 MiB at `https://media.githubusercontent.com/media/tstapler/steno-wiki/main/assets/p.png`, *When* `WasmLfsReadResolver.resolve` runs, *Then* the request carries `Authorization`, the bytes' SHA-256 equals the oid, they are stored under `.stele-lfs/objects/4d/4d7a...2393`, and a blob URL is registered for `assets/p.png`.
- GitLab resolution via `raw?lfs=true`.
  - *Given* a GitLab host, *When* resolving, *Then* the request URL is `<apiBase>/repository/files/assets%2Fp.png/raw?ref=main&lfs=true` with the host's auth header.
- Hash mismatch is rejected.
  - *Given* the mock returns bytes with another hash, *When* resolving, *Then* the result is `LfsObjectIntegrityFailed(oid)` and nothing is stored.
- Size cap.
  - *Given* `maxObjectBytes=50 MiB` and a 200 MiB pointer, *When* `stateOf` runs, *Then* it is `Blocked(SizeCap)` and no request is made until the user chooses "download anyway".
- Token isolation.
  - *Given* a response redirecting object bytes to another host, *When* followed, *Then* `Authorization` is not sent to that host.
**Files**: `{W}/git/lfs/WasmLfsHostStrategy.kt`, `{W}/git/lfs/WasmLfsReadResolver.kt`, `{W}/git/lfs/WasmLfsObjectCache.kt`, `{W}/git/lfs/WasmOpfsLfsObjectStore.kt`, `{W}/git/WasmGitWriteService.kt`

##### Task E3.1a: Binary-safe fetch and host strategies [M]
- Add `fetchRemoteFileBytes(hostConfig, path, ref): Either<GitError, ByteArray>` beside `fetchRemoteFileContent` (`:346`) using `bodyAsBytes()` (the text path `fetchRawUrl` at `:363-375` stays). `LfsHostStrategy` sealed: `GitHubMedia` (`https://media.githubusercontent.com/media/<owner>/<repo>/<ref>/<path>`, with fallback to the Contents API `download_url` on 401 or 403, Q1), `GitLabRawLfs`, `DirectBatch` (uses `LfsBatchClient`), chosen by `GitHostAdapter` host detection. Pre-filter with `LfsPointerSniffer` using the Git tree blob size from the listing (no download for files of 1024 bytes or more).
- Files: `{W}/git/lfs/WasmLfsHostStrategy.kt`, `{W}/git/lfs/WasmLfsReadResolver.kt`, `{W}/git/WasmGitWriteService.kt`.

##### Task E3.1b: OPFS object cache [M]
- `WasmLfsObjectCache`: layout `.stele-lfs/objects/<aa>/<oid>` under the graph's OPFS root (outside the graph dir so it never enters the dirty set), content-addressed so never stale, SHA-256 verified before it becomes visible, total-bytes cap with LRU eviction, `navigator.storage.estimate()` consulted before large writes, blob URL registration through `registerBlobUrl` (`{W}/platform/PlatformFileSystem.kt:871`) so `resolveAssetUri` (`:872`) serves it.
- Files: `{W}/git/lfs/WasmLfsObjectCache.kt`, `{W}/platform/OpfsInterop.kt` (externals only if needed).

##### Task E3.1c: Streaming OPFS store [M]
- `WasmOpfsLfsObjectStore : LfsObjectStore` streams `bodyAsChannel` into a `FileSystemWritableFileStream` when `createWritable` exists (INFERRED availability; feature-detected), hashing as it writes; otherwise downloads into a `ByteArray` only below `maxObjectBytes` (default 50 MiB). Never decodes as text (P R8). Known limit, stated to users: web downloads have no `Range` resume (a dropped transfer restarts from zero; the 50 MiB default cap bounds the cost); a Direct or Proxy upload that fails after some objects were accepted leaves orphaned host objects that count against host storage.
- Files: `{W}/git/lfs/WasmOpfsLfsObjectStore.kt`, `{W}/platform/OpfsInterop.kt`.

##### Task E3.1d: Resolver and cache tests with CI wiring [M]
- `WasmLfsReadResolverTest` (the five acceptance cases, 401/404/429 mapping through `LfsErrorMapper`, 8 MB mock body to catch whole-file-as-String handling) and `WasmLfsObjectCacheTest` (verify-before-visible, LRU, cap, quota). Append both FQCNs to the `ci.yml` allow-list.
- Files: `{WT}/git/WasmLfsReadResolverTest.kt`, `{WT}/git/WasmLfsObjectCacheTest.kt`, `.github/workflows/ci.yml`.

##### Task E3.1e: Web LfsTreeSource for the first-sync survey [S]
- `WasmLfsTreeSource : LfsTreeSource` using the tree listing and the small-blob fetches to build `LfsRepoSurvey` (pointer count and summed size) for web clones. Test `WasmLfsTreeSourceTest`, appended to the allow-list.
- Files: `{W}/git/lfs/WasmLfsTreeSource.kt`, `{WT}/git/WasmLfsTreeSourceTest.kt`, `.github/workflows/ci.yml`.

#### Story E3.2: Coil fetcher and resolver port (all platforms)
**As a** user on any platform, **I want** images whose file is a pointer to resolve lazily, **so that** they display instead of rendering empty (F section 2 hook 1).
**Acceptance Criteria**:
- Pointer file resolves through the resolver.
  - *Given* `assets/p.png` is a 130-byte pointer on disk and a fake `LfsObjectResolver` returning `Ready("blob:abc")`, *When* Coil loads `../assets/p.png`, *Then* the fetcher returns the resolver's bytes, not the pointer text.
- A real image passes through untouched.
  - *Given* a 40 KB PNG, *When* loaded, *Then* the resolver is never called.
- A 1023-byte non-pointer file passes through.
  - *Given* 1023 bytes starting `hello`, *When* sniffed, *Then* it is treated as content.
**Files**: `{C}/git/lfs/LfsObjectResolver.kt`, `{C}/ui/components/LfsAssetFetcher.kt`, `{C}/ui/components/SteleKitAssetFetcher.kt`, `{J}/platform/PlatformFileSystem.kt`, `{W}/platform/PlatformFileSystem.kt`

##### Task E3.2a: LfsObjectResolver, LfsAssetState, fetcher, getFileSize overrides [M]
- `LfsObjectResolver` (`stateOf(pointer)`, `resolve(pointer)`, `states: Flow`), `LfsAssetState`, `UnavailableLfsObjectResolver` (default, used on iOS), `LocalLfsObjectResolver` CompositionLocal. `LfsAssetFetcher` (Coil `Fetcher`; sniffs only when `FileSystem.getFileSize(path)` is under 1024, then reads at most those bytes through `readFileBytes`). Register it in `rememberSteleKitImageLoader()` (`SteleKitAssetFetcher.kt:79-95`) next to `SteleKitAssetMapper`. Add `getFileSize` overrides to the JVM `PlatformFileSystem` (default at `FileSystem.kt:65` reads the whole file) and the wasm one; Android already overrides (`androidMain/.../platform/PlatformFileSystem.kt:750`).
- Files: `{C}/git/lfs/LfsObjectResolver.kt`, `{C}/ui/components/LfsAssetFetcher.kt`, `{C}/ui/components/SteleKitAssetFetcher.kt`, `{J}/platform/PlatformFileSystem.kt`, `{W}/platform/PlatformFileSystem.kt`.

##### Task E3.2b: JVM and Android local resolver [M]
- `LfsLocalObjectResolver` (jvmCommon): `Ready` when the object is in the store and the working file is smudged; on demand (policy `ON_DEMAND`) downloads through `LfsTransferService` then restores the single path from the index with `git.checkout().addPath(path)` (smudge is local) and, on Android, lets the flush actor write bytes to SAF (Task E2.5b); honours `androidLfsEnabled`. Provide it through the `LocalLfsObjectResolver` at the graph shell (`{C}/ui/GraphContentActiveShell.kt`, construction beside `GraphContentGitSyncSetup.kt:53-62`).
- Files: `{JC}/git/lfs/LfsLocalObjectResolver.kt`, `{C}/ui/GraphContentActiveShell.kt`, `{C}/ui/GraphContentGitSyncSetup.kt`.

##### Task E3.2c: Wasm resolver provider [S]
- `WasmLfsObjectResolver` wraps `WasmLfsReadResolver` plus cache and size-cap policy, exposed through `LocalLfsObjectResolver` in the wasm entry (provider placed where the graph shell is composed for wasm).
- Files: `{W}/git/lfs/WasmLfsObjectResolver.kt`, `{C}/ui/GraphContentActiveShell.kt`.

##### Task E3.2d: LfsLocalObjectResolver tests [S]
- `LfsLocalObjectResolverTest` (businessTest, real JGit plus `FakeLfsServer`): `Ready` when present, on-demand download then single-path checkout, caps respected, `Blocked(Quota)` mapping, checkout done inside the watcher bracket. Register.
- Files: `{BT}/git/lfs/LfsLocalObjectResolverTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E3.3: Placeholder UI for unresolved pointers
**As a** user, **I want** a clear "not downloaded" tile with a size and a download action, **so that** a missing LFS file is understandable (F section 3 messages).
**Acceptance Criteria**:
- *Given* `Pending(size=2.4 MB)`, *When* the placeholder renders, *Then* it shows "This file is stored with Git LFS and has not been downloaded. Tap to download (2.4 MB)."
- *Given* `Blocked(Quota)`, *Then* it shows "Your Git host's LFS quota is used up, so this file cannot be downloaded or uploaded until the quota resets or is raised. Your text notes still sync."
- *Given* `Blocked(ProxyNotConfigured)` on web, *Then* it shows "Downloading large files in the browser needs an LFS proxy. Add one in Sync settings, or use the desktop or Android app. Text notes sync normally."
- *Given* `Downloading(3 MB, 10 MB)`, *Then* a progress indicator reads 30 percent.
**Files**: `{C}/ui/components/LfsAssetPlaceholder.kt`, `{C}/ui/components/ImageBlock.kt`

##### Task E3.3a: LfsAssetPlaceholder composable [M]
- Composable per `LfsAssetState` (Pending with tap-to-download, Downloading with progress, Failed with reason and retry, Blocked with reason). Robolectric `LfsAssetPlaceholderTest` covers the four texts. Registered in `{AT}/AllAndroidUnitTests.kt`.
- Files: `{C}/ui/components/LfsAssetPlaceholder.kt`, `{AT}/ui/components/LfsAssetPlaceholderTest.kt`, `{AT}/AllAndroidUnitTests.kt`.

##### Task E3.3b: ImageBlock and lightbox wiring [M]
- `ImageBlock.kt:47-70` uses `AsyncImage` with no `error` or `placeholder` today; switch to `SubcomposeAsyncImage` with `loading` and `error` slots showing `LfsAssetPlaceholder`, same for the lightbox at `:92-103`; update the stale KDoc at `:40`.
- Files: `{C}/ui/components/ImageBlock.kt`.

#### Story E3.4: Assets browser, gallery and annotation surfaces
**As a** user, **I want** every place that shows an image to show the placeholder for an unresolved pointer, **so that** nothing is blank (F section 2 loader users).
**Acceptance Criteria**:
- *Given* an `assets/` folder with one pointer file, *When* the Assets browser grid renders, *Then* its card shows the placeholder with the size, not an empty thumbnail.
**Files**: `{C}/ui/assets/AssetItemCard.kt`, `{C}/ui/assets/AssetDetailScreen.kt`, `{C}/ui/gallery/GalleryScreen.kt`, `{C}/ui/components/ImageAnnotationBlockItem.kt`

##### Task E3.4a: Cards, detail, gallery [M]
- Wire `LfsAssetPlaceholder` through `AssetItemCard.kt` (`:73`, `:137`), `AssetDetailScreen.kt` (`:220`), `GalleryScreen.kt` (`:183`).
- Files: `{C}/ui/assets/AssetItemCard.kt`, `{C}/ui/assets/AssetDetailScreen.kt`, `{C}/ui/gallery/GalleryScreen.kt`.

##### Task E3.4b: Annotation surfaces [S]
- `ImageAnnotationBlockItem.kt:86` and the annotation editor image load (`AnnotationEditorScreen.kt:400`): placeholder for pointers and refuse to open the editor on an unresolved pointer.
- Files: `{C}/ui/components/ImageAnnotationBlockItem.kt`, `{C}/ui/annotate/AnnotationEditorScreen.kt`.

##### Task E3.4c: Fetcher and mapper tests [S]
- `LfsAssetFetcherTest` (businessTest, fake resolver, the three acceptance cases of E3.2), extend `SteleKitAssetMapperTest` (`{JT}/ui/components/SteleKitAssetMapperTest.kt`, existing) for pointer-backed paths, `UnavailableLfsObjectResolverTest` (iOS default returns `Blocked(PlatformUnsupported)`). Register in `{BT}/AllBusinessTests.kt`.
- `SteleKitAssetMapperTest` is not in `AllJvmTests` today: this task adds it (first-time registration of an existing class) and runs the jvm suite through `scripts/jvm-display-check.sh`.
- Files: `{BT}/ui/components/LfsAssetFetcherTest.kt`, `{JT}/ui/components/SteleKitAssetMapperTest.kt`, `{BT}/git/lfs/UnavailableLfsObjectResolverTest.kt`, `{BT}/AllBusinessTests.kt`, `{JT}/AllJvmTests.kt`.

##### Task E3.4d: Decode-size guard [S]
- `LfsDecodeBudget` (commonMain, pre-mortem N15): reads PNG and JPEG header dimensions from the first bytes, and returns `Blocked(TooLarge)` above a pixel budget (default 50 megapixels) before Coil decodes; used by `LfsAssetFetcher` and the web resolver. Tests `LfsDecodeBudgetTest` (commonTest, header parsing, budget) and `WasmLfsHighResImageTest` (real Chrome, a generated high-resolution PNG under the cap renders, one over it shows the placeholder), the latter appended to the ci.yml allow-list.
- Files: `{C}/git/lfs/LfsDecodeBudget.kt`, `{CT}/git/lfs/LfsDecodeBudgetTest.kt`, `{WT}/git/WasmLfsHighResImageTest.kt`, `.github/workflows/ci.yml`, `kmp/src/commonTest/kotlin/BUILD.bazel`.

##### Task E3.4e: Asset card placeholder test [S]
- Robolectric `AssetItemCardLfsTest` (a pointer-backed asset renders the placeholder with its size, not an empty thumbnail). Register in `{AT}/AllAndroidUnitTests.kt`.
- Files: `{AT}/ui/assets/AssetItemCardLfsTest.kt`, `{AT}/AllAndroidUnitTests.kt`.

### Epic E4: Web write: layered Direct, Proxy, Converter, ReadOnly

**Goal**: Web LFS writes via a real design with a precise cost statement (ADR-002; requirements success metric 3, write half).

#### Story E4.1: Runtime CORS capability probe
**As a** web user on Bitbucket or Azure DevOps, **I want** SteleKit to detect that my host allows browser LFS, **so that** writes work with no proxy.
**Acceptance Criteria**:
- *Given* a `MockEngine` answering the probe `POST .../objects/batch` with HTTP 401, *When* `WasmLfsCorsProbe.probe(host)` runs, *Then* it returns `DirectCapable` (a response arrived, so the preflight passed).
- *Given* the engine throwing a network `TypeError`, *Then* it returns `CorsBlocked` and caches it under `git_lfs_cors_probe_<host>` with the probe time.
- *Given* a cached result younger than one day, *Then* no request is made; after one day or after a CORS-shaped failure it re-probes.
**Files**: `{W}/git/lfs/WasmLfsCorsProbe.kt`, `{WT}/git/WasmLfsCorsProbeTest.kt`, `.github/workflows/ci.yml`

##### Task E4.1a: WasmLfsCorsProbe [S]
- Per ADR-002 probe rules; result type `DirectCapable | CorsBlocked | Unknown(error)`; hint table (Bitbucket, Azure DevOps) only orders the probe, never decides.
- Files: `{W}/git/lfs/WasmLfsCorsProbe.kt`.

##### Task E4.1b: Probe tests [S]
- `WasmLfsCorsProbeTest` (the three cases) appended to the `ci.yml` allow-list.
- Files: `{WT}/git/WasmLfsCorsProbeTest.kt`, `.github/workflows/ci.yml`.

##### Task E4.1c: CORS canary workflow [S]
- `.github/workflows/lfs-cors-canary.yml` (scheduled; only `curl`, no third-party actions): unauthenticated `OPTIONS` preflight against the four batch hosts' known endpoints and the media and GitLab raw URLs; fails and thereby notifies when a host's `Access-Control-Allow-*` result differs from the table in `research/web-lfs.md` section 1 (pre-mortem N14). Permissions `contents: read`.
- Files: `.github/workflows/lfs-cors-canary.yml`.

#### Story E4.2: Direct browser LFS write
**As a** web user on a CORS-enabled LFS host, **I want** a pasted image uploaded to LFS and a pointer committed, **so that** it syncs like on desktop.
**Acceptance Criteria**:
- *Given* mode `Direct`, a dirty `assets/p.png` of 3 MiB, *When* `commitGitHub` (or the GitLab-style path) runs, *Then* the order of requests is: batch `upload`, `PUT` object, optional `verify`, then `POST .../git/blobs` whose decoded content is the canonical pointer for the SHA-256 of the 3 MiB, then tree, commit and ref update.
- *Given* mode `Direct` or `Proxy` and a raw tracked file the writer converts, *Then* `LfsActionRequired` is NOT raised (the service coordinator is `Delegated` on web, Task E1.4d); *Given* mode `ReadOnly`, *Then* it is raised with "Writing large files from the browser needs a proxy or a host that allows it. Your text notes still sync." (tests `directModeConvertedFileRaisesNoActionRequired`, `readOnlyModeHeldWithExactMessage`).
- *Given* the object PUT fails with 500 after retries, *Then* no blob, tree, commit or ref request is made and `p.png` stays dirty.
- *Given* success, *Then* the local file at `assets/p.png` becomes the pointer without being marked dirty, the 3 MiB bytes sit in the cache under the oid, and the blob URL still resolves.
**Files**: `{W}/git/lfs/WasmLfsWriter.kt`, `{W}/git/WasmGitWriteService.kt`, `{W}/platform/PlatformFileSystem.kt`, `{WT}/git/WasmLfsDirectWriteTest.kt`

##### Task E4.2a: WasmLfsWriter [M]
- Streaming SHA-256 (okio) of the dirty bytes, `LfsBatchClient.batch(upload)`, PUT, `verify`; returns `path -> pointerBytes`. Respects `maxObjectBytes` (above it: `Blocked(SizeCap)` and held).
- Files: `{W}/git/lfs/WasmLfsWriter.kt`.

##### Task E4.2b: Wire commitGitHub [M]
- After the guard (E1.6b) and before `createBlobsBounded` (`:436`): for held-as-LFS entries when mode is `Direct` or `Proxy`, obtain pointer bytes from `WasmLfsWriter` and substitute them as the blob content, after confirming each referenced object exists on the host (a batch `download` probe; pre-mortem N12: pointer-without-object is never committed); a PUT-time CORS-shaped failure downgrades that host's cached probe result to `CorsBlocked` so the next push resolves to the next mode (N14); guard semantics unchanged (a path whose content is now a pointer passes `LfsGuard`).
- Files: `{W}/git/WasmGitWriteService.kt`.

##### Task E4.2c: Wire pushViaGitLab and local swap [M]
- `buildGitLabActions` takes a bytes reader lambda (the lambda argument of the `buildGitLabActions` call in `pushViaGitLab`; the function is at `:751`, the call at `:1000`); pass a reader returning pointer bytes for LFS paths. After a successful commit, replace each such OPFS file with the pointer using a non-dirty write (add `writeFileBytesClean` to the wasm `PlatformFileSystem`, no dirty marking), move bytes into `WasmLfsObjectCache`, re-register the blob URL.
- Files: `{W}/git/WasmGitWriteService.kt`, `{W}/platform/PlatformFileSystem.kt`.

##### Task E4.2d: Direct write tests [M]
- `WasmLfsDirectWriteTest` (the three acceptance cases, request order assertion, token only on batch). Appended to the allow-list.
- Files: `{WT}/git/WasmLfsDirectWriteTest.kt`, `.github/workflows/ci.yml`.

#### Story E4.3: Opt-in user-owned CORS proxy [CANCELLED by O1 = no proxy]
**As a** web user on github.com or gitlab.com, **I want** to point SteleKit at a CORS proxy I deploy myself, **so that** I can write LFS files from the browser (ADR-002 mode 2).
**Acceptance Criteria**:
- Requests go to the proxy, not the host.
  - *Given* `lfsProxyUrl=https://lfs.example.workers.dev` and a GitHub repo `tstapler/steno-wiki`, *When* a batch runs, *Then* the request is `POST https://lfs.example.workers.dev/...` with `X-Target: https://github.com/tstapler/steno-wiki.git/info/lfs/objects/batch` and no request goes to `github.com`.
- Health check gates enabling.
  - *Given* `GET <proxy>/__health` returning `{"ok":true,"allowedOrigin":"https://stapler.dev","version":1}` while `window.location.origin` is `https://stelekit.app`, *When* the user enables the proxy, *Then* it is refused with `LfsProxyUnavailable("origin mismatch")`.
- Token exposure needs explicit acknowledgment.
  - *Given* the proxy field is set, *When* the user has not ticked the acknowledgment, *Then* Save is disabled.
- Template rejects an unauthenticated or wrong-origin request.
  - *Given* the Worker receiving a request without `Authorization`, *Then* it returns 403 and forwards nothing (`worker.test.mjs`).
**Files**: `{C}/git/lfs/client/LfsProxyEndpoint.kt`, `{C}/ui/screens/git/LfsProxySettingsSection.kt`, `docs/reference/lfs-cors-proxy/worker.js`, `docs/reference/lfs-cors-proxy/README.md`

##### Task E4.3a: LfsProxyEndpoint [S]
- Pure mapping of a normal `LfsEndpoint` to proxy form (`X-Target`), pass-through of action hrefs that already point at the proxy origin (the template rewrites them). `LfsEndpointProxyTest` (commonTest) registered.
- Files: `{C}/git/lfs/client/LfsProxyEndpoint.kt`, `{CT}/git/lfs/LfsEndpointProxyTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E4.3b: Health check, acknowledgment store and settings UI [M]
- `LfsProxyHealthCheck` (the `__health` contract in ADR-002), acknowledgment key `git_lfs_proxy_ack_<graphId>`, `LfsProxySettingsSection` shown only on web and only when `.gitattributes` has LFS rules (warning copy: "The proxy operator can see your Git token. Only use a proxy you deploy yourself.").
- Files: `{W}/git/lfs/LfsProxyHealthCheck.kt`, `{C}/ui/screens/git/LfsProxySettingsSection.kt`, `{C}/ui/screens/git/LfsSettingsSection.kt`.

##### Task E4.3c: Worker template and README [M]
- `docs/reference/lfs-cors-proxy/worker.js` (module syntax, exports `handleRequest` for testing): Origin pinned to `env.ALLOWED_ORIGIN`; `Authorization` required; exact host allowlist (`env.HOSTS`), no IP literals or `localhost`; batch path regex; response rewriting of `actions.*.href` to `/obj/<base64url(href)>.<hmac>` signed with `env.HMAC_SECRET`; `/obj/...` only for valid signatures and only for hosts in `env.OBJECT_HOSTS`; `redirect: "manual"`; methods POST, GET, PUT, OPTIONS; body size cap; `GET /__health`; no header logging. `README.md`: deploy steps, variables, free-tier limits flagged "re-verify before relying" (W section 2), the token-exposure statement and cost. `wrangler.toml.example`.
- Files: `docs/reference/lfs-cors-proxy/worker.js`, `docs/reference/lfs-cors-proxy/README.md`, `docs/reference/lfs-cors-proxy/wrangler.toml.example`.

##### Task E4.3d: Worker unit tests [S]
- Token hygiene (N13): `README.md` and the settings copy recommend a fine-grained token limited to the one repo, warn that classic tokens are broader, require re-acknowledgment when the proxy URL changes, and the Worker never echoes `Authorization` (asserted in the tests below). CI (N16): a job step in `.github/workflows/ci.yml` runs `node --test docs/reference/lfs-cors-proxy/`; SteleKit refuses a proxy whose health `version` is below `MIN_PROXY_VERSION` with "update your Worker template".
- `docs/reference/lfs-cors-proxy/worker.test.mjs` runnable with `node --test` (no dependencies): origin check, missing `Authorization`, host allowlist and SSRF cases (`127.0.0.1`, `localhost`, `[::1]`, decimal IP), path regex, HMAC rewrite and verification, health. Also run in CI as above.
- Files: `docs/reference/lfs-cors-proxy/worker.test.mjs`, `.github/workflows/ci.yml`.

##### Task E4.3e: Proxy transport tests [M]
- `WasmLfsProxyTransportTest` (adds `proxyDownBeforePushFallsToReadOnly` and `proxyDiesMidUploadLeavesNoBlobOrRef`; requests only to the proxy, `X-Target` value, token only on batch, health mismatch refusal, acknowledgment gating via the settings logic). Appended to the allow-list.
- Files: `{WT}/git/WasmLfsProxyTransportTest.kt`, `.github/workflows/ci.yml`.

##### Task E4.3f: Proxy health check tests [S]
- `LfsProxyHealthCheckTest` (wasmJsTest): origin mismatch refused, version below `MIN_PROXY_VERSION` refused, `ok:false` refused, acknowledgment re-required on URL change. Appended to the allow-list.
- Files: `{WT}/git/LfsProxyHealthCheckTest.kt`, `.github/workflows/ci.yml`.

#### Story E4.4: Workflow converter (UNBLOCKED: O2 = YES, opt-in advanced mode; fully specified)
**As a** web user on github.com or gitlab.com with no proxy, **I want** an opt-in way to commit an LFS file through my own repo's CI, **so that** I can write without any infrastructure beyond my repo (ADR-002 mode 3; ADR-003 section 7).
**Acceptance Criteria**:
- Staging-ref scope is enforced.
  - *Given* `StagingRefScope`, *When* the target ref is `refs/heads/main`, `refs/heads/stelekit/lfs-inbox/../main` or `refs/heads/stelekit/lfs-inbox/abc 123`, *Then* `LfsStagingRefPolicy` rejects each; `refs/heads/stelekit/lfs-inbox/a1B_9-x` is accepted.
- End to end on GitHub (mocked).
  - *Given* mode `Converter` enabled and approved, a dirty `assets/p.png`, *When* the push runs, *Then* the request order is: Data API blob, tree, commit and ref create on `refs/heads/stelekit/lfs-inbox/<id>`, `POST .../actions/workflows/stelekit-lfs.yml/dispatches` with input `ref`, polling of the staging ref until it disappears, and the UI shows "Converting large file on GitHub (about 30 seconds)".
- Failure leaves the ref for the user.
  - *Given* the poll sees the workflow conclude `failure`, *Then* the ref is not deleted by the client and the message names the Actions run URL.
- Disabled by default.
  - *Given* `lfsConverterEnabled=false`, *Then* the resolver never returns `Converter` and the staging-ref exception code path is unreachable.
**Files**: `{C}/git/lfs/LfsStagingRefPolicy.kt`, `{W}/git/lfs/WasmLfsConverterClient.kt`, `{W}/git/lfs/WasmLfsConverterGitLab.kt`, `docs/reference/lfs-converter/stelekit-lfs.yml`, `{W}/git/lfs/WasmLfsConverterInstaller.kt`

##### Task E4.4a: LfsStagingRefPolicy and StagingRefScope [S]
- Pure policy accepting only `^refs/heads/stelekit/lfs-inbox/[A-Za-z0-9_-]+$`, never the configured branch; `LfsGuard` accepts a `StagingRefScope` (E1.4a hook). `LfsStagingRefPolicyTest` (property: any string not matching the pattern is rejected; any configured-branch ref is rejected) registered.
- Files: `{C}/git/lfs/LfsStagingRefPolicy.kt`, `{CT}/git/lfs/LfsStagingRefPolicyTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E4.4b: GitHub converter client [M]
- `WasmLfsConverterClient`: create staging ref commit via the Data API (reusing the `commitGitHub` machinery with a target-ref parameter), workflow dispatch (preflight VERIFIED `ACAO: *`, W section 1), poll `GET .../git/ref/heads/stelekit/lfs-inbox/<id>` plus workflow runs, cancel and cleanup, timeout.
- Files: `{W}/git/lfs/WasmLfsConverterClient.kt`, `{W}/git/WasmGitWriteService.kt`.

##### Task E4.4c: GitLab converter client [M]
- `WasmLfsConverterGitLab`: Commits API to a staging branch, `POST /projects/:id/pipeline` (preflight VERIFIED `ACAO: *`), pipeline status polling.
- Files: `{W}/git/lfs/WasmLfsConverterGitLab.kt`.

##### Task E4.4d: Workflow templates [S]
- `docs/reference/lfs-converter/stelekit-lfs.yml` (GitHub Actions: triggers `workflow_dispatch` with input `ref`; validates `ref` against the inbox pattern; `actions/checkout` with `lfs: true`; `git add --renormalize`; rebase onto the target branch; push with `GITHUB_TOKEN` `contents: write`; delete the staging ref; on rebase conflict fail and keep the ref; `concurrency` group), `gitlab-ci.stelekit-lfs.yml`, `README.md` stating the residual cost (raw blob unreachable until host GC).
- Files: `docs/reference/lfs-converter/stelekit-lfs.yml`, `docs/reference/lfs-converter/gitlab-ci.stelekit-lfs.yml`, `docs/reference/lfs-converter/README.md`.

##### Task E4.4e: Installer and token-scope check [S]
- `WasmLfsConverterInstaller`: check for the workflow file at the base ref; offer to commit it through the Data API (token needs `workflow` scope; GitHub fine-grained `actions:write`), report the missing-scope case as `LfsAuthFailed(readOnly=true)` with the scope hint.
- Files: `{W}/git/lfs/WasmLfsConverterInstaller.kt`.

##### Task E4.4f: Converter tests [M]
- `WasmLfsConverterTest` (the four acceptance cases with `MockEngine`, GitLab variant). Appended to the allow-list. Remains unbuilt if O2 is NO; the ADR-003 section 7 text then stays as a rejected-by-owner record.
- Files: `{WT}/git/WasmLfsConverterTest.kt`, `.github/workflows/ci.yml`.

#### Story E4.5: Write capability resolver and ReadOnly fallback
**As a** web user, **I want** SteleKit to pick the best write mode available and say plainly when none is, **so that** I am never surprised (ADR-002 precedence).
**Acceptance Criteria**:
- Precedence.
  - *Given* probe `DirectCapable` and a healthy proxy, *When* resolved, *Then* the mode is `Direct`; with probe `CorsBlocked` and a healthy proxy, `Proxy`; with neither and converter approved and enabled, `Converter`; with none, `ReadOnly`.
- ReadOnly holds files back with a clear message.
  - *Given* mode `ReadOnly` and a dirty `assets/p.png`, *When* sync runs, *Then* the file is held by the guard and the sheet shows "Writing large files from the browser needs a proxy or a host that allows it. Your text notes still sync."
**Files**: `{C}/git/lfs/LfsWriteCapabilityResolver.kt`, `{C}/ui/screens/git/LfsWebWriteStatus.kt`, `{CT}/git/lfs/LfsWriteCapabilityResolverTest.kt`

##### Task E4.5a: LfsWriteCapabilityResolver [S]
- Pure resolver and `LfsWriteMode` sealed type; property test `precedenceIsStable` and `readOnlyWhenNothingAvailable`. `LfsWriteCapabilityResolverTest` (commonTest) registered.
- Files: `{C}/git/lfs/LfsWriteCapabilityResolver.kt`, `{CT}/git/lfs/LfsWriteCapabilityResolverTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`.

##### Task E4.5b: Wire the resolver and status surface [M]
- `WasmGitWriteService` asks the resolver per push, and for mode `Proxy` runs `LfsProxyHealthCheck` once per push before any request; a failed check maps to `LfsProxyUnavailable` and the resolver falls to the next mode or `ReadOnly` (files held with the ReadOnly wording, nothing half-sent). It records the chosen mode in the log line and metric; `LfsWebWriteStatus` composable shows the current mode with its cost statement (ADR-002 consequences text); the held-files sheet (E1.8a) uses the ReadOnly wording.
- Files: `{W}/git/WasmGitWriteService.kt`, `{C}/ui/screens/git/LfsWebWriteStatus.kt`, `{C}/ui/components/git/LfsHeldFilesSheet.kt`.

##### Task E4.5c: Capability tests [S]
- `WasmLfsWriteCapabilityTest` (wasm integration of probe plus health plus settings into the resolver). Appended to the allow-list.
- Files: `{WT}/git/WasmLfsWriteCapabilityTest.kt`, `.github/workflows/ci.yml`.

#### Story E4.6: Web settings and cap UX
**As a** web user, **I want** an opt-in auto-download, a size cap and visible limits, **so that** my browser storage and data are not consumed unasked (F settings table).
**Acceptance Criteria**:
- *Given* E7.6 is not recorded, *When* `LfsWebWriteStatus` names a host as supported for direct writes, *Then* it shows "unverified" next to it.
- *Given* default web settings, *When* a pointer larger than 50 MiB is encountered, *Then* the placeholder shows `Blocked(SizeCap)` with "Download anyway".
- *Given* `autoDownload=ON_DEMAND`, *When* the page loads, *Then* no LFS request is made until a placeholder is tapped.
- *Given* the wasm `NetworkMonitor.isMetered()` is false, *Then* the wifi-only toggle is hidden on web with the note "not available in the browser".
**Files**: `{C}/ui/screens/git/LfsSettingsSection.kt`, `{C}/git/lfs/LfsDownloadPolicy.kt`

##### Task E4.6a: Web defaults and cap UI [S]
- Web defaults (`ON_DEMAND`, 50 MiB cap, no wifi-only), "Download anyway" action in the placeholder state machine, hide wifi-only on web. Test `LfsSettingsSectionWebDefaultsTest` (androidUnitTest Robolectric over the shared composable with web capabilities) registered in `{AT}/AllAndroidUnitTests.kt`.
- Files: `{C}/ui/screens/git/LfsSettingsSection.kt`, `{C}/git/lfs/LfsDownloadPolicy.kt`, `{C}/ui/components/LfsAssetPlaceholder.kt`, `{AT}/ui/screens/git/LfsSettingsSectionWebDefaultsTest.kt`, `{AT}/AllAndroidUnitTests.kt`.

#### Story E4.7: Live real-host test harness
**As a** maintainer, **I want** a credential-gated test that proves CORS and proxy behaviour on real hosts, **so that** the UNVERIFIED rows in `research/web-lfs.md` are closed.
**Acceptance Criteria**:
- *Given* no credentials in the environment, *When* the suite runs, *Then* `WasmLfsLiveTest` is skipped and CI is unaffected.
- *Given* a throwaway private repo and PAT supplied by the owner (O5), *When* run manually, *Then* it reports per-host: probe result, media URL access with PAT, direct upload result, proxy round trip.
**Files**: `{WT}/git/WasmLfsLiveTest.kt`

##### Task E4.7a: WasmLfsLiveTest [S]
- Mirror `WasmGitWriteServiceLiveTest.kt` (existing): skip without credentials; not on the CI allow-list.
- Files: `{WT}/git/WasmLfsLiveTest.kt`.

**Phase 3 gate G3**: all `Wasm*Lfs*` test classes above are on the `ci.yml` allow-list and green in headless Chrome (`./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests ...`, output shown); real-host checklist (Story E7.6) executed or its blocker recorded.

---

## Phase 4: iOS stance, docs, demo and site

### Epic E5: iOS: guard plus read-only placeholders (native LFS deferred)

**Goal**: iOS never commits a raw tracked binary and shows pointers as "not downloaded" placeholders rather than blank (F section 5; owner decision O4 confirms the stance). `IosGitRepository` is a stub: every method returns `NotSupported("iOS")` except `isGitRepo` and `hasDetachedHead` (`{I}/git/IosGitRepository.kt:1-67`, VERIFIED).

#### Story E5.1: iOS guard regression and placeholder default
**As a** maintainer, **I want** a test that fails if iOS git ever reaches commit without the guard, **so that** the future kgit2 implementation cannot regress the floor.
**Acceptance Criteria**:
- *Given* `IosGitRepository`, *When* `stageSubdir`, `commit` and `push` are called, *Then* each returns `NotSupported("iOS")`.
- *Given* a pointer file in an iOS-opened graph, *When* an image block renders, *Then* `UnavailableLfsObjectResolver` yields `Blocked(PlatformUnsupported)` and the placeholder reads "Open this graph on desktop or Android to download this file."
**Files**: `{IT}/git/IosGitRepositoryLfsGuardTest.kt`, `{I}/git/IosGitRepository.kt`, `{C}/ui/components/LfsAssetPlaceholder.kt`

##### Task E5.1a: Guard regression test [S]
- `IosGitRepositoryLfsGuardTest` in the iosTest source set (runs only on macOS runners; no registration list exists for iosTest). Asserts the three `NotSupported` results and, via reflection-free construction, that `stageSubdir` accepts `excludePaths`.
- Files: `{IT}/git/IosGitRepositoryLfsGuardTest.kt`.

##### Task E5.1b: KDoc contract and placeholder wording [XS]
- Add to `IosGitRepository` KDoc: any future implementation must call `LfsGuardCoordinator` before `stageSubdir` (ADR-003 section 2 table). Add the `PlatformUnsupported` wording to `LfsAssetPlaceholder`. Covered by `UnavailableLfsObjectResolverTest` and `LfsAssetPlaceholderTest` (already registered in E3).
- Files: `{I}/git/IosGitRepository.kt`, `{C}/ui/components/LfsAssetPlaceholder.kt`.

##### Task E5.1c: Defer native LFS in writing [XS]
- Record in ADR-001 (iOS item 6, already written) and in `docs/tasks/git-sync.md` (Task E6.1a) that native iOS LFS depends on iOS git shipping; no code.
- Files: `docs/tasks/git-sync.md`.

### Epic E6: Docs, demo and site

**Goal**: User and developer docs match the shipped behaviour, and the feature satisfies the PR #413 demo-help registry (git-sync is MARKETED there).

Verified paths: `docs/tasks/git-sync.md`, `docs/tasks/git-sync-ux.md`, `docs/ux/journey-map.md` (journey 7 "Git Sync Setup & Ongoing Sync" at `:215`), `docs/adr/ADR-015-wasm-git-data-api-write-back.md`, `site/src/content/docs/user/` (no git-sync page today: `all-pages, backlinks, block-editing, daily-notes, flashcards, getting-started, outliner, search, unlinked-references`), `site/astro.config.mjs` (sidebar at `:22-35`), demo pages at `kmp/src/commonMain/resources/demo-graph/pages/` (18 pages, no git page), generator task `generateDemoFileSystem` at `kmp/build.gradle.kts:560`. PR #413 branch `docs/demo-help-feature-registry-plan` (verified: `git ls-tree -r --name-only origin/docs/demo-help-feature-registry-plan | grep demo-help` lists 12 files under `project_plans/demo-help-feature-registry/`); read them with `git show origin/docs/demo-help-feature-registry-plan:project_plans/demo-help-feature-registry/<path>`. The registry code (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/featureregistry/SyncFeatures.kt`) does not exist on `feat/git-sync-branch-fix`; it is created by that plan's Task T-A1.1.3.

#### Story E6.1: Task docs, journey map and ADR-015 pointer
**As a** contributor, **I want** the task lists and journey to include LFS, **so that** the docs do not describe a product that no longer exists.
**Acceptance Criteria**:
- *Given* the updated `docs/ux/journey-map.md`, *When* journey 7 is read, *Then* it contains the LFS review step, the held-files state and the web write modes.
- *Given* ADR-015, *Then* it carries the line "Qualified for LFS-tracked paths by project_plans/git-lfs-support/decisions/ADR-002-web-lfs-write-strategy.md" and nothing else in it changed.
**Files**: `docs/tasks/git-sync.md`, `docs/tasks/git-sync-ux.md`, `docs/ux/journey-map.md`, `docs/adr/ADR-015-wasm-git-data-api-write-back.md`

##### Task E6.1a: Task lists [S]
- Add an "LFS" section to `docs/tasks/git-sync.md` (checkbox per epic, iOS deferral) and UX items to `docs/tasks/git-sync-ux.md` (review sheet, held-files sheet, placeholder states, proxy warning copy).
- Files: `docs/tasks/git-sync.md`, `docs/tasks/git-sync-ux.md`.

##### Task E6.1b: Journey map [S]
- Extend journey 7 (`docs/ux/journey-map.md:215`) with the LFS review step, held-files state, web write modes and error copy; do not add a new `docs/journeys/*.md` (those carry frontmatter checked by `JourneyDocsFrontmatterTest` and step counts by `JourneyStepCountRubricTest`).
- Files: `docs/ux/journey-map.md`.

##### Task E6.1c: ADR-015 pointer and ADR promotion [XS]
- Append the one-line qualification to `docs/adr/ADR-015-wasm-git-data-api-write-back.md`. After owner acceptance copy ADR-002 to `docs/adr/` using the next free number (`ls docs/adr`; ADR-018 is absent and ADR-019 is the highest today, so verify before choosing).
- Files: `docs/adr/ADR-015-wasm-git-data-api-write-back.md`.

#### Story E6.2: Website page
**As a** visitor, **I want** a git-sync page with an LFS section, **so that** I know what works on which platform and what a proxy costs.
**Acceptance Criteria**:
- *Given* ADR-002 is not yet Accepted (Story E7.6 not recorded), *When* the page lists hosts, *Then* every web-write host claim carries an "UNVERIFIED" label and the orphaned-object and no-resume limits are stated.
- *Given* the built site, *When* `/user/git-sync` is opened, *Then* it renders with the sidebar entry "Git Sync", contains the capability table (no setup: reading, and writing on hosts that allow it; one user-owned proxy: writing on GitHub and GitLab; never possible: writing from the browser with no proxy and no workflow) and links the proxy template.
**Files**: `site/src/content/docs/user/git-sync.mdx`, `site/astro.config.mjs`

##### Task E6.2a: git-sync.mdx and sidebar [M]
- New page with setup, hold-back behaviour, LFS section with the capability table from ADR-002 and the per-platform support matrix (JVM full, Android full after the flag flips, web per mode, iOS guard and placeholders). Sidebar entry in `site/astro.config.mjs` next to `user/search`. Build check: `cd site && npm run build` output shown.
- Files: `site/src/content/docs/user/git-sync.mdx`, `site/astro.config.mjs`.

#### Story E6.3: README and install docs
**As a** reader of the README, **I want** the features list to mention LFS, **so that** it is discoverable.
**Acceptance Criteria**:
- *Given* `README.md`, *Then* the features list mentions Git LFS support in one line, and `docs/install.md` is unchanged because `git lfs` is not a prerequisite on JVM or Android (JGit LFS is used; `scripts/generate-readme.sh` only inlines `docs/install.md` between `BEGIN_INSTALL` and `END_INSTALL`, so no generator run changes the features list).
**Files**: `README.md`

##### Task E6.3a: README line [XS]
- Hand-edit the features list; run `bash scripts/generate-readme.sh && git diff --exit-code README.md` to confirm the generator leaves it as written.
- Files: `README.md`.

#### Story E6.4: Demo page and feature registry (PR #413)
**As a** maintainer of the demo-help registry, **I want** LFS accounted for, **so that** the registry gate passes for a MARKETED git-sync feature.
**Acceptance Criteria**:
- *Given* the demo page, *When* `:kmp:generateDemoFileSystem` runs, *Then* `DemoFileSystem.kt` includes `Git LFS.md` and `git diff` shows the regenerated file.
- *Given* PR #413's registry merged, *When* `SyncFeatures.kt` is read, *Then* it has a `git-lfs` entry (or a dated `PENDING_DEMO_WITH_ISSUE` exemption) whose `covers` includes `sync:LfsTransferring` and `sync:LfsActionRequired`, and the `SyncState` candidate count in that plan's baseline is updated from 14 to 16.
**Files**: `kmp/src/commonMain/resources/demo-graph/pages/Git LFS.md`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/featureregistry/SyncFeatures.kt`

##### Task E6.4a: Demo page and regeneration [S]
- Write `Git LFS.md` in the demo graph style (what LFS is, the three placeholder states, held files, platform table); run `./gradlew :kmp:generateDemoFileSystem` and commit the regenerated `DemoFileSystem.kt` (header says generated, do not edit).
- Files: `kmp/src/commonMain/resources/demo-graph/pages/Git LFS.md`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt`.

##### Task E6.4b: Registry entry (blocked on PR #413 landing, O7) [S]
- In `SyncFeatures.kt` (created by that plan) add `FeatureEntry(id = FeatureId("git-lfs"), kind = FeatureKind.SYSTEM, audience = MARKETED /* inherits git-sync per its D1, resolved 2026-10-11 */, covers = setOf("sync:LfsTransferring", "sync:LfsActionRequired", ...), demoPage = "Git LFS", sitePage = "git-sync", sources = listOf(...))`; if the demo or site page is not ready, a dated `Exemption(PENDING_DEMO_WITH_ISSUE, ...)`. Update the baseline recount (that plan's T-A1.1.6). Test `LfsFeatureRegistryEntryTest` (businessTest) asserts the `git-lfs` entry exists, its `covers` includes both new `SyncState` candidates, and its demo and site pages exist; registered.
- Files: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/featureregistry/SyncFeatures.kt`, `{BT}/git/lfs/LfsFeatureRegistryEntryTest.kt`, `{BT}/AllBusinessTests.kt`.

---

## Phase 5: Close-out and cross-cutting test infrastructure

### Epic E7: Test fixtures, registration and manual validation

**Goal**: Every behaviour above is provable, runs in CI, and the parts CI cannot prove (real-host CORS, Android device) have an owner-run checklist. Stories E7.1 and E7.2 are prerequisites of Phase 2 and are executed first among E7.

#### Story E7.1: FakeLfsServer (in-process, no new dependencies)
**As a** test author, **I want** a spec-faithful LFS server inside the test JVM, **so that** clients are exercised over real HTTP (P section 1.1).
**Acceptance Criteria**:
- *Given* `FakeLfsServer.start()`, *When* a batch `download` for a stored 1 MiB object is POSTed with `Content-Type: application/vnd.git-lfs+json`, *Then* the response is `{transfer:"basic", objects:[{oid,size,actions:{download:{href,header}}}]}` and the GET of `href` returns the bytes.
- *Given* a PUT whose body SHA-256 differs from the oid, *Then* it returns 422 and nothing is stored.
- *Given* `requireAuth=true` and a missing header, *Then* the batch returns 401 with `LFS-Authenticate`.
- *Given* knobs `fail(status, times)`, `retryAfter`, `expiresIn`, `throttle`, *Then* each behaves as configured, and `requests()` returns method, path, headers and body size for every call.
**Files**: `{BT}/git/testsupport/FakeLfsServer.kt`, `{BT}/git/testsupport/FakeLfsServerSelfTest.kt`, `{BT}/AllBusinessTests.kt`

##### Task E7.1a: FakeLfsServer [M]
- `com.sun.net.httpserver.HttpServer` on an ephemeral port; endpoints `POST /<repo>.git/info/lfs/objects/batch`, `GET|PUT /objects/<oid>`, `POST /verify`; omit `actions` for an upload of an object it already has; per-object `error{code:404}` for missing downloads; recording of every request; knobs listed above; helpers `seed(oid, bytes)` and `received()`; never sees a body whose SHA-256 is not its oid is asserted by `neverReceivedRawBinary()`.
- Files: `{BT}/git/testsupport/FakeLfsServer.kt`.

##### Task E7.1b: Self test [S]
- `FakeLfsServerSelfTest` (acceptance cases). Register in `{BT}/AllBusinessTests.kt`. Optional higher-fidelity layer is Task E7.3b.
- Files: `{BT}/git/testsupport/FakeLfsServerSelfTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E7.1c: Bazel smoke for jdk.httpserver [S]
- Run `FakeLfsServerSelfTest` under `bazel test //kmp:business_tests` (wrapped in `timeout 30m`, then `bazel shutdown`); if the test JDK lacks `jdk.httpserver`, add `jvm_flags = ["--add-modules=jdk.httpserver"]` to `business_tests` in `kmp/src/businessTest/kotlin/BUILD.bazel`. Stated gap: androidUnitTest uses Ktor `MockEngine`, so no Android test exercises real JGit-to-server HTTP; the JVM suite is the proof for the shared client and filters.
- Files: `kmp/src/businessTest/kotlin/BUILD.bazel`.

#### Story E7.2: LFS bare-origin fixtures
**As a** test author, **I want** a bare git origin plus the fake LFS server wired through `lfs.url`, **so that** real JGit LFS tests run with no network (P section 1.1: `file://` remotes route JGit's own discovery to SSH, so `lfs.url` must be set).
**Acceptance Criteria**:
- *Given* `LfsBareOriginFixtures.create(prefix, branch, lfsFiles = mapOf("assets/a.bin" to 3 MiB))`, *Then* the bare origin's `assets/a.bin` blob is a canonical pointer built by our commonMain serializer, `FakeLfsServer` holds the object, and a clone with `lfs.url` set resolves it.
**Files**: `{BT}/git/testsupport/LfsBareOriginFixtures.kt`

##### Task E7.2a: LfsBareOriginFixtures [M]
- Modelled on `BareOriginFixtures` (`{JT}/git/testsupport/BareOriginFixtures.kt`, jvmTest-only today, so this is a businessTest-side sibling so tests run in `//kmp:business_tests` without a display): bare origin, `.gitattributes` seeding, pointer commits built with `LfsPointerCodec` (not JGit's) plus object upload to the fake, `configureClone(git, server)` setting `lfs.url`. No `@Test` classes (fixture only).
- Files: `{BT}/git/testsupport/LfsBareOriginFixtures.kt`.

#### Story E7.3: End-to-end and interop
**As a** maintainer, **I want** one cross-component test per platform proving the whole flow and one test proving the fake equals real `git-lfs`, **so that** green unit tests cannot hide an integration gap.
**Acceptance Criteria**:
- *Given* an LFS origin and the fake server, *When* `LfsEndToEndTest` clones (3 MB pointer materialised), edits the file, runs `GitSyncService.sync` (commit + push), *Then* the origin has a new pointer blob whose oid matches the server's stored object and no blob at a tracked path is non-pointer.
- *Given* `git lfs` on PATH, *When* `LfsGitLfsInteropTest` runs `git lfs pull` and `git lfs push` against the same fake, *Then* both succeed; the test is skipped (`Assume`) when the binary is absent.
**Files**: `{BT}/git/lfs/LfsEndToEndTest.kt`, `{BT}/git/lfs/LfsGitLfsInteropTest.kt`, `{BT}/AllBusinessTests.kt`

##### Task E7.3a: LfsEndToEndTest [M]
- Full `GitSyncService` plus `JvmGitRepository` plus `LfsTransferService` plus fake server; a second scenario with `lfs_mode=GUARD_ONLY` asserting nothing transfers and a raw file is held; a third with an over-cap object deferred per `LfsDownloadPolicy`. Register.
- Files: `{BT}/git/lfs/LfsEndToEndTest.kt`, `{BT}/AllBusinessTests.kt`.

##### Task E7.3b: LfsGitLfsInteropTest [S]
- Tagged by `Assume.assumeTrue(gitLfsOnPath())`; runs the real binary against `FakeLfsServer` to prove spec-fidelity. Register.
- Files: `{BT}/git/lfs/LfsGitLfsInteropTest.kt`, `{BT}/AllBusinessTests.kt`.

#### Story E7.4: Registration and wiring audit
**As a** maintainer, **I want** proof that every new test class actually runs in CI, **so that** none silently never runs (the `AllBusinessTests.kt` header documents the history of that failure).
**Acceptance Criteria**:
- *Given* all LFS work complete, *When* `bazel test //kmp:business_tests` runs, *Then* `AllBusinessTestsCompletenessTest` passes and the run's test count includes every businessTest and registered commonTest class named in the Test Registry appendix.
- *Given* the wasm allow-list, *When* the allow-list is inspected, *Then* the count of `--tests` lines containing `Lfs` (`grep -c -- '--tests.*Lfs' .github/workflows/ci.yml`) equals the number of wasm LFS test classes in the Test Registry minus `WasmLfsLiveTest` (deliberately excluded) and minus `WasmLfsConverterTest` if owner decision O2 is NO.
**Files**: `.github/workflows/ci.yml`, `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`, `kmp/src/businessTest/kotlin/BUILD.bazel`

##### Task E7.4a: Audit commonTest srcs and suite entries [S]
- Add every commonTest LFS file still missing from `business_common_tests.srcs` (all LFS commonTest classes in the Test Registry, including `LfsStoragePreflightTest`, `LfsDownloadPolicyTest`, `LfsSettingsStoreTest`, `LfsBandwidthLedgerTest`, `LfsDecodeBudgetTest`) and its entry in `AllBusinessTests`; run `bazel test //kmp:business_tests` (wrapped in `timeout 30m`, then `bazel shutdown`), show output.
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `{BT}/AllBusinessTests.kt`, `kmp/src/businessTest/kotlin/BUILD.bazel`.

##### Task E7.4b: Audit the wasm allow-list and Gradle run [S]
- Verify every Task above that says "appended to the allow-list" did so (`grep -n "Lfs" .github/workflows/ci.yml`), run `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests ...` for them (run `./scripts/fix-wasm-karma-tooling.sh` once if Karma fails, per CLAUDE.md), show output; the comment above the step names the new suite.
- Files: `.github/workflows/ci.yml`.

##### Task E7.4c: Mechanical registration audit [S]
- `scripts/check-lfs-test-registration.sh` (pre-mortem N2, validation V5; replaces the idea of an in-JVM audit test, which could not read repo files under Bazel): lists every LFS test class named in the Test Registry appendix and fails unless it is present in the matching suite or list: commonTest and businessTest classes in `{BT}/AllBusinessTests.kt` (commonTest also in `business_common_tests.srcs`), `{JT}/AllJvmTests.kt`, `{AT}/AllAndroidUnitTests.kt`, and wasm classes in the `ci.yml` allow-list (except `WasmLfsLiveTest`). Run as a step in `.github/workflows/ci.yml`.
- Files: `scripts/check-lfs-test-registration.sh`, `.github/workflows/ci.yml`.

#### Story E7.5: Android device pass (wall-clock blocker)
**As the** owner, **I want** the Android LFS path verified on a real device before the flag flips, **so that** SAF behaviour that Robolectric cannot model is proven.
**Acceptance Criteria**:
- *Given* a device with a SAF-backed graph and a repo with a 5 MB tracked PNG, *When* the Android checklist (appendix) is run with `androidLfsEnabled=true`, *Then* every row is recorded pass with date and device model, after which the flag default flips in Task E7.5a.
**Files**: `{A}/git/AndroidGitRepository.kt`, `project_plans/git-lfs-support/decisions/ADR-001-jgit-lfs-vs-alternatives.md`

##### Task E7.5a: Flip androidLfsEnabled and record [XS]
- After the owner records the device pass in ADR-001 ("Spike results" Android row), change the default of `androidLfsEnabled` to true in `AndroidGitRepository.kt`. Blocked on the owner running the checklist.
- Files: `{A}/git/AndroidGitRepository.kt`, `project_plans/git-lfs-support/decisions/ADR-001-jgit-lfs-vs-alternatives.md`.

#### Story E7.6: Real-host CORS and proxy validation (wall-clock blocker)
**As the** owner, **I want** the UNVERIFIED rows of `research/web-lfs.md` closed with a throwaway repo and PAT, **so that** ADR-002 can be Accepted and user docs only claim what was observed.
**Acceptance Criteria**:
- *Given* O5 inputs, *When* the checklist (appendix) is executed, *Then* every row records observed behaviour with date, and `research/web-lfs.md` open items (object-storage CORS for action hrefs, private media access with PAT, GitLab `lfs=true` end to end, Cloudflare Worker body limits, Giftless CORS, OPFS quotas) each get a result or an explicit "still unknown".
**Files**: `project_plans/git-lfs-support/research/web-lfs.md`, `project_plans/git-lfs-support/decisions/ADR-002-web-lfs-write-strategy.md`

##### Task E7.6a: Execute checklist and record [S]
- Owner supplies repos and PAT (O5) and deploys the template Worker (E4.3c) to their own account; the agent runs `WasmLfsLiveTest` plus manual browser steps and appends results to `research/web-lfs.md` and ADR-002 (flip to Accepted or revise). Also captures the real quota-exceeded response body if a throwaway over-quota repo is available (Q2).
- Files: `project_plans/git-lfs-support/research/web-lfs.md`, `project_plans/git-lfs-support/decisions/ADR-002-web-lfs-write-strategy.md`.

---

## Error mapping table (shared `LfsErrorMapper`, JVM, Android and web)

| Signal | Domain error | Retry | User text (one line) |
|---|---|---|---|
| 401 on batch | `LfsAuthFailed(readOnly=false)` (routes to existing credential-expired UX) | After re-auth | "Git LFS rejected your credentials. Reconnect your account to download or upload large files." |
| 403 on upload (read-only token) | `LfsAuthFailed(readOnly=true)` | No | "Your token can read but not write LFS files." |
| 403 or 509 with quota or bandwidth wording; 507 | `LfsQuotaExceeded` | No (quota resets monthly on GitHub, F section 1) | "Your Git host's LFS quota is used up, so this file cannot be downloaded or uploaded until the quota resets or is raised. Your text notes still sync." |
| 404 on batch (repo) | `FetchFailed("LFS repository not found")` | No | "The Git host cannot find this repository's LFS storage." |
| Per-object 404 or 410 | `LfsObjectMissing(oid)` | No | "The Git host no longer has this file (it was removed or never uploaded). Ask whoever pushed it to push it again." |
| 422 | `LfsPointerCorrupt` | No | "Git LFS rejected this file's pointer as invalid." |
| 429 | `RateLimited(retryAfter)` | Yes, honouring `Retry-After` | "Rate limited by GitHub/GitLab, retrying automatically" |
| 413 | Halve the batch once, then `PushFailed("LFS request too large")` | Once | "The Git host refused a request as too large." |
| 5xx, network failure | `NetworkFailure` or `RetryExhausted` | Yes, backoff | existing messages |
| Hash or size mismatch on download | `LfsObjectIntegrityFailed(oid)` | Re-download once | "A downloaded file did not match its checksum and was discarded." |
| Not enough disk | `LfsStorageInsufficient` | No | "Not enough free space for the large files in this repository." |
| Proxy health check fails | `LfsProxyUnavailable(detail)` | No | "The LFS proxy did not answer correctly: <detail>." |
Quota detection is by status code first and a case-insensitive substring second; the real bodies are a GAP (Q2), so the text never repeats host wording.

## Settings and UX summary

| Setting | Where | Default JVM / Android / Web / iOS |
|---|---|---|
| `lfs_mode` (OFF, GUARD_ONLY, AUTO) | `LfsSettingsSection` (Step 4 after LFS detection; per-graph git settings) | AUTO / GUARD_ONLY until `androidLfsEnabled` / GUARD_ONLY / GUARD_ONLY |
| `lfsAutoDownload` (NEVER, ON_DEMAND, ALL) | same | ALL / ON_DEMAND / ON_DEMAND / n/a |
| `lfsMaxObjectBytes`, `lfsMaxSyncBytes` | same | none / 25 MiB and 200 MiB / 50 MiB per object / n/a (proposal, O3) |
| `lfsWifiOnly` | same; uses `NetworkMonitor.isMetered()` (new, Task E2.4b) | false / true / hidden ("not available in the browser") / n/a |
| `lfsProxyUrl`, acknowledgment | web only; shown only when `.gitattributes` has LFS rules | empty |
| `lfsConverterEnabled` | web only; exists only if O2 is YES | false |
First-sync review (Story E2.4): after clone or first fetch, detect `filter=lfs` rules, show tracked patterns, pointer count, summed size, legacy-raw count and the host quota note, with choices "Download all now", "On demand", "Don't download (placeholders only)"; Automatic sync stays `FirstSyncReviewPending` until chosen.

## Pitfall to task mapping (research/pitfalls.md R1 to R22)

| Risk | Disposition | Task or reason |
|---|---|---|
| R1 no https auth, half-failed push | Closed | E2.2c, E2.3e, E2.3g, E2.3i `LfsPushOrderingTest`, E2.3k `LfsModeSwitchPushTest` |
| R2 Android text-only shadow | Closed (flag off until device pass) | E2.5a to E2.5e, E7.5 |
| R3 raw binary committed | Closed | E1.4, E1.5, E1.6, ADR-003 |
| R4 pointer parsed as page | Closed | E1.7a |
| R5 legacy raw history | Closed | E2.4g `LfsLegacyRawDetectorTest` |
| R6 autocrlf | Detect, repair, warn | E1.1 `Corrupt(CRLF)`, E2.9a |
| R7 disk space | Closed | E2.3d, E2.5c |
| R8 web memory | Closed with limits | E3.1c, E4.6a |
| R9 pointer merge conflicts | Closed | E2.6 |
| R10 missing or partial objects | Closed | E2.2c verify and `.part`, E2.3b, E3.3 |
| R11 shallow clone | Closed | E2.3i `LfsShallowPushTest` |
| R12 token exposure | Closed | E2.2c token only on batch, E2.3e audits |
| R13 quota and rate limits | Closed (status-code based, Q2) | E2.2d |
| R14 watcher | Closed | E2.7 |
| R15 sparse and system git | Forced `GUARD_ONLY` | E2.9b |
| R16 stale attributes | Closed (per-operation) | E1.2c, E1.4d |
| R17 case-insensitive paths | Closed | E1.2d, E1.2e, E2.9c |
| R18 exec bit | Accepted, one test | E2.9c `LfsExecBitTest` |
| R19 force-push cache growth | Accepted and deferred | E2.3d preflight counts cache; "clear cache" action is a follow-up |
| R20 installed git-lfs hooks | Decided by spike | E2.1g S5, Q5 |
| R21 asset display | Closed | E3.2 to E3.4 |
| R22 web CORS | Closed by design, validated manually | ADR-002, E4, E7.6 |

## Requirements traceability

| Requirement (success metric) | Proving tests |
|---|---|
| 1 Safety floor on every platform | `GitSyncServiceLfsGuardTest`, `LfsRawBinaryNeverCommittedTest`, `LfsIndexVerifierTest`, `WasmGitWriteServiceLfsGuardTest`, `LfsGuardPropertyTest`, `IosGitRepositoryLfsGuardTest` |
| 2 JVM and Android clone, pull, commit, push against a local server | `JvmGitRepositoryLfsCloneTest`, `JvmGitRepositoryLfsPushTest`, `LfsPushOrderingTest`, `LfsEndToEndTest`, `AndroidGitRepositoryLfsTest` |
| 3 Web read and write with a precise cost statement | `WasmLfsReadResolverTest`, `WasmLfsDirectWriteTest`, `WasmLfsProxyTransportTest`, `WasmLfsConverterTest`, `WasmLfsWriteCapabilityTest`, E7.6 checklist |
| 4 Pointer parse and serialise property-tested | `LfsPointerPropertyTest`, `LfsPointerDifferentialTest` |

## Effort estimate

| Story | Tasks | Cost classes | Est. raw tokens | Assumed output share | Est. CU (x1.75 verification) | Wall-clock blocker |
|---|---|---|---|---|---|---|
| E1.1 Spec-conformant LfsPointer model, parser and serialiser | 2 | Sx1 Mx1 | 185k | 12% | 479k | - |
| E1.2 Pure-Kotlin .gitattributes matcher and LFS path policy | 6 | Sx3 Mx3 | 555k | 12% | 1437k | - |
| E1.3 New DomainError.GitError variants and both message maps | 1 | Sx1 | 55k | 12% | 142k | - |
| E1.4 LfsGuard and GitSyncService wiring with hold-back | 7 | Sx3 Mx4 | 685k | 12% | 1774k | - |
| E1.5 JVM and Android repository backstop and hold-back implementation | 5 | Sx1 Mx4 | 575k | 12% | 1489k | - |
| E1.6 Web guard on every write entry | 3 | Mx3 | 390k | 12% | 1010k | - |
| E1.7 Pointer files are never parsed or rewritten as pages | 1 | Sx1 | 55k | 12% | 142k | - |
| E1.8 Held-files UI | 1 | Mx1 | 130k | 12% | 336k | - |
| E2.1 Spikes S1 to S5 with explicit go/no-go (runs first) | 7 | XSx1 Sx2 Mx4 | 650k | 8% | 1501k | Spike S3 real-host SSH row optional |
| E2.2 commonMain LFS client (ADR-004) | 6 | Sx4 Mx2 | 480k | 12% | 1243k | - |
| E2.3 JVM adapter: store, scanner, transfer service, repository wiring | 14 | Sx7 Mx7 | 1295k | 12% | 3354k | - |
| E2.4 Settings, download policy, metered networks, first-sync review | 9 | Sx6 Mx3 | 720k | 12% | 1864k | O3 defaults confirmation |
| E2.5 Android: binary-safe shadow path, storage guard, wiring (flag off until device pass) | 5 | Sx1 Mx4 | 575k | 12% | 1489k | Android device pass (E7.5), owner |
| E2.6 Pointer conflicts are object-level, never text merges | 2 | Sx1 Mx1 | 185k | 12% | 479k | - |
| E2.7 Watcher and self-write suppression for LFS materialisation | 2 | Sx1 Mx1 | 185k | 12% | 479k | - |
| E2.8 Bazel and Gradle graduation (after GO) | 4 | Sx4 | 220k | 12% | 569k | Bazel pin needs the 16 GB CI-class machine (MODULE.bazel:149-158) |
| E2.9 Pitfall closures (R6, R15, R17 to R20) | 3 | Sx3 | 165k | 12% | 427k | - |
| E3.1 Binary-safe resolution and OPFS cache | 5 | Sx1 Mx4 | 575k | 12% | 1489k | - |
| E3.2 Coil fetcher and resolver port (all platforms) | 4 | Sx2 Mx2 | 370k | 12% | 958k | - |
| E3.3 Placeholder UI for unresolved pointers | 2 | Mx2 | 260k | 12% | 673k | - |
| E3.4 Assets browser, gallery and annotation surfaces | 5 | Sx4 Mx1 | 350k | 12% | 906k | - |
| E4.1 Runtime CORS capability probe | 3 | Sx3 | 165k | 12% | 427k | - |
| E4.2 Direct browser LFS write | 4 | Mx4 | 520k | 12% | 1346k | - |
| E4.3 Opt-in user-owned CORS proxy | 6 | Sx3 Mx3 | 555k | 12% | 1437k | Owner deploys own Worker (E7.6) |
| E4.4 Workflow converter (BLOCKED on owner decision O2; fully specified) | 6 | Sx3 Mx3 | 555k | 12% | 1437k | Owner decision O2 |
| E4.5 Write capability resolver and ReadOnly fallback | 3 | Sx2 Mx1 | 240k | 12% | 621k | - |
| E4.6 Web settings and cap UX | 1 | Sx1 | 55k | 12% | 142k | - |
| E4.7 Live real-host test harness | 1 | Sx1 | 55k | 12% | 142k | O5 repos and PATs (owner) |
| E5.1 iOS guard regression and placeholder default | 3 | XSx2 Sx1 | 95k | 12% | 246k | - |
| E6.1 Task docs, journey map and ADR-015 pointer | 3 | XSx1 Sx2 | 130k | 20% | 409k | - |
| E6.2 Website page | 1 | Mx1 | 130k | 20% | 409k | - |
| E6.3 README and install docs | 1 | XSx1 | 20k | 20% | 63k | - |
| E6.4 Demo page and feature registry (PR #413) | 2 | Sx2 | 110k | 20% | 346k | PR #413 merge (O7) for E6.4b |
| E7.1 FakeLfsServer (in-process, no new dependencies) | 3 | Sx2 Mx1 | 240k | 12% | 621k | - |
| E7.2 LFS bare-origin fixtures | 1 | Mx1 | 130k | 12% | 336k | - |
| E7.3 End-to-end and interop | 2 | Sx1 Mx1 | 185k | 12% | 479k | - |
| E7.4 Registration and wiring audit | 3 | Sx3 | 165k | 12% | 427k | - |
| E7.5 Android device pass (wall-clock blocker) | 1 | XSx1 | 20k | 8% | 46k | Android device pass, owner |
| E7.6 Real-host CORS and proxy validation (wall-clock blocker) | 1 | Sx1 | 55k | 8% | 127k | O5 throwaway repos and PAT; proxy deploy by owner |

**Totals** (derived by script from the rows above): 139 tasks, 12085k raw task tokens before verification; 31316k CU after the x1.75 verification multiplier and output weighting (`CU = raw x (1 + 4 x output share)`, every non-output token counted as fresh input, so this overstates cost because cached reads weigh 0.1). Size band: **XL (over 15M)** per ESTIMATION.md (informational, not a gate).

**Critical path in agent waves** (fan-out inside a wave is parallel; arrows serialize on a shared file or a gate):
1. Wave 1: E1.1a, E1.2a, E1.3a in parallel (disjoint files).
2. Wave 2: E1.1b, E1.2b, then E1.2c/E1.2d/E1.2e; E1.4a, E1.4c in parallel with them once E1.2c lands.
3. Wave 3: E1.4d (touches `GitSyncService.kt`), then E1.4e; in parallel E1.5a, E1.5b, E1.6a, E1.7a, E1.8a. Wave 4: E1.5c, E1.6b, E1.6c. Gate G1 ships.
4. Wave 5: E2.1a, E7.1a, E7.2a in parallel; Wave 6: E2.1b to E2.1e and E2.1g in parallel, then E2.1f (go/no-go).
5. Wave 7: E2.2a, E2.2b, E2.2d, E2.2e in parallel; E2.2c; E2.2f. Wave 8: E2.3a to E2.3e in parallel; E2.3f then E2.3g (same file `JvmGitRepository.kt`); E2.3h, E2.3i, E2.3j. In parallel from Wave 7: E2.4a, E2.4b, then E2.4c to E2.4f (all touch `GitSyncService.kt` or `SyncState.kt`, so E2.4c, E2.4f, E2.6a, E2.7a serialize), E2.8a to E2.8d; E1.4f, E1.4g, E1.5d, E1.5e, E1.2f, E2.3l to E2.3n, E2.4h, E2.4i, E3.2d, E3.4d, E3.4e, E4.1c, E4.3f, E7.4c are test or control tasks that fan out in the wave after their parent task; E2.3k, E2.4g and E2.9a to E2.9c after E2.3 (E2.4g, E2.9b touch `GitSyncService.kt`, so they serialize with E2.4c, E2.4f, E2.6a, E2.7a).
6. Android lane (parallel to Waves 8 and later once E2.2 and E2.3d exist): E2.5a, then E2.5b, E2.5c, then E2.5d, then E2.5e. Gated by E7.5 (device).
7. Wave 9 (web, parallel with the Android lane): E3.1a, E3.1b, E3.1c in parallel, then E3.1d; E3.2a (`SteleKitAssetFetcher.kt`), then E3.2b, E3.2c, E3.3a, E3.3b, E3.4a, E3.4b in parallel; E4.1a, E4.2a, E4.3a, E4.3c, E4.3d, E4.4a in parallel; then E4.2b to E4.2d, E4.3b, E4.3e, E4.5a to E4.5c serialized on `WasmGitWriteService.kt`.
8. Wave 10: E5.1a to E5.1c, E6.1a to E6.4a in parallel; E7.3a, E7.3b, E7.4a, E7.4b last.
Serial depth is roughly 10 waves; the calendar is set by the blockers below, not by agent runs.

**Overrun checkpoint**: if spend exceeds the table value by more than 25% for the stories completed (per-story CU above x1.25), pause and report the delta with its cause (wrong estimate or real surprise). Informational, not an invitation to cut scope. The first checkpoint is after Story E1.4 (the first story that edits a hot file).

**Wall-clock blockers (owner unless stated)**:
- O2: staging-ref converter decision (blocks Story E4.4 only).
- O3: auto-download defaults and caps (defaults ship as proposed; changeable by users).
- O5: throwaway private LFS repos on github.com, gitlab.com, Bitbucket, Azure DevOps plus PATs (blocks Story E7.6 and ADR-002 Accepted).
- Proxy deploy: the owner deploys the template Worker to their own Cloudflare account (blocks checklist rows 5 and the proxy live test).
- Android device pass (Story E7.5): owner with a physical device; the `androidLfsEnabled` default flips only after it.
- Spike S3 real-host SSH confirmation (optional row of Story E7.6).
- CI: the Bazel lock re-pin runs on the 16 GB machine (`MODULE.bazel:149-158`); wasm browser tests need Chrome (preinstalled on ubuntu-latest) and a one-time `scripts/fix-wasm-karma-tooling.sh` locally.
- PR #413 merge (O7) before Task E6.4b.

Basis: bands and weights INFERRED; recalibrate after the first implementer run (calibration point: `cross-graph-merge-share-target` planning used about 3.5M subagent tokens across 29 runs, ESTIMATION.md).


## Manual validation checklists (owner-run)

### Real-host CORS and proxy checklist (Story E7.6; needs O5: throwaway private repo with an LFS-tracked `assets/*.png`, a PAT per host)
| # | Step | Records |
|---|---|---|
| 1 | In a browser at the SteleKit origin, run `WasmLfsCorsProbe` against github.com, gitlab.com, Bitbucket, Azure DevOps (and a CORS-configured Giftless if available) | probe result per host |
| 2 | GitHub private repo: `GET media.githubusercontent.com/media/<o>/<r>/<ref>/<path>` with `Authorization: token <PAT>` | status, bytes equal to object, any `?token=` redirect (Q1) |
| 3 | GitLab private project: `GET .../repository/files/<path>/raw?ref=<ref>&lfs=true` with the token | status, bytes equal to object |
| 4 | Bitbucket and Azure DevOps: direct batch upload then PUT the returned href from the browser | whether the object-storage href passes CORS (Q3) |
| 5 | Deploy `docs/reference/lfs-cors-proxy/worker.js` to the owner's Cloudflare account; run the health check; round trip a 5 MB and a 60 MB upload on github.com and gitlab.com | pass or fail, observed body limit, CPU limit |
| 6 | Converter (only if O2 YES): run the staging-ref flow on a throwaway repo | latency, final tree has a pointer, staging ref deleted, raw blob reachable by SHA until GC |
| 7 | Over-quota repo (if available): capture the LFS 403 and 509 bodies verbatim | exact text for Q2 |
| 8 | Measure OPFS quota with `navigator.storage.estimate()` in Chrome and Safari | bytes available |

### Android device checklist (Story E7.5)
| # | Step | Pass when |
|---|---|---|
| 1 | Clone an LFS repo into a SAF-backed graph with `androidLfsEnabled=true` | PNG renders in the app and bytes equal the server object |
| 2 | Edit a markdown page, paste a new PNG, sync | Pointer committed, object on server, file still renders |
| 3 | Airplane mode then sync | Text edits queue, no crash, placeholders for undownloaded files |
| 4 | Fill storage to under the preflight threshold | `LfsStorageInsufficient` before any download byte |
| 5 | Force-stop mid-download and relaunch | Resume (`Range`), no truncated media file in `.git/lfs/objects` |
| 6 | Revoke the SAF grant then sync | Definite failure with retry, no deletion of shadow media |

## Test Registry (names that appear in validation.md)

| Test class | Source set and path | Created by task |
|---|---|---|
| `LfsPointerTest`, `LfsPointerPropertyTest` | commonTest `{CT}/git/lfs/` | E1.1b |
| `GitAttributesMatcherTest`, `GitAttributesMatcherPropertyTest`, `LfsPathPolicyTest` | commonTest `{CT}/git/lfs/` | E1.2d |
| `GitAttributesDifferentialTest` | businessTest `{BT}/git/lfs/` | E1.2e |
| `DomainErrorTest` (extended) | commonTest `{CT}/error/` | E1.3a |
| `LfsGuardTest`, `LfsGuardPropertyTest` | commonTest `{CT}/git/lfs/` | E1.4b |
| `GitSyncServiceLfsGuardTest` | businessTest `{BT}/git/` | E1.4e |
| `LfsIndexVerifierTest`, `LfsRawBinaryNeverCommittedTest` | businessTest `{BT}/git/lfs/` | E1.5c |
| `WasmGitWriteServiceLfsGuardTest` | wasmJsTest `{WT}/git/` | E1.6c |
| `GraphLoaderLfsPointerTest` | businessTest `{BT}/db/` | E1.7a |
| `LfsHeldFilesSheetTest` | androidUnitTest `{AT}/ui/components/git/` | E1.8a |
| `LfsSpikeFilterActivationTest`, `LfsSpikeCloneNoCheckoutTest`, `LfsSpikeSshAuthenticateTest` (jvmTest, temporary), `GitShadowWorktreeBinaryRoundTripSpikeTest` (androidUnitTest, temporary) | `{JT}/git/lfs/spike/`, `{AT}/git/` | E2.1b to E2.1e |
| `LfsErrorMapperTest`, `LfsRemoteResolverTest` | commonTest `{CT}/git/lfs/` | E2.2d, E2.2e |
| `LfsBatchClientTest` | businessTest `{BT}/git/lfs/` | E2.2f |
| `JvmLfsObjectStoreTest`, `LfsPointerScannerTest`, `LfsTransferServiceTest`, `LfsCredentialBridgeTest`, `LfsPointerDifferentialTest` | businessTest `{BT}/git/lfs/` | E2.3b to E2.3e, E2.3j |
| `LfsStoragePreflightTest`, `LfsDownloadPolicyTest`, `LfsSettingsStoreTest` | commonTest `{CT}/git/lfs/` | E2.3d, E2.4a |
| `PatLeakageAuditTest` (extended), `SecretRedactionTest` (extended) | jvmTest `{JT}/git/`, commonTest `{CT}/git/` | E2.3e |
| `JvmGitRepositoryLfsCloneTest`, `JvmGitRepositoryLfsMergeTest`, `JvmGitRepositoryLfsPushTest`, `LfsPushOrderingTest`, `LfsShallowPushTest` | businessTest `{BT}/git/lfs/` | E2.3h, E2.3i |
| `LfsFirstSyncReviewTest` | businessTest `{BT}/git/lfs/` | E2.4e |
| `LfsReviewSheetTest` | androidUnitTest `{AT}/ui/screens/git/` | E2.4e |
| `GitShadowWorktreeBinaryTest`, `GitShadowFlushActorBinaryTest`, `AndroidGitRepositoryLfsTest`, `AndroidGitRepositoryStorageGuardTest` (extended) | androidUnitTest `{AT}/git/` | E2.5e |
| `LfsConflictPointerTest` | businessTest `{BT}/git/lfs/` | E2.6b |
| `LfsWatcherSuppressionTest` | businessTest `{BT}/git/lfs/` | E2.7b |
| `LfsFilterActivationTest`, `LfsCloneNoCheckoutTest`, `LfsSshAuthenticateTest` (only if spike S3 is GO) | businessTest `{BT}/git/lfs/` | E2.8c |
| `WasmLfsReadResolverTest`, `WasmLfsObjectCacheTest`, `WasmLfsTreeSourceTest` | wasmJsTest `{WT}/git/` | E3.1d, E3.1e |
| `LfsAssetPlaceholderTest` | androidUnitTest `{AT}/ui/components/` | E3.3a |
| `LfsAssetFetcherTest`, `UnavailableLfsObjectResolverTest` | businessTest `{BT}/ui/components/`, `{BT}/git/lfs/` | E3.4c |
| `SteleKitAssetMapperTest` (extended) | jvmTest `{JT}/ui/components/` | E3.4c |
| `WasmLfsCorsProbeTest` | wasmJsTest `{WT}/git/` | E4.1b |
| `WasmLfsDirectWriteTest` | wasmJsTest `{WT}/git/` | E4.2d |
| `LfsEndpointProxyTest` | commonTest `{CT}/git/lfs/` | E4.3a |
| `WasmLfsProxyTransportTest` | wasmJsTest `{WT}/git/` | E4.3e |
| `worker.test.mjs` | `docs/reference/lfs-cors-proxy/` (node, manual) | E4.3d |
| `LfsStagingRefPolicyTest` | commonTest `{CT}/git/lfs/` | E4.4a |
| `WasmLfsConverterTest` | wasmJsTest `{WT}/git/` | E4.4f |
| `LfsWriteCapabilityResolverTest` | commonTest `{CT}/git/lfs/` | E4.5a |
| `WasmLfsWriteCapabilityTest` | wasmJsTest `{WT}/git/` | E4.5c |
| `WasmLfsLiveTest` (skipped without credentials) | wasmJsTest `{WT}/git/` | E4.7a |
| `IosGitRepositoryLfsGuardTest` | iosTest `{IT}/git/` | E5.1a |
| `FakeLfsServerSelfTest` | businessTest `{BT}/git/testsupport/` | E7.1b |
| `LfsEndToEndTest`, `LfsGitLfsInteropTest` | businessTest `{BT}/git/lfs/` | E7.3a, E7.3b |
| `LfsSpikeDeferredObjectCheckoutTest` (jvmTest, temporary) | `{JT}/git/lfs/spike/` | E2.1g |
| `LfsLegacyRawDetectorTest` | businessTest `{BT}/git/lfs/` | E2.4g |
| `LfsModeIsolationTest` | businessTest `{BT}/git/lfs/` | E2.3a |
| `LfsModeSwitchPushTest` | businessTest `{BT}/git/lfs/` | E2.3k |
| `LfsPointerRepairTest`, `LfsModeResolverTest` | commonTest `{CT}/git/lfs/` | E2.9a, E1.4f (extended in E2.9b) |
| `LfsExecBitTest` | businessTest `{BT}/git/lfs/` | E2.9c |
| `LfsRulesLoaderTest` | businessTest `{BT}/git/lfs/` | E1.2f |
| `LfsGuardCoordinatorTest`, `LfsStagingCallSitesStructuralTest` | businessTest `{BT}/git/lfs/` | E1.4g |
| `LfsModeRollbackTest` | businessTest `{BT}/git/lfs/` | E1.4f |
| `AndroidGitRepositoryLfsGuardTest` | androidUnitTest `{AT}/git/` | E1.5d |
| `LfsRawBinaryNeverCommittedPropertyTest` | businessTest `{BT}/git/lfs/` | E1.5d |
| `LfsPostPushAuditTest` | businessTest `{BT}/git/lfs/` | E1.5e |
| `WasmLfsRulesSourceTest` | wasmJsTest `{WT}/git/` | E1.6c |
| `StelekitLfsFiltersTest` (only if S5 NO-GO) | businessTest `{BT}/git/lfs/` | E2.3m |
| `LfsPostPushReadBackTest` | businessTest `{BT}/git/lfs/` | E2.3n |
| `AndroidNetworkMonitorMeteredTest` | androidUnitTest `{AT}/platform/` | E2.4h |
| `LfsTransferringStateTest` | businessTest `{BT}/git/lfs/` | E2.4h |
| `LfsBandwidthLedgerTest` | commonTest `{CT}/git/lfs/` | E2.4i |
| `ConflictResolutionLfsTest` | androidUnitTest `{AT}/ui/screens/git/` | E2.6b |
| `LfsDeferredObjectCheckoutTest` | businessTest `{BT}/git/lfs/` | E2.8c |
| `LfsLocalObjectResolverTest` | businessTest `{BT}/git/lfs/` | E3.2d |
| `LfsDecodeBudgetTest` | commonTest `{CT}/git/lfs/` | E3.4d |
| `WasmLfsHighResImageTest` | wasmJsTest `{WT}/git/` | E3.4d |
| `AssetItemCardLfsTest` | androidUnitTest `{AT}/ui/assets/` | E3.4e |
| `LfsProxyHealthCheckTest` | wasmJsTest `{WT}/git/` | E4.3f |
| `LfsSettingsSectionWebDefaultsTest` | androidUnitTest `{AT}/ui/screens/git/` | E4.6a |
| `LfsFeatureRegistryEntryTest` | businessTest `{BT}/git/lfs/` | E6.4b |
| `scripts/check-lfs-test-registration.sh`, `scripts/check-jgit-version-parity.sh` (shell controls, not test classes) | repo `scripts/` | E7.4c, E2.8d |
