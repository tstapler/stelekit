# Validation Plan: git-lfs-support

**Date**: 2026-10-11 | **Phase**: SDD 4-validate, round 2 (planning only, no source changed)
**Inputs re-read this round**: `requirements.md`, `implementation/plan.md` (1292 lines: 7 epics, 39 stories, 122 tasks), `decisions/ADR-001..004` (ADR-001 section 3a spike S5, ADR-003 section 2a guard input per mode), `docs/adr/ADR-015-...`, plus the registration files named in section 3.
Labels: VERIFIED = file opened or command run this session (command shown). INFERRED = reasoned.
Path aliases follow plan.md: `{C}` commonMain, `{CT}` commonTest, `{BT}` businessTest, `{JC}` jvmCommonMain, `{J}` jvmMain, `{JT}` jvmTest, `{A}` androidMain, `{AT}` androidUnitTest, `{W}` wasmJsMain, `{WT}` wasmJsTest, `{I}`/`{IT}` iosMain/iosTest; each expands under `kmp/src/<set>/kotlin/dev/stapler/stelekit`.

## Round-2 changes (what differs from round 1)

Round 1 was written against a plan still being written and wrongly called it truncated. Retracted: "plan.md is truncated", "E5-E7 missing", "Effort estimate absent", old gaps G7 (E5), G8 (E6 undefined), G11 (E7 undefined), G13 (no effort table). The plan now has E5, E6, E7, the Effort estimate, the Test Registry appendix, spike S5 (E2.1g), E2.3a `LfsModeIsolationTest`, E2.3k `LfsModeSwitchPushTest`, E2.4g `LfsLegacyRawDetectorTest`, E2.9a-c (`LfsPointerRepairTest`, `LfsModeResolverTest`, `LfsExecBitTest`) and ADR-003 section 2a. This round adds requirements REQ-34 to REQ-42 for those, reconciles every test name with the Test Registry, and restates the real remaining gaps (section 5).

## Headline findings (read first)

1. **The plan's registration claim is still false.** plan.md line 242 (Task E1.3a) says `DomainErrorTest` is "existing, extended, already registered". VERIFIED (counts are matches of the class name):
   - `grep -c 'DomainErrorTest\|SecretRedactionTest' kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt kmp/src/commonTest/kotlin/BUILD.bazel` returns 0 for both files. Both classes live in commonTest (`kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt`, `.../git/SecretRedactionTest.kt`); `business_common_tests.srcs` (BUILD.bazel:102-110) lists only `CaptureTargetResolverTest`, `FakeFileSystem`, `PageSelectionTest`.
   - `PatLeakageAuditTest` and `SteleKitAssetMapperTest`: `grep -c` in `AllJvmTests.kt` returns 0. `jvm_tests` sets `test_class = "dev.stapler.stelekit.AllJvmTests"` (`kmp/src/jvmTest/kotlin/BUILD.bazel:78`), so under Bazel an unlisted class compiles and never runs.
   - `AndroidGitRepositoryStorageGuardTest`: `grep -c` in `AllAndroidUnitTests.kt` returns 0; `android_unit_tests` sets `test_class = ...AllAndroidUnitTests` (`androidUnitTest/kotlin/BUILD.bazel`).
   Consequence: the plan's extensions of these five classes (E1.3a, E2.3e, E2.5e, E3.4c) run under Gradle only unless those tasks also register the class. Plan tasks E2.3d, E2.3e, E3.x defer `business_common_tests.srcs` edits to E7.4a, whose list names only three files (`LfsStoragePreflightTest`, `LfsDownloadPolicyTest`, `LfsSettingsStoreTest`). The plan must say so (gap V1).
2. **`AllBusinessTestsCompletenessTest` covers businessTest only.** It walks `kmp/src/businessTest` (read this session, header comment) so an unregistered commonTest class (the `DomainErrorTest` case today) is invisible to it. The plan's E7.4 relies on a manual audit; gap V5 recommends a mechanical check.
3. **20 test classes in round-1 validation are not in the plan's Test Registry.** VERIFIED by a loop of `grep -c <Class> plan.md` over every class named in the section 2 table (90 classes, 20 with count 0; list in section 5). Some are covered by another plan class or by an acceptance command (marked "covered elsewhere"), some are real gaps. Round 1 asked the plan to adopt all of them; round 2 keeps only the real ones.
4. Every ADR the plan cites exists (see gate table). ADR statuses are all Proposed.

## 1. Requirements

Derived from `requirements.md` (success metrics, scope, constraints), ADR-002/003/004 decisions and the owner direction ("real full LFS on JVM, Android and web; guard everywhere; precise cost statement for web").

| ID | Requirement | Source |
|---|---|---|
| REQ-1 | Pointer parse and serialise are spec-strict, round-trip, typed on every rejection, never throw; legacy version URLs accepted on read only; CRLF pointer is `Corrupt` not `NotAPointer` | Success metric 4; plan E1.1 |
| REQ-2 | One pure-Kotlin `.gitattributes` matcher and `LfsPathPolicy` equal to git (last line wins, deeper wins, basename rule, `.gitattributes` never tracked) | ADR-003 s1; plan E1.2 |
| REQ-3 | `LfsGuard` is a pure function: tracked path with non-pointer, non-empty content is a violation (`RawBinary`, `CorruptPointer`, `ConflictMarkers`); reads at most 1024 bytes | ADR-003 s1; plan E1.4a |
| REQ-4 | Service guard at every staging entry point (`sync`, `commitLocalChanges`, each `markResolved` site), hold-back not block, `SyncState.LfsActionRequired`, text notes still commit | Success metric 1; ADR-003 s2-3 |
| REQ-5 | JVM and Android repository backstop: `LfsIndexVerifier`, `stageSubdir(excludePaths)`, commit refuses a raw tracked blob with `LfsRawBinaryRefused` and un-stages | ADR-003 s2; plan E1.5 |
| REQ-6 | Web guard on `commitGitHub`, `pushViaGitLab`, `createBlob`; fail closed with `NetworkFailure` when rules cannot be fetched; held paths stay dirty | ADR-003 s2; plan E1.6 |
| REQ-7 | Safety-floor regression: no platform ever commits or uploads a raw binary at a tracked path, verified by walking every blob of the resulting repo and the request log | Success metric 1 |
| REQ-8 | Attribute source is working tree plus index (not HEAD), re-evaluated per operation; `.gitattributes` edits apply to the same commit; legacy raw files are never auto-converted (`LegacyRawPending`, per-graph confirmation) | ADR-003 s4-5, s6 (no override) |
| REQ-9 | Pointer files are never parsed or rewritten as pages by `GraphLoader` | Pitfalls R4; plan E1.7 |
| REQ-10 | Error mapping: nine new `DomainError.GitError` variants have entries in both exhaustive maps with the exact texts; HTTP status mapping (401, 403, 404, 413, 422, 429, 507, 509) | Plan E1.3, E2.2d |
| REQ-11 | `LfsBatchClient` (commonMain): token only on batch host, hash and size verified before an object is visible, retry with `Retry-After`, resume with `Range`, 413 batch halving, `verify` action, expired action rejected | ADR-004; plan E2.2 |
| REQ-12 | JVM and Android: clone, fetch, merge and checkout materialise LFS content (prefetch before checkout and before `git.merge()`), shallow clones supported | Success metric 2; ADR-001 |
| REQ-13 | JVM and Android: push uploads objects before the ref moves; a failed upload leaves the remote unchanged; origin holds only pointers; shallow push uploads only new objects | Success metric 2; Pitfalls R1, R11 |
| REQ-14 | Credentials: PAT mapped to `LfsAuth` with the existing username rules, never in a URL, log, `.git/config` or an object-host request | Pitfalls R12; plan E2.3e |
| REQ-15 | JGit filter activation proven (`BuiltinLFS.register()`, filter config, `useJGitBuiltin` unset), `setNoCheckout` clone sequencing, SSH `git-lfs-authenticate` go/no-go, Android byte corruption demonstrated then fixed | ADR-001; plan E2.1 |
| REQ-16 | Settings, `LfsDownloadPolicy` caps never exceeded, metered and wifi-only deferral, first-sync review blocks automatic LFS pulls until decided, `NetworkMonitor.isMetered()` | Open Question 2; plan E2.4 |
| REQ-17 | Android: byte-safe SAF-to-shadow mirror, smudged media not deleted before write-back, storage preflight counts copies, `lfs_android_enabled` default false | Pitfalls R2, R7; plan E2.5 |
| REQ-18 | Pointer conflicts are object-level (keep mine / keep theirs), never a text merge | Pitfalls R9; plan E2.6 |
| REQ-19 | Smudge writes do not raise `DiskConflict` or page re-parses | Pitfalls R14; plan E2.7 |
| REQ-20 | Web read path: GitHub media URL and GitLab `raw?lfs=true` resolution with token isolation, hash verification, size cap, OPFS content-addressed cache, binary-safe fetch | Success metric 3; plan E3.1 |
| REQ-21 | Resolver port and Coil fetcher on every platform; placeholder UI (Pending, Downloading, Failed, Blocked) on every image surface; iOS default resolver returns `Blocked(PlatformUnsupported)` | Pitfalls R21; plan E3.2-E3.4 |
| REQ-22 | Web runtime CORS capability probe (`DirectCapable`, `CorsBlocked`, `Unknown`), cached 24 h | ADR-002 mode 1; plan E4.1 |
| REQ-23 | Web direct write: batch upload, PUT, verify, then blob of the pointer, tree, commit, ref; upload failure leaves no blob/commit and the file dirty; local file becomes a pointer without being marked dirty | ADR-002; plan E4.2 |
| REQ-24 | Opt-in user-owned CORS proxy: requests only to the proxy with `X-Target`, `__health` origin check, token-exposure acknowledgment gates Save, Worker template rejects unauthenticated, wrong-origin and SSRF requests | ADR-002 mode 2; plan E4.3 |
| REQ-25 | Converter (O2-gated): `StagingRefScope` accepts only `refs/heads/stelekit/lfs-inbox/<id>`, disabled by default, failure leaves the ref, specified end to end | ADR-002 mode 3, ADR-003 s7; plan E4.4 |
| REQ-26 | `LfsWriteCapabilityResolver` precedence Direct, Proxy, Converter, ReadOnly; ReadOnly holds files with the exact message; web defaults (ON_DEMAND, 50 MiB cap, no wifi-only) | ADR-002; plan E4.5, E4.6 |
| REQ-27 | iOS stance: guard is called before `stageSubdir`, the stub never reaches commit, read path shows placeholders, native LFS deferred | Scope "iOS stance"; plan Risk Control, O4 |
| REQ-28 | Docs, site, demo and feature registry updated; ADR-015 carries a one-line pointer to ADR-002; proxy README states the token-exposure cost | Scope "Docs/demo/site"; ADR-002 consequences |
| REQ-29 | UX: held-files sheet, sync badge warning state, review sheet, `LfsTransferring` progress, web write-status cost statement | ADR-003 s8; plan E1.8, E2.4, E4.5b |
| REQ-30 | Test registration and builds: `AllBusinessTests`, `AllJvmTests`, `AllAndroidUnitTests`, `business_common_tests.srcs`, wasm `--tests` allow-list, Bazel pin of the LFS artifact | CLAUDE.md; plan intro, E2.8 |
| REQ-31 | Real-host validation (CORS on real hosts, private-repo media URL with PAT, quota bodies, Android device, proxy deploy) | Open Questions 3, 5; plan E7.5, E7.6 |
| REQ-32 | Rollback and flags: `lfs_mode=GUARD_ONLY` disables all transfer code and keeps the guard; defaults per platform | Plan Risk Control |
| REQ-33 | End-to-end: clone, edit LFS file, commit, push against `FakeLfsServer` with interop against the real `git lfs` when present | Success metric 2; plan G2 |
| REQ-34 | Deferred-object checkout is safe: with the builtin filters, `git.checkout()` of a pointer whose object is absent makes zero unauthenticated requests and leaves the pointer text; `status` is clean and `git add` makes no pointer-to-pointer; a `git lfs install` pre-push hook does not double-push; NO-GO switches to `jgit://stelekit/lfs/*` filters | ADR-001 s3a; plan E2.1g (S5), Q5 |
| REQ-35 | Push-time pointer-without-object check runs in every `LfsMode` and flag state; refuses with `LfsObjectMissing` before `git.push()` when a pushed pointer's object is neither local nor on the server | Plan E2.3k; Adversarial C1 |
| REQ-36 | Mode isolation: in `GUARD_ONLY` and `OFF` the repo-local config routes to a passthrough filter so a global external `git-lfs` filter never runs; two graphs with different modes in one JVM do not leak; `cleanFilterActive` is read back from config | Plan E2.3a |
| REQ-37 | Guard input per mode (ADR-003 s2a): AUTO with active clean filter judges the post-clean index, not the working file; GUARD_ONLY and flag-off judge the working file; an AUTO filter that did not run is caught by `stagedLfsViolations` | ADR-003 s2a; plan E1.4 acceptance, E1.4d, E1.4e |
| REQ-38 | CRLF or BOM pointer is repaired to canonical bytes before prefetch; `text=auto` without `-text` on a tracked path yields a held-files warning note | Pitfalls R6; plan E2.9a |
| REQ-39 | `LfsModeResolver` forces `GUARD_ONLY` for system-git sparse graphs and repos with an external `filter.lfs.process` that the override could not replace; service guard still applies | Pitfalls R15; plan E2.9b |
| REQ-40 | Exec bit: pointer blob keeps mode 100755 on JVM; Android documented as not preserved | Pitfalls R18; plan E2.9c |
| REQ-41 | Case-insensitive paths: `LfsRulesLoader` passes `core.ignorecase` to the matcher; the matcher flips case sensitivity | Pitfalls R17; plan E1.2b-d, E2.9c |
| REQ-42 | Proxy failure behaviour: proxy down before push falls to `ReadOnly` with held files; proxy dying mid-upload leaves no blob or ref | Plan E4.3e; Adversarial C2 |

## 2. Requirement to Test mapping

Conventions. Names in plan.md are camelCase scenario names (for example `roundTripForAnyOidAndSize`); those are kept verbatim because plan.md tasks reference them. Tests this document adds use `methodName_should_X_When_Y`. A trailing `(plan)` marks a name taken from plan.md; `(new)` marks a test this document adds (also listed in Plan gaps). Types: U unit, PR property, I integration (real store, real JGit, real HTTP or MockEngine), UI Robolectric, W wasm browser, M manual. "Gradle" is the command; "Bazel" is the target.

Source-set key (used in the last column):
- **CT** = commonTest + registration. Gradle `./gradlew jvmTest --tests "<FQCN>"` (also `testDebugUnitTest`, `wasmJsBrowserTest`). Bazel `bazel test //kmp:business_tests` only after the class is in `AllBusinessTests` and the file in `business_common_tests.srcs`.
- **BT** = businessTest. Gradle `./gradlew jvmTest --tests "<FQCN>"`. Bazel `bazel test //kmp:business_tests`.
- **JT** = jvmTest. Gradle `scripts/jvm-display-check.sh -- ./gradlew jvmTest --tests "<FQCN>"`. Bazel `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests` plus the `--sandbox_add_mount_pair` flags from CLAUDE.md.
- **AT** = androidUnitTest. Gradle `./gradlew testDebugUnitTest --tests "<FQCN>"`. Bazel `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android`.
- **WT** = wasmJsTest. Gradle `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests "<FQCN>"`. Bazel: none; CI runs it only when the FQCN is in the `ci.yml` allow-list.

| Requirement | Test File | Test Name | Type | Scenario | Source set / runner |
|---|---|---|---|---|---|
| REQ-1 | `{CT}/git/lfs/LfsPointerTest.kt` | `canonicalPointerRoundTripsByteForByte` (new) (plan: E1.1 case 1) | U | Happy: canonical bytes parse to `Valid` and re-serialise identically | CT (register in `AllBusinessTests` + `business_common_tests.srcs`) |
| REQ-1 | `{CT}/git/lfs/LfsPointerTest.kt` | `parse_should_returnCorruptCrlf_When_everyNewlineIsCrlf` (new) | U | Error: CRLF rewrite is `Corrupt("CRLF")` | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerTest.kt` | `parse_should_returnNotAPointer_When_textDoesNotStartWithVersionLine` (new) | U | Error: `# My notes\n` is `NotAPointer` | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerTest.kt` | `looksLikePointer_should_returnFalseWithoutReadingHead_When_sizeIs5000000` (new) | U | Sniffer never reads body for big files | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerTest.kt` | `parse_should_acceptLegacyVersionUrlsAndWriteCanonical_When_hawserOrGitMediaUrl` (new) | U | Legacy URL read-only (INFERRED rule labelled in test) | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerPropertyTest.kt` | `roundTripForAnyOidAndSize` (plan) | PR | `parse(serialize(p)) == p` for any oid and size 0..Long.MAX | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerPropertyTest.kt` | `canonicalBytesAreStableUnderReserialise` (plan) | PR | Canonical bytes are a fixed point | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerPropertyTest.kt` | `everyRejectionCaseIsTypedNeverThrows` (plan) | PR | Error: 17 rejection classes typed, no throw | CT |
| REQ-1 | `{CT}/git/lfs/LfsPointerPropertyTest.kt` | `crlfRewriteIsCorruptNotNotAPointer` (plan) | PR | Error: CRLF always `Corrupt` | CT |
| REQ-1 | `{BT}/git/lfs/LfsPointerDifferentialTest.kt` | `canonicalPointersAgreeWithJgitParser_`... (plan class) ; `jgitLeniencyDivergencesAreDocumented` (new) | I | Oracle: agree with JGit on canonical, pin the documented divergences | BT; Gradle `jvmTest`; Bazel `//kmp:business_tests` |
| REQ-2 | `{CT}/git/lfs/GitAttributesMatcherTest.kt` | `deeperFileOverridesRootUnset` (new) (E1.2 case 1) | U | Happy: `assets/raw/a.png` false, `assets/b.png` true | CT |
| REQ-2 | `{CT}/git/lfs/GitAttributesMatcherTest.kt` | `slashFreePatternMatchesBasenameAtAnyDepth` (new) (E1.2 case 2) | U | Anchoring rules | CT |
| REQ-2 | `{CT}/git/lfs/GitAttributesMatcherTest.kt` | `unsetAndUnspecifiedAndOtherValueClearTracking` (new) | U | Error-ish: `-filter`, `!filter`, `filter=other`; macros; `ignoreCase` flip | CT |
| REQ-2 | `{CT}/git/lfs/GitAttributesMatcherPropertyTest.kt` | `unrelatedLineNeverChangesResult` (plan) | PR | Metamorphic | CT |
| REQ-2 | same | `reorderingUnrelatedLinesIsNoOp` (plan) | PR | Metamorphic | CT |
| REQ-2 | same | `rootedAndRecursiveGlobEquivalence` (plan) | PR | Metamorphic | CT |
| REQ-2 | same | `laterLineWins` (plan) | PR | Precedence | CT |
| REQ-2 | same | `deeperFileWins` (plan) | PR | Precedence | CT |
| REQ-2 | same | `matchesNaiveReferenceOracle` (plan) | PR | Second implementation oracle | CT |
| REQ-2 | `{CT}/git/lfs/LfsPathPolicyTest.kt` | `gitattributesFileNeverTracked` (plan) | U | `* filter=lfs` still exempts `.gitattributes` | CT |
| REQ-2 | same | `nestedOverrideUnsets` (plan) | U | Nested override | CT |
| REQ-2 | `{CT}/git/lfs/LfsRulesLoaderTest.kt` | `load_should_readEveryAncestorAndInfoAttributes_When_pathsAreNested` (new) | U | Loader reads ancestors plus `.git/info/attributes` | CT |
| REQ-2 | `{BT}/git/lfs/GitAttributesDifferentialTest.kt` | `policyAgreesWithJgitTreeWalkAttributes` (new), `policyAgreesWithGitCheckAttr` (new) (`Assume` git) | I | 200 generated files x 20 paths vs JGit and real git | BT |
| REQ-2 | same | `emptyFileIsStoredRawByGitLfsClean` (new) (`Assume` git-lfs) | I | Confirms ADR-003 empty-file exemption, else reported unconfirmed | BT |
| REQ-3 | `{CT}/git/lfs/LfsGuardTest.kt` | `evaluate_should_holdRawPng_When_pathIsTracked` (new) | U | Happy (hold): raw PNG at tracked path | CT |
| REQ-3 | same | `evaluate_should_allowPointerAndEmptyFile_When_pathIsTracked` (new) | U | Happy (allow): pointer, 0-byte | CT |
| REQ-3 | same | `evaluate_should_exemptGitattributes_And_honourNestedOverride` (new) | U | Edge | CT |
| REQ-3 | same | `evaluate_should_reportCorruptPointer_And_conflictMarkers` (new) | U | Error: reasons `CorruptPointer`, `ConflictMarkers` | CT |
| REQ-3 | `{CT}/git/lfs/LfsGuardPropertyTest.kt` | `allowedIffPointerOrEmptyAtTrackedPath` (plan) | PR | Iff property | CT |
| REQ-3 | same | `untrackedPathNeverHeld` (plan) | PR | | CT |
| REQ-3 | same | `bomAndCrlfPointersAreHeld` (plan) | PR | | CT |
| REQ-3 | same | `over1024BytesIsHeld` (plan) | PR | | CT |
| REQ-3 | same | `versionPrefixTextFileIsHeld` (plan) | PR | | CT |
| REQ-3 | same | `neverReadsMoreThan1024Bytes` (plan) | PR | Probe counts bytes | CT |
| REQ-3 | `{CT}/git/lfs/LfsGuardCoordinatorTest.kt` | `coordinate_should_buildHeldSetFromRulesAndProbe_When_changedPathsMixed` (new) | U | Coordinator wiring in isolation | CT |
| REQ-4 | `{BT}/git/GitSyncServiceLfsGuardTest.kt` | `sync_should_holdRawPngAndCommitMarkdown_When_assetsAreLfsTracked` (new) (E1.4 case 1) | I | Happy: `stageSubdir(excludePaths={assets/photo.png})`, push proceeds, state `LfsActionRequired` | BT; Gradle `jvmTest`; Bazel `//kmp:business_tests` |
| REQ-4 | same | `sync_should_holdNothing_When_trackedFileIsCanonicalPointer` (new) | I | Pointer passes | BT |
| REQ-4 | same | `sync_should_holdNothing_When_trackedFileIsEmpty` (new) | I | Empty passes | BT |
| REQ-4 | same | `commitLocalChanges_should_excludeRawFile_When_tracked` (new) | I | Entry point 2 | BT |
| REQ-4 | same | `resolveConflicts_should_refuseRawFile_When_markResolvedAtEachOfThreeSites` (new) | I | Entry point 3 x 3 sites; `StubGitRepository.commit` never sees it | BT |
| REQ-4 | same | `commitLocalChanges_should_refuseFileWithConflictMarkers_When_markersPresent` (new) | I | Parity gap fixed in E1.4d | BT |
| REQ-4 | same | `sync_should_returnNonFatalResultWithHeldList_When_violationsExist` (new) | I | Error path: non-fatal | BT |
| REQ-5 | `{BT}/git/lfs/LfsIndexVerifierTest.kt` | `stageSubdir_should_unstageHeldPath_When_excludePathsGiven` (new) (E1.5 case 1) | I | Real JGit: index holds `pages/a.md` only | BT |
| REQ-5 | same | `commit_should_refuseAndUnstage_When_rawTrackedBlobStagedDirectly` (new) (E1.5 case 2) | I | Error: `LfsRawBinaryRefused`, no commit exists | BT |
| REQ-5 | same | `verify_should_passPointerEmptyAndNestedRules_When_staged` (new) | I | Happy variants | BT |
| REQ-5 | same | `markResolved_should_runVerifier_When_resolvingTrackedPath` (new) | I | Third staging site | BT |
| REQ-5 | `{AT}/git/AndroidGitRepositoryLfsGuardTest.kt` | `commit_should_refuseRawTrackedBlob_When_shadowRepoIndexed` (new) | UI/I (Robolectric) | Android parity of E1.5b; Validation-added V2; plan E1.5c has JVM tests only | AT (register in `AllAndroidUnitTests`) |
| REQ-6 | `{WT}/git/WasmGitWriteServiceLfsGuardTest.kt` | `commitGitHub_should_postNoBlobForTrackedPng_When_rulesTrackAssets` (new) (E1.6 case 1) | W | Happy (hold): request log has blob for `a.md`, none for `p.png`, `p.png` stays dirty | WT; ci.yml allow-list |
| REQ-6 | same | `pushViaGitLab_should_excludeTrackedPathFromActions_When_gitlabHost` (new) (case 2) | W | GitLab actions array | WT |
| REQ-6 | same | `commitGitHub_should_returnNetworkFailureAndPostNothing_When_gitattributesGet503` (new) (case 3) | W | Error: fail closed, dirty set unchanged | WT |
| REQ-6 | same | `createBlobNeverCalledForTrackedPath` (plan) | W | Structural second check | WT |
| REQ-6 | same | `commitGitHub_should_treatGitattributes404AsNoRules_When_fileAbsent` (new) | W | 404 means no rules | WT |
| REQ-6 | `{WT}/git/WasmLfsRulesSourceTest.kt` | `rulesSource_should_fetchNestedGitattributesAtBaseSha_When_treeListsThem` (new) | W | Rules source in isolation; V4 (plan covers it only through `WasmGitWriteServiceLfsGuardTest` 503 and 404 cases) | WT; ci.yml allow-list |
| REQ-7 | `{BT}/git/lfs/LfsRawBinaryNeverCommittedTest.kt` | `stageAndCommit_should_neverWriteRawBlobAtLfsPath_When_modeIsGuardOnly` (new) | I | Real JGit, 3 MB `assets/a.bin`, walk every blob | BT |
| REQ-7 | same | `directGitAddThenCommit_should_beRefused_When_modeIsGuardOnly` (new) | I | Error path | BT |
| REQ-7 | `{BT}/git/lfs/LfsRawBinaryNeverCommittedPropertyTest.kt` | `noRawBlobAtTrackedPathInAnyGeneratedRepo` (new) | PR | Generated attribute files x file contents through `stageSubdir`+`commit`; asserts tree walk; V3 (plan has the property only on web, E1.6c) | BT |
| REQ-7 | `{BT}/git/lfs/FakeLfsServerSelfTest.kt` | `server_should_neverSeeBodyWhoseSha256DiffersFromOid_When_pushRuns` (new) | I | Upload-side safety floor, uses `FakeLfsServer` recording knob; plan E7.1a states `neverReceivedRawBinary()` but names no test; V3 | BT |
| REQ-8 | `{BT}/git/GitSyncServiceLfsGuardTest.kt` | `sync_should_holdRawPng_When_gitattributesEditedButUncommitted` (new) (E1.4 case 4) | I | Worktree plus index, not HEAD | BT |
| REQ-8 | same | `sync_should_notFlagUnchangedFiles_When_newRuleAdded` (new) | I | No retroactive flag (ADR-003 s5) | BT |
| REQ-8 | `{BT}/git/lfs/LfsLegacyRawDetectorTest.kt` | `classify_should_returnLegacyRawPending_When_headBlobRawAndWorkingFileUnchanged` (new) | I | Happy; plan E2.4g | BT |
| REQ-8 | same | `commit_should_notConvertLegacyRaw_When_noMigrationConfirmation` (new); `commit_should_convert_When_confirmed` (new) | I | Error and confirm paths | BT |
| REQ-9 | `{BT}/db/GraphLoaderLfsPointerTest.kt` | `loadDirectory_should_skipPointerPageAndLeaveBytesUnchanged_When_pagesBigMdIsPointer` (new) | I | Happy: no page `Big`, one warning | BT |
| REQ-9 | same | `loadDirectory_should_skipCorruptPointerWithoutThrowing_When_crlfPointer` (new) | I | Error | BT |
| REQ-10 | `{CT}/error/DomainErrorTest.kt` (existing, extended) | `toSyncErrorMessageAndToUiMessage_should_returnRefusingText_When_lfsRawBinaryRefused` (new) | U | Happy: exact text in both maps, project compiles (exhaustive `when`) | CT; **not registered today (V1)** |
| REQ-10 | same | `lfsQuotaExceeded_should_startWithQuotaUsedUp_When_detailNull` (new) | U | Error text | CT |
| REQ-10 | same | `everyLfsVariantHasEntryInBothMaps` (new) | U | Reflection over sealed subclasses | CT |
| REQ-10 | `{CT}/git/lfs/LfsErrorMapperTest.kt` | `map_should_returnExpectedVariant_When_status401_403_404_413_422_429_507_509` (new) (table) | U | Eight statuses | CT |
| REQ-10 | same | `map_should_fallBackToStatusOnly_When_quotaBodyUnknown` (new) | U | Q2 gap handling | CT |
| REQ-10 | same | `mapProperty_should_neverThrow_When_anyStatusHeadersBody` (new) | PR | Total function | CT |
| REQ-11 | `{BT}/git/lfs/LfsBatchClientTest.kt` | `download_should_returnIntegrityFailedAndNotStore_When_hashDiffers` (new) (E2.2 case 1) | I | Error: real HTTP (OkHttp engine) vs `FakeLfsServer` | BT |
| REQ-11 | same | `download_should_sendAuthorizationOnBatchHostOnly_When_hrefOnOtherPort` (new) | I | Token isolation | BT |
| REQ-11 | same | `batch_should_succeedAfterOneRetry_When_429WithRetryAfter` (new) | I | Retry | BT |
| REQ-11 | same | `download_should_resumeWithRange_When_cutAfter1MiB` (new) | I | Resume | BT |
| REQ-11 | same | `uploadSkippedWhenServerAlreadyHasObject` (plan) | I | | BT |
| REQ-11 | same | `verifyActionCalled` (plan) | I | | BT |
| REQ-11 | same | `expiredActionRejected` (plan) | I | | BT |
| REQ-11 | same | `tokenNeverInUrlOrLogs` (plan) | I | | BT |
| REQ-11 | same | `batch_should_halveAndRetryOnce_When_413` (new) | I | Batch chunking | BT |
| REQ-11 | `{CT}/git/lfs/LfsRemoteResolverTest.kt` | `resolve_should_preferLfsUrlThenLfsconfigThenRemoteInfoLfs_When_allPresent` (new) | U | Happy | CT |
| REQ-11 | same | `resolve_should_returnNotSupportedHere_When_sshRemoteAndNoHttpsToken` (new) | U | Error | CT |
| REQ-11 | same | `resolve_should_neverPutCredentialsInUrl` (new) | PR | | CT |
| REQ-11 | `{BT}/git/lfs/JvmLfsObjectStoreTest.kt` | `commit_should_makeObjectVisibleAtomically_When_hashAndSizeMatch` (new); `commit_should_deleteAndFail_When_mismatch` (new); `resume_should_keepPartFile_When_interrupted` (new) | I | Real filesystem store | BT |
| REQ-12 | `{BT}/git/lfs/JvmGitRepositoryLfsCloneTest.kt` | `clone_should_materialiseOriginalBytesAndStoreObject_When_originHoldsPointer` (new) (E2.3 case 1) | I | Real JGit + `LfsBareOriginFixtures` + `FakeLfsServer` | BT |
| REQ-12 | `{BT}/git/lfs/JvmGitRepositoryLfsMergeTest.kt` | `merge_should_downloadBothObjectsBeforeMerge_When_twoPointersChanged` (new) | I | Prefetch ordering | BT |
| REQ-12 | same | `merge_should_resolveToOneSide_When_oneSidedPointerChange` (new); `merge_should_surfaceConflict_When_bothSidesChangePointer` (new) | I | Error path | BT |
| REQ-12 | `{BT}/git/lfs/LfsPointerScannerTest.kt` | `scan_should_findPointersInTreeAndDiff_When_shallowRepoAndCustomRemoteName` (new) | I | Scanner, never hard-coded `origin` | BT |
| REQ-12 | `{BT}/git/lfs/LfsFilterActivationTest.kt` (graduated S1) | `filterActivation_should_commitPointerAndStoreObject_When_builtinFiltersConfigured` (new) | I | Zero requests recorded | BT |
| REQ-12 | `{BT}/git/lfs/LfsCloneNoCheckoutTest.kt` (graduated S2) | `cloneNoCheckout_should_materialiseFromPreseededStore_When_checkoutAfterScan` (new) | I | | BT |
| REQ-13 | `{BT}/git/lfs/JvmGitRepositoryLfsPushTest.kt` | `push_should_leaveOnlyPointersAndOneNewObject_When_new3MbFileCommitted` (new) (E2.3 case 3) | I | Happy | BT |
| REQ-13 | same | `push_should_failWithAuthError_When_serverRequiresAuthAndNoToken` (new) | I | Error | BT |
| REQ-13 | `{BT}/git/lfs/LfsPushOrderingTest.kt` | `push_should_leaveOriginTipUnchanged_When_secondPutFails500` (new) (E2.3 case 2) | I | Error: R1 half-failed push | BT |
| REQ-13 | `{BT}/git/lfs/LfsShallowPushTest.kt` | `push_should_uploadExactlyOneObject_When_depth50CloneAndOneNewFile` (new) | I | Shallow | BT |
| REQ-13 | `{BT}/git/lfs/LfsTransferServiceTest.kt` | `uploadForPush_should_abortWithObjectMissing_When_localObjectAbsent` (new); `prefetch_should_skipObjectsAlreadyInStore` (new) | I | | BT |
| REQ-13 | `{CT}/git/lfs/LfsStoragePreflightTest.kt` | `preflight_should_returnInsufficient_When_requiredExceedsFree` (new); `preflightProperty_should_neverUnderCountCopies` (new) | U/PR | | CT |
| REQ-14 | `{BT}/git/lfs/LfsCredentialBridgeTest.kt` | `bridge_should_mapHttpsTokenToEmptyUsernameAndOauthToXOauthBasic` (new) | U | Happy | BT |
| REQ-14 | same | `bridge_should_failWithAuthError_When_noCredential` (new) | U | Error | BT |
| REQ-14 | `{JT}/git/PatLeakageAuditTest.kt` (existing, extended) | `lfsUrlAndSignedHrefNeverLeakToken` (new) | I | Extends existing audit; **not in `AllJvmTests` (V1, grep count 0)** | JT |
| REQ-14 | `{CT}/git/SecretRedactionTest.kt` (existing, extended) | `redact_should_maskTokenInLfsUrlAndSignedHref` (new) | U | **not registered anywhere (V1)** | CT |
| REQ-15 | `{JT}/git/lfs/spike/LfsSpikeFilterActivationTest.kt` | `s1_should_record_goNoGo_When_builtinFiltersConfigured` (new) | I | Spike, not registered until graduated | JT (manual run, recorded in ADR-001) |
| REQ-15 | `{JT}/git/lfs/spike/LfsSpikeCloneNoCheckoutTest.kt` | `s2_should_record_goNoGo_When_noCheckoutThenCheckout` (new) | I | Spike | JT |
| REQ-15 | `{JT}/git/lfs/spike/LfsSpikeSshAuthenticateTest.kt` | `s3_should_recordNoGo_When_minaServerClassesUnavailable` (new) | I | Spike with explicit NO-GO branch | JT |
| REQ-15 | `{AT}/git/GitShadowWorktreeBinaryRoundTripSpikeTest.kt` | `s4_should_demonstrateByteCorruption_When_nonUtf8SafFile` (new) | UI | Negative result recorded, then deleted in E2.5e | AT (unregistered by design) |
| REQ-16 | `{CT}/git/lfs/LfsDownloadPolicyTest.kt` | `meteredAndWifiOnlyDefersAll` (plan) | PR | Metered defers all | CT |
| REQ-16 | same | `neverExceedsPerObjectCap` (plan), `neverExceedsSyncCap` (plan) | PR | Caps never exceeded | CT |
| REQ-16 | same | `plan_should_returnAtMost20Immediate_When_30x10MiBAndUnmetered` (new) | U | E2.4 case 2 | CT |
| REQ-16 | `{CT}/git/lfs/LfsSettingsStoreTest.kt` | `store_should_returnO3Defaults_When_noKeysPresent` (new); `store_should_roundTrip_When_keysWritten` (new) | U | | CT |
| REQ-16 | `{BT}/git/lfs/LfsFirstSyncReviewTest.kt` | `sync_should_returnFirstSyncReviewPendingAndRequestNoObject_When_120PointersAndNoDecision` (new) | I | Happy | BT |
| REQ-16 | same | `sync_should_resume_When_decisionNone`; `decision_should_persist` (new) | I | | BT |
| REQ-16 | `{AT}/ui/screens/git/LfsReviewSheetTest.kt` | `sheet_should_show120FilesAbout1_4GiB_When_surveyGiven` (new) | UI | | AT |
| REQ-16 | `{AT}/platform/AndroidNetworkMonitorMeteredTest.kt` | `isMetered_should_returnTrue_When_activeNetworkMetered` (new) | UI | E2.4b has no test in plan; V4 | AT |
| REQ-17 | `{AT}/git/GitShadowWorktreeBinaryTest.kt` | `syncFromSafRoot_should_mirrorBytesIdentically_When_nonUtf8File` (new) | UI | Happy, replaces spike | AT |
| REQ-17 | `{AT}/git/GitShadowFlushActorBinaryTest.kt` | `deleteOrphanedShadowFiles_should_keepSmudgedFile_When_notYetFlushedToSaf` (new) | UI | Error/ordering | AT |
| REQ-17 | `{AT}/git/AndroidGitRepositoryStorageGuardTest.kt` (existing, extended) | `clonePreflight_should_returnStorageInsufficient_When_500MbPointersAnd450MbFree` (new) | UI | **not in `AllAndroidUnitTests` (V1)** | AT |
| REQ-17 | `{AT}/git/AndroidGitRepositoryLfsTest.kt` | `clone_should_materialiseLfsFile_When_flagEnabledWithMockEngine` (new); `clone_should_stayGuardOnly_When_lfsAndroidEnabledFalse` (new) | UI/I | Ktor `MockEngine` (Q4) | AT |
| REQ-18 | `{BT}/git/lfs/LfsConflictPointerTest.kt` | `unresolvedFiles_should_flagPointerConflict_When_markersInsidePointer` (new) | I | Happy | BT |
| REQ-18 | same | `resolve_should_checkoutTheirsAndPrefetch_When_userKeepsTheirs` (new) | I | | BT |
| REQ-18 | `{AT}/ui/screens/git/ConflictResolutionLfsTest.kt` | `screen_should_hideHunkEditorAndShowTwoButtons_When_isLfsPointer` (new) | UI | E2.6b names only `LfsConflictPointerTest` (businessTest); V4 | AT |
| REQ-19 | `{BT}/git/lfs/LfsWatcherSuppressionTest.kt` | `watcher_should_emitNoDiskConflictOrReparse_When_40BinaryWritesDuringSyncBracket` (new) | I | Happy | BT |
| REQ-19 | same | `watcher_should_dropNonMarkdownEventsBeforeRead_When_binaryWrite` (new) | I | | BT |
| REQ-20 | `{WT}/git/WasmLfsReadResolverTest.kt` | `resolve_should_storeVerifiedBytesAndRegisterBlobUrl_When_githubMediaUrl5MiB` (new) (E3.1 case 1) | W | Happy | WT; ci.yml allow-list |
| REQ-20 | same | `resolve_should_requestRawLfsTrueWithAuth_When_gitlabHost` (new) | W | | WT |
| REQ-20 | same | `resolve_should_returnIntegrityFailedAndStoreNothing_When_hashMismatch` (new) | W | Error | WT |
| REQ-20 | same | `stateOf_should_returnBlockedSizeCap_When_200MiBAnd50MiBCap` (new) | W | Error | WT |
| REQ-20 | same | `resolve_should_notSendAuthorization_When_redirectedToOtherHost` (new) | W | Token isolation | WT |
| REQ-20 | same | `resolve_should_mapStatus401_404_429_When_hostFails`; `resolve_should_handle8MbBodyWithoutStringDecoding` (new) | W | Error mapping, R8 | WT |
| REQ-20 | `{WT}/git/WasmLfsObjectCacheTest.kt` | `cache_should_notExposeObject_When_hashUnverified`; `cache_should_evictLru_When_overCap`; `cache_should_refuse_When_storageEstimateInsufficient` (new) | W | | WT |
| REQ-20 | `{WT}/git/WasmLfsTreeSourceTest.kt` | `survey_should_countPointersAndSumSizes_When_treeListed` (new) | W | | WT |
| REQ-20 | `{WT}/git/WasmLfsLiveTest.kt` | `live_should_reportPerHostProbeMediaDirectAndProxy_When_credentialsSupplied` (new) | M | Skipped without credentials (see section 4) | WT (not on allow-list) |
| REQ-21 | `{BT}/ui/components/LfsAssetFetcherTest.kt` | `fetch_should_returnResolverBytes_When_130BytePointerOnDisk` (new) (E3.2 case 1) | I | Happy | BT |
| REQ-21 | same | `fetch_should_neverCallResolver_When_40KbPng` (new); `fetch_should_passThrough_When_1023ByteNonPointer` (new) | I | | BT |
| REQ-21 | `{BT}/git/lfs/UnavailableLfsObjectResolverTest.kt` | `stateOf_should_returnBlockedPlatformUnsupported_When_iosDefault` (new) | U | iOS default | BT |
| REQ-21 | `{JT}/ui/components/SteleKitAssetMapperTest.kt` (existing, extended) | `mapper_should_handlePointerBackedPath` (new) | I | **not in `AllJvmTests` (V1)** | JT |
| REQ-21 | `{AT}/ui/components/LfsAssetPlaceholderTest.kt` | `placeholder_should_showPendingBlockedQuotaProxyAndProgressTexts` (new) (4 exact strings) | UI | Happy and Blocked states | AT |
| REQ-21 | `{AT}/ui/assets/AssetItemCardLfsTest.kt` | `card_should_showPlaceholderWithSize_When_filePointer` (new) | UI | E3.4a has no test in plan; V4 | AT |
| REQ-21 | `{BT}/git/lfs/LfsLocalObjectResolverTest.kt` | `resolve_should_downloadThenCheckoutSinglePath_When_onDemand` (new); `stateOf_should_returnBlocked_When_androidFlagOff` (new) | I | E3.2b has no test in plan; V4 | BT |
| REQ-22 | `{WT}/git/WasmLfsCorsProbeTest.kt` | `probe_should_returnDirectCapable_When_batchAnswers401` (new) | W | Happy | WT; ci.yml allow-list |
| REQ-22 | same | `probe_should_returnCorsBlockedAndCache_When_typeErrorThrown` (new) | W | Error | WT |
| REQ-22 | same | `probe_should_skipRequestWithin24h_And_reprobeAfter` (new) | W | TTL | WT |
| REQ-23 | `{WT}/git/WasmLfsDirectWriteTest.kt` | `commitGitHub_should_followBatchPutVerifyThenBlobTreeCommitRef_When_directMode3MiB` (new) | W | Happy: request order | WT; ci.yml allow-list |
| REQ-23 | same | `commitGitHub_should_makeNoBlobTreeCommitRef_When_objectPut500` (new) | W | Error: file stays dirty | WT |
| REQ-23 | same | `commitGitHub_should_swapLocalFileToCleanPointerAndCacheBytes_When_success` (new) | W | | WT |
| REQ-23 | same | `commitGitHub_should_sendTokenOnlyOnBatch_When_directMode` (new) | W | | WT |
| REQ-24 | `{CT}/git/lfs/LfsEndpointProxyTest.kt` | `toProxy_should_setXTargetHeader_When_githubEndpoint` (new); `toProxy_should_passThroughProxyOriginHrefs` (new) | U | | CT |
| REQ-24 | `{WT}/git/WasmLfsProxyTransportTest.kt` | `batch_should_goToProxyWithXTargetAndNothingToGithub_When_proxyConfigured` (new) | W | Happy | WT; ci.yml allow-list |
| REQ-24 | same | `enable_should_refuseWithOriginMismatch_When_healthAllowedOriginDiffers` (new) | W | Error | WT |
| REQ-24 | same | `save_should_beDisabled_When_ackNotTicked` (new) | W | Ack gating | WT |
| REQ-24 | `docs/reference/lfs-cors-proxy/worker.test.mjs` | `handleRequest_should_return403AndForwardNothing_When_noAuthorization` (new) | U (node) | Also wrong origin, SSRF (`127.0.0.1`, `localhost`, `[::1]`, decimal IP), path regex, HMAC rewrite, health. Run: `node --test docs/reference/lfs-cors-proxy/worker.test.mjs`; not in CI | manual run recorded |
| REQ-24 | `{WT}/git/LfsProxyHealthCheckTest.kt` | `health_should_acceptMatchingOriginAndVersion1` (new) | W | E4.3b names no health-check test; `WasmLfsProxyTransportTest` covers origin mismatch only; V4 | WT; allow-list |
| REQ-25 | `{CT}/git/lfs/LfsStagingRefPolicyTest.kt` | `accepts_should_beTrueOnlyForInboxPattern_When_anyString` (new) | PR | Any non-matching string rejected; any configured-branch ref rejected | CT |
| REQ-25 | same | `rejects_should_returnFalse_When_mainDotDotOrSpaceRef` (new) (3 named refs) | U | Error | CT |
| REQ-25 | `{WT}/git/WasmLfsConverterTest.kt` | `push_should_followBlobTreeCommitRefDispatchPoll_When_converterApproved` (new) | W | Remains unbuilt if O2 = NO | WT; allow-list |
| REQ-25 | same | `push_should_keepRefAndNameRunUrl_When_workflowFails` (new); `resolver_should_neverReturnConverter_When_flagFalse` (new); GitLab variant (new) | W | | WT |
| REQ-26 | `{CT}/git/lfs/LfsWriteCapabilityResolverTest.kt` | `precedenceIsStable` (plan), `readOnlyWhenNothingAvailable` (plan) | PR | | CT |
| REQ-26 | same | `resolve_should_returnDirectProxyConverterReadOnly_When_matrixInputs` (new) | U | 4 precedence cases | CT |
| REQ-26 | `{WT}/git/WasmLfsWriteCapabilityTest.kt` | `resolve_should_integrateProbeHealthAndSettings_When_githubHost` (new); `readOnly_should_holdFileWithExactMessage_When_nothingAvailable` (new) | W | | WT; allow-list |
| REQ-26 | `{AT}/ui/screens/git/LfsSettingsSectionWebDefaultsTest.kt` | `defaults_should_beOnDemand50MiBNoWifiOnly_When_web`; `placeholder_should_offerDownloadAnyway_When_sizeCap` (new) | UI | E4.6a has no test in plan; V4 (INFERRED: could live in `LfsDownloadPolicyTest`) | AT (INFERRED: web defaults are pure and could instead live in `LfsDownloadPolicyTest`) |
| REQ-27 | `{IT}/git/IosGitRepositoryLfsGuardTest.kt` | `stageSubdir_should_returnNotSupportedAndNeverReachCommit_When_iosStub` (new) (plan E5.1a: `stageSubdir`, `commit`, `push` return `NotSupported("iOS")`) | U | iOS stub never reaches commit | IT; runs only in `.github/workflows/ci-ios.yml` (macos-latest, path-filtered on `kmp/src/iosTest/**`), no registration list; not in Bazel |
| REQ-27 | `{BT}/git/lfs/UnavailableLfsObjectResolverTest.kt` | see REQ-21 | U | iOS read stance | BT |
| REQ-28 | command | `grep -c 'ADR-002-web-lfs-write-strategy' docs/adr/ADR-015-wasm-git-data-api-write-back.md` returns 1 and `git diff --stat` shows that file changed by one line (plan E6.1 acceptance) | M | ADR-015 pointer; replaces round-1 `Adr015PointerTest` (plan does not build it) | acceptance command run by implementer |
| REQ-28 | command | `./gradlew :kmp:generateDemoFileSystem` then `git diff --stat kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt` shows `Git LFS.md` added; after PR #413: `SyncFeatures.kt` has a `git-lfs` entry (plan E6.4 acceptance, O7) | M | Demo and registry; replaces round-1 `LfsFeatureRegistryEntryTest` | acceptance command |
| REQ-28 | command | `cd site && npm run build` renders `/user/git-sync` with UNVERIFIED labels while ADR-002 is Proposed (plan E6.2 acceptance) | M | Site | acceptance command |
| REQ-28 | manual | proxy README states token exposure and caps | M | Section 4 | manual |
| REQ-29 | `{AT}/ui/components/git/LfsHeldFilesSheetTest.kt` | `sheet_should_listPathWithRemedyText_When_rawBinaryHeld` (new) (E1.8) | UI | Happy | AT |
| REQ-29 | same | `badge_should_renderWarningChipNotError_When_lfsActionRequired` (new) | UI | | AT |
| REQ-29 | same | `sheet_should_showReadOnlyWording_When_modeReadOnly` (new) | UI | | AT |
| REQ-29 | `{BT}/git/lfs/LfsTransferringStateTest.kt` | `sync_should_emitLfsTransferringProgress_When_prefetchRuns` (new) | I | E2.4f has no test in plan; V4 | BT |
| REQ-30 | `{BT}/AllBusinessTestsCompletenessTest.kt` (existing) | existing completeness check | I | Fails if any new businessTest `@Test` class is unregistered | BT |
| REQ-30 | `{BT}/git/lfs/scripts/check-lfs-test-registration.sh.kt` | `audit_should_findEveryLfsTestClassInSuites_When_scanningSourceTrees` (new) | I | Extends completeness to `commonTest` files in `business_common_tests` and `ci.yml` allow-list FQCNs; V5 (plan E7.4 is a manual audit) | BT |
| REQ-30 | `bazel test //kmp:business_tests` | `AllBusinessTestsCompletenessTest` after E2.8 | I | Bazel pin proof | Bazel |
| REQ-31 | manual | see section 4 | M | | manual |
| REQ-32 | `{BT}/git/lfs/LfsModeRollbackTest.kt` | `sync_should_skipAllTransferAndKeepGuard_When_modeGuardOnly` (new) | I | plan covers via `LfsEndToEndTest` scenario 2 (E7.3a), `LfsModeIsolationTest`, `LfsModeSwitchPushTest`; defaults assertion only is new | BT |
| REQ-32 | same | `defaults_should_beAutoJvmGuardOnlyAndroidWebIos` (new) | U | | BT |
| REQ-33 | `{BT}/git/lfs/LfsEndToEndTest.kt` | `cloneEditPushPull_should_roundTripBytes_When_fakeServerAndTwoClones` (new) | I | Plan E7.3a | BT |
| REQ-33 | same | `interop_should_matchGitLfsCli_When_gitLfsOnPath` (new) (`Assume`) | I | Real `git lfs pull` against the fake | BT |
| REQ-33 | `{BT}/git/testsupport/FakeLfsServerSelfTest.kt` | `server_should_enforceAuthAndRecordRequests_When_knobsSet` (new) | I | Plan names it `FakeLfsServerSelfTest` (E7.1b, `{BT}/git/testsupport/`); use the plan name | BT (support class in `testsupport/`, no `@Test`) |
| REQ-34 | `{JT}/git/lfs/spike/LfsSpikeDeferredObjectCheckoutTest.kt` | `s5_should_recordGoNoGo_When_objectAbsentAndAuthRequired` (plan E2.1g, spike S5) | I | Zero requests reach `FakeLfsServer`; pointer text left; `status` clean; no pointer-to-pointer; `pre-push` hook outcome recorded (Q5) | JT (unregistered until graduated; result pasted into ADR-001 "Spike results") |
| REQ-34 | `{BT}/git/lfs/LfsFilterActivationTest.kt` (graduated S1, plan E2.8c) | covered by REQ-12 row | I | Graduation of S1; S5 has no graduation task in plan E2.8c (E2.8c graduates S1, S2, S3 only) | BT; **V6** |
| REQ-35 | `{BT}/git/lfs/LfsModeSwitchPushTest.kt` | pointers committed under AUTO, mode switched to GUARD_ONLY, objects absent on server: push refused, origin unchanged (plan E2.3k); objects present: push allowed | I | Error and happy | BT |
| REQ-36 | `{BT}/git/lfs/LfsModeIsolationTest.kt` | two graphs AUTO and GUARD_ONLY in one JVM with `SystemReader` global `filter.lfs.clean=git-lfs clean -- %f`: GUARD_ONLY never executes it (plan E2.3a) | I | Isolation | BT |
| REQ-36 | `{BT}/git/lfs/LfsRawBinaryNeverCommittedTest.kt` | same class re-run under the global external filter config (plan E2.3a) | I | Safety floor under hostile user config | BT |
| REQ-37 | `{BT}/git/GitSyncServiceLfsGuardTest.kt` | `autoModeCleanFilterActiveNeverHoldsWorkingFile`, `autoModeRawInIndexIsHeldAndUnstaged`, `guardOnlyModeHoldsWorkingFile` (plan E1.4e) | I | Three guard-input cases | BT |
| REQ-38 | `{CT}/git/lfs/LfsPointerRepairTest.kt` | CRLF and BOM pointer normalised, oid and size unchanged (plan E2.9a) | U | Repair | CT; register in `AllBusinessTests` and `business_common_tests.srcs` (plan E2.9a says so) |
| REQ-38 | `{BT}/git/GitSyncServiceLfsGuardTest.kt` | `TextAutoMayRewritePointers` note appears for `* text=auto` tracked path (INFERRED test name; plan E2.9a lists the behaviour but no assertion) | I | Warning note | BT; V4 |
| REQ-39 | `{CT}/git/lfs/LfsModeResolverTest.kt` | `effective_should_forceGuardOnly_When_systemGitSparseOrExternalProcessFilter` (new name; plan E2.9b names the class) | U | Resolver | CT; register as above |
| REQ-39 | `{BT}/git/GitSyncServiceLfsGuardTest.kt` | `systemGitPathStillGuarded` (plan E2.9b) | I | Guard applies without adapter | BT |
| REQ-40 | `{BT}/git/lfs/LfsExecBitTest.kt` | pointer blob mode 100755 after convert and push (plan E2.9c) | I | JVM only; Android documented | BT |
| REQ-41 | `{CT}/git/lfs/GitAttributesMatcherTest.kt` | case flipping under `ignoreCase` (plan E1.2d) | U | Matcher | CT |
| REQ-41 | `{BT}/git/lfs/GitAttributesDifferentialTest.kt` | differential run with `core.ignorecase=true` (plan E2.9c says "also exercised"; assertion not spelled out) | I | Oracle | BT; V4 |
| REQ-42 | `{WT}/git/WasmLfsProxyTransportTest.kt` | `proxyDownBeforePushFallsToReadOnly`, `proxyDiesMidUploadLeavesNoBlobOrRef` (plan E4.3e) | W | Error paths | WT; allow-list |

## 3. Test stack, coverage targets, registration checklist

### Stack (VERIFIED sources in plan.md and `research/pitfalls.md` s1.5)
| Layer | Tooling |
|---|---|
| Pure logic (pointer, matcher, guard, policy, resolver) | `kotlin.test` plus `kotest-property` `Arb`/`checkAll` inside `runTest {}`; commonTest |
| Real git | JGit 7.3.0 temp repos, `LfsBareOriginFixtures` over `BareOriginFixtures`; oracle: `git check-attr` and `git lfs` guarded by `Assume` |
| LFS server | `FakeLfsServer` on `com.sun.net.httpserver` (no new dependency) in businessTest |
| Android | Robolectric `androidUnitTest`; Ktor `MockEngine` (not `com.sun.net.httpserver`, Q4) |
| Web | `wasmJsTest` real Chrome via Karma, Ktor `MockEngine`, real OPFS (template `WasmGitWriteServiceMockedIntegrationTest`, file exists: VERIFIED) |
| Proxy Worker | `node --test` (no dependencies), manual |

### Coverage targets (INFERRED; set by this document, not by the plan)
| Area | Target |
|---|---|
| `LfsPointer*`, `GitAttributes*`, `LfsGuard`, `LfsPathPolicy`, `LfsDownloadPolicy`, `LfsWriteCapabilityResolver`, `LfsStagingRefPolicy`, `LfsErrorMapper` | 95 percent line, 90 percent branch, plus properties |
| `GitSyncService` LFS call sites, `LfsGuardCoordinator`, `LfsIndexVerifier`, `LfsTransferService`, `LfsBatchClient` | 85 percent line; every staging entry point has a named test (REQ-4) |
| wasm `WasmLfs*` collaborators | every acceptance criterion in plan E1.6, E3.1, E4.1-E4.5 has a test; line coverage not measured (no wasm coverage tooling in repo, INFERRED) |
| UI composables | Behavioural: every exact user-facing string in plan E1.8, E3.3, E4.5 asserted |
| Mutation-style check | Safety floor: `LfsRawBinaryNeverCommittedTest` must fail if the guard call in `commitLocalChanges` is removed (run once by the implementer: delete the call, expect red, restore) |

### Test-registration checklist (state verified by command; the plan must say this)

| Suite | Path | State today (command) | Plan obligation |
|---|---|---|---|
| `AllBusinessTests` | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt` (`test_class` of `business_tests`, `businessTest/kotlin/BUILD.bazel:56`) | `grep -c 'DomainErrorTest\|SecretRedactionTest'` = 0 | Register every new `{BT}` class and every new `{CT}` class below; ALSO register the existing `DomainErrorTest` and `SecretRedactionTest` (plan E1.3a/E2.3e must stop saying "already registered") |
| `business_common_tests.srcs` | `kmp/src/commonTest/kotlin/BUILD.bazel:102-110` | lists `CaptureTargetResolverTest`, `FakeFileSystem`, `PageSelectionTest` only | Add each new commonTest LFS file at the task that creates it, not in E7.4a; add `error/DomainErrorTest.kt` and `git/SecretRedactionTest.kt`. E7.4a currently names three files; the commonTest set is larger (list below) |
| `AllBusinessTestsCompletenessTest` | `.../businessTest/.../AllBusinessTestsCompletenessTest.kt` | scans businessTest only (header read) | Does not protect commonTest registration (V5) |
| `AllJvmTests` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/AllJvmTests.kt` (`test_class`, `jvmTest/kotlin/BUILD.bazel:78`, srcs are a `**/*.kt` glob) | `grep -c 'PatLeakageAuditTest\|SteleKitAssetMapperTest'` = 0 | Register both extended classes (plan E2.3e, E3.4c do not); spikes stay unregistered (E2.1b-e, g). No completeness guard exists for jvmTest |
| `AllAndroidUnitTests` | `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt` | `grep -c AndroidGitRepositoryStorageGuardTest` = 0 | Register it (plan E2.5e lists the file but not that this existing class is missing) plus every new `{AT}` class |
| wasm allow-list | `.github/workflows/ci.yml:303-309` ("Run wasmJs browser tests") | 6 `--tests` entries today (`grep -n -- '--tests'`; line 297 is a comment) | Plan E7.4 acceptance: `grep -c -- '--tests.*Lfs' ...` equals 9 (the nine non-Live wasm classes of the Test Registry incl. `WasmGitWriteServiceLfsGuardTest`; VERIFIED by counting the registry). If O2 is NO and `WasmLfsConverterTest` is not built the number is 8; the acceptance text should say so |
| iosTest | `kmp/src/iosTest` (no suite list); runs in `.github/workflows/ci-ios.yml` on macos-latest, path-filtered | exists | `IosGitRepositoryLfsGuardTest` (E5.1a) is not run by Bazel or the Linux CI; state it |
| Bazel android target | `kmp/src/androidUnitTest/kotlin/BUILD.bazel` (`android_unit_tests`) | Ktor mock dep absent (E2.8b adds) | As plan |

commonTest LFS classes that need BOTH an `AllBusinessTests` entry and a `business_common_tests.srcs` entry (counted from the Test Registry, section 6): `LfsPointerTest`, `LfsPointerPropertyTest`, `LfsArbs` (srcs only, no `@Test`), `GitAttributesMatcherTest`, `GitAttributesMatcherPropertyTest`, `LfsPathPolicyTest`, `LfsGuardTest`, `LfsGuardPropertyTest`, `LfsErrorMapperTest`, `LfsRemoteResolverTest`, `LfsStoragePreflightTest`, `LfsDownloadPolicyTest`, `LfsSettingsStoreTest`, `LfsEndpointProxyTest`, `LfsStagingRefPolicyTest`, `LfsWriteCapabilityResolverTest`, `LfsPointerRepairTest`, `LfsModeResolverTest`, plus existing `DomainErrorTest`, `SecretRedactionTest` (19 `@Test` classes and the `LfsArbs` support file; the plan registry lists 20 commonTest-labelled names, one of which, `PatLeakageAuditTest`, is jvmTest). Also INFERRED: any support file these tests import (for example generators beyond `LfsArbs`) must be in `srcs` or Bazel compile fails.

### Commands for the gates (to be run and output shown by the implementer)
- G1: `bazel test //kmp:business_tests` (headless, shows `AllBusinessTestsCompletenessTest`), then for UI-bearing suites `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`. Wrap in `timeout 30m` per CLAUDE.md.
- Android: `./gradlew testDebugUnitTest` and `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android`.
- Web: `./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests "<FQCN>"` (run `./scripts/fix-wasm-karma-tooling.sh` if mocha/webpack error).

## 4. Manual and real-host validation checklist

Automated tests cannot cover these. Each needs owner-supplied inputs (O5). Record results in `research/web-lfs.md` and ADR-001/002.

| # | Check | How | Pass criterion | Closes |
|---|---|---|---|---|
| M1 | CORS preflight on real hosts | From a page served at the deployed origin run `WasmLfsLiveTest` (or `fetch` in DevTools) with `Authorization`: github.com, gitlab.com, Bitbucket, Azure DevOps batch endpoints | github.com and gitlab.com report `CorsBlocked`; Bitbucket and Azure DevOps `DirectCapable` (or the table is corrected) | ADR-002 Accepted; Q3 |
| M2 | Object-storage CORS for action hrefs | After M1 on a CORS-open host, upload a 3 MB file and read the PUT outcome in the Network tab | PUT succeeds from the browser, or the `Direct` row is downgraded in docs | Q3 |
| M3 | Private-repo `media.githubusercontent.com` with a PAT | Throwaway private repo with one LFS PNG, fine-grained PAT; `WasmLfsReadResolver` on the media URL | 200 with `Authorization`, or the fallback to Contents API `download_url` works; record which | Q1, requirements Open Question 3 |
| M4 | Quota-exceeded body | Free repo past quota (or a disposable one) on GitHub and GitLab; capture status and body | Bodies recorded; `LfsErrorMapper` substring rule adjusted or confirmed | Q2 |
| M5 | Android device shadow worktree | Real device, SAF graph with LFS PNG and PDF over 100 MB total; clone, edit, sync, kill app mid-download | Bytes identical to desktop clone (sha256), no orphan deletion, storage preflight message shown when low, `lfs_android_enabled` flipped only after pass | Plan Risk Control; E7.5 |
| M6 | Proxy deploy | Deploy `docs/reference/lfs-cors-proxy` to a Cloudflare Worker; set `ALLOWED_ORIGIN`; run `node --test worker.test.mjs`; use SteleKit web against github.com | Upload and download of a 3 MB object through the proxy, health check passes, a request from a wrong origin is refused, README free-tier limits re-verified | ADR-002 mode 2; E4.3 |
| M7 | Converter live run (only if O2 YES) | Throwaway GitHub and GitLab repos with the workflow installed | Staging ref converted and deleted, failure leaves ref, token scope error message correct | E4.4 |
| M8 | Real `git lfs` interop on a user repo | Clone a real LFS repo (first-sync fixture, Open Question 5) with SteleKit, then `git lfs fsck` and `git lfs pull` in the same directory | `git lfs fsck` clean; shared `.git/lfs/objects` layout works | Requirements Open Question 5 |
| M9 | Mobile data / metered | Android on mobile data with defaults | No LFS download before the review sheet decision; deferral shown | REQ-16 |
| M10 | SSH remote | Real SSH remote with `git-lfs-authenticate`, only if spike S3 is GO | Download works, else `LfsNotSupportedHere("ssh")` shown | ADR-001 S3 |
| M11 | Docs and site | Open built site and README | Web cost statement matches ADR-002 consequences text | REQ-28 |

## 5. Remaining plan gaps (round 2)

Round-1 gaps G2 to G13 were re-checked against the current plan. Closed or retracted: G5 (E2.4g `LfsLegacyRawDetectorTest` covers detection, per-graph confirmation and conversion; separate `LfsLegacyRawDetectorTest` dropped), G7 (E5 exists, iosTest), G8 (E6 exists; acceptance commands replace the two doc tests), G11 (E7.1-E7.3 exist; use the plan name `FakeLfsServerSelfTest`), G13 (Effort estimate exists), G10 (rollback covered by `LfsEndToEndTest` scenario 2, `LfsModeIsolationTest`, `LfsModeSwitchPushTest`), G12 (optional, unchanged, left to the implementer). Still real:

| ID | Gap | Required plan change or added test |
|---|---|---|
| V1 | Registration of the five existing extended classes is wrong or missing (headline 1). plan.md line 242 is false. `business_common_tests.srcs` edits are deferred to E7.4a for E2.3d, E2.4a and similar tasks | Rewrite E1.3a Tests line: "`DomainErrorTest` and `SecretRedactionTest` are NOT registered today; add both to `AllBusinessTests` and `business_common_tests.srcs`"; add `AllJvmTests` registration of `PatLeakageAuditTest` and `SteleKitAssetMapperTest` to E2.3e and E3.4c; add `AllAndroidUnitTests` registration of `AndroidGitRepositoryStorageGuardTest` to E2.5e; move each commonTest `srcs` edit into its creating task, and extend E7.4a's list to the 20 commonTest files in section 3 |
| V2 | Android has no test that the index guard refuses a raw tracked blob (E1.5b) | `AndroidGitRepositoryLfsGuardTest` in androidUnitTest, registered; add to E1.5c |
| V3 | Safety floor has a generated-input property only on web (E1.6c); JVM path has examples. E7.1a asserts `neverReceivedRawBinary()` but no test calls it | `LfsRawBinaryNeverCommittedPropertyTest` (BT) and one assertion of `neverReceivedRawBinary()` inside `LfsEndToEndTest` (E7.3a) or a `FakeLfsServerSelfTest` |
| V4 | Tasks with behaviour and no named test: E1.2c `LfsRulesLoader` (ancestor reads, info/attributes), E1.4d `LfsGuardCoordinator` in isolation, E1.6a `WasmLfsRulesSource` nested fetch, E2.4b `isMetered`, E2.4f `LfsTransferring`, E2.6b conflict-screen UI, E3.2b `LfsLocalObjectResolver`, E3.4a asset cards, E4.3b `LfsProxyHealthCheck`, E4.6a web defaults, E2.9a text-attr note, E2.9c ignorecase differential | `LfsRulesLoaderTest`, `LfsGuardCoordinatorTest`, `WasmLfsRulesSourceTest`, `AndroidNetworkMonitorMeteredTest`, `LfsTransferringStateTest`, `ConflictResolutionLfsTest`, `LfsLocalObjectResolverTest`, `AssetItemCardLfsTest`, `LfsProxyHealthCheckTest`, `LfsSettingsSectionWebDefaultsTest` (or fold into `LfsDownloadPolicyTest`); each registered per section 3 |
| V5 | Nothing mechanical detects an unregistered commonTest, jvmTest or androidUnitTest class (the completeness test scans businessTest only); E7.4 is a manual audit | `scripts/check-lfs-test-registration.sh` in businessTest: reads the Test Registry class list and `AllBusinessTests`, `business_common_tests.srcs`, `AllJvmTests`, `AllAndroidUnitTests`, `ci.yml`; or accept the manual audit and state it |
| V6 | E2.8c graduates S1, S2 and S3 only. S5 decides the baseline filter (ADR-001 s3a) yet leaves no regression test after the spike is deleted | Add `LfsDeferredObjectCheckoutTest` (BT) to E2.8c graduating S5 |
| V8 | Round-2 adversarial C2 (read, not re-derived here): on web the service-level guard judges the working file and would report `LfsActionRequired(held)` for a file that Direct or Proxy mode then converts; no test pins the intended web behaviour (held label vs converted) | Add to `WasmLfsDirectWriteTest` or `GitSyncServiceLfsGuardTest` one case: web, Direct mode, raw tracked PNG: no `LfsActionRequired`, pointer committed; web ReadOnly: held with the exact message |
| V7 | Plan Test Registry lacks `GitSyncServiceLfsGuardTest` extra cases added by E2.9a/E2.9b (`systemGitPathStillGuarded`, text-auto note) and `LfsFilterActivationTest` relation to REQ-34 | Cosmetic: list them in the registry |

## 6. Counts (recomputed by command this run)

Command: `awk -F'|' '/^\| REQ-[0-9]+ \|/ && NF>=7 {gsub(/^ +| +$/,"",$5); print $5}' validation.md | sort | uniq -c` over the section 2 table. Total mapping rows: `awk -F'|' '/^\| REQ-[0-9]+ \|/ && NF>=7' validation.md | wc -l` = **189** (a row may name several methods).

| Type | Rows |
|---|---|
| Integration (I) | 76 |
| Unit (U) | 38 (+1 U/PR, +1 U node) |
| Wasm browser (W) | 29 |
| Property (PR) | 23 |
| Robolectric UI | 13 (+2 UI/I) |
| Manual or command (M) | 6 |

Requirements: **42** (`grep -oE '^\| REQ-[0-9]+' validation.md | sort -u | wc -l` = 42; REQ-34 to REQ-42 added this round). Every one has at least one mapping row; REQ-31 maps to manual checks M1 to M11 only. Name markers inside mapping rows: 50 "(plan...)" (taken from the plan) and 157 "(new...)" (added here), counted with `grep -o '(plan[^)]*)'` and `grep -o '(new[^)]*)'` over those rows. Plan Test Registry: 97 distinct class names (`sed -n '/^## Test Registry/,$p' plan.md | grep -oE '`[A-Z][A-Za-z0-9]+(Test|Tests)`' | sort -u | wc -l`). Round-1 validation named 90 classes; 20 of them are not in the registry (the Test Registry search shows count 0 for each in plan.md): 5 are dropped or renamed (`LfsLegacyRawDetectorTest` covered by `LfsLegacyRawDetectorTest`, `LfsFeatureRegistryEntryTest` and `Adr015PointerTest` replaced by acceptance commands, `StubGitRepositoryStageSubdirTest` covered by `GitSyncServiceLfsGuardTest`, `FakeLfsServerSelfTest` renamed to the plan's `FakeLfsServerSelfTest`); `LfsModeRollbackTest` stays as a row with only the defaults assertion new; the other 14 are real gaps listed under V2 to V5 and V8-related rows. Plan structure: 7 epics (`grep -cE '^### Epic'`), 39 stories (`grep -cE '^#### Story'`), 139 tasks (`grep -cE '^##### Task'`), 1393 lines (`wc -l`).

## 7. Implementation readiness gate (round 2)

Each criterion evaluated by a command run this session.

| # | Criterion | Command and result | Verdict |
|---|---|---|---|
| 1 | Every requirement has at least one test | 42 of 42 requirements have rows (counts above). Automated at repo level: all but REQ-31 (manual) and the three REQ-28 acceptance commands. The proxy Worker (REQ-24) is a manual `node --test` run, not CI (plan E4.3d) | PASS with CONCERNS: coverage exists on paper, but the unregistered-class defect (headline 1, V1) means several mapped tests would not run under Bazel, and 14 mapped classes (V2 to V5) have no plan task |
| 2 | No TODO/TBD placeholders in plan.md | `grep -nE 'TODO\|TBD' plan.md \| wc -l` = 0. 12 open questions are tracked as checkboxes (O1 to O7, Q1 to Q5), all owner or spike decisions (`grep -cE '^- \[ \] (O\|Q)[0-9]' plan.md` = 12). Round 1 said 17, which was wrong | PASS |
| 3 | ADRs referenced in plan.md exist | `ls decisions` lists ADR-001 to ADR-004; `ls docs/adr` lists ADR-012, ADR-015 and ADR-019; the one ADR-018 mention in plan.md (line 951) says it is absent. Statuses: all four LFS ADRs Proposed (ADR-001 flips on spikes S1, S2, S5; ADR-002 on E7.6; ADR-003 on O6) | PASS |
| 4 | No BLOCKER in adversarial-review.md | Read at the end of this run: `implementation/adversarial-review.md` is **Round 2**, `Verdict: CONCERNS`, `Counts: 0 Blockers, 6 Concerns, 6 Minors`; `grep -c BLOCKER` = 0 and `grep -cE '^- \[ \] \*\*B[0-9]'` = 0 | PASS (verdict reported as written by the round-2 review) |

**Overall: CONCERNS, implementation may start Phase 1 after the V1 plan correction.** Reasons: (a) V1 is an objective error in plan.md (line 242, and the E2.3e, E2.5e, E3.4c registration omissions) and is cheap to fix before any code; (b) round-2 adversarial C2 and C3 (web working-tree guard versus Direct mode; `LfsMode` defined in Phase 2 but needed by Phase 1) were read from that review and are not re-derived here; V8 records the test the plan lacks for C2; (c) V2 to V5 are test-coverage additions, not design changes. Fix list in priority order: (1) V1; (2) V5 or an accepted manual audit statement in E7.4; (3) V2, V3, V8; (4) V4 tests; (5) V6 graduate S5; (6) address adversarial C1 to C6 as that review lists them.
