# Pre-mortem: git-lfs-support

**Date**: 2026-10-11 | **Frame**: it is April 2027 and this feature failed or caused harm. Why?
**Inputs**: `plan.md` (1292 lines, 122 tasks), `validation.md` (round 2), `decisions/ADR-001..004`, `requirements.md`, `adversarial-review.md` (round 1, read for context only). Registration facts below were checked by command this session (see validation.md headline 1).
**Scoring**: Severity S and Likelihood L each 1 to 5; rank = S x L. Likelihood is INFERRED judgement, not measured. "In plan" cites a plan task id; "NEW" items are collected as N1..N16 in the last section and are not in plan.md yet.

## Ranking

| # | Failure | S | L | Rank |
|---|---|---|---|---|
| 1 | New tests never run in CI | 4 | 4 | 16 |
| 2 | A raw binary is committed anyway | 5 | 3 | 15 |
| 3 | Android shadow worktree loses or corrupts user data | 5 | 3 | 15 |
| 4 | Guard false positives block or nag every sync | 4 | 3 | 12 |
| 5 | JGit filter behaviour changes under us | 4 | 3 | 12 |
| 6 | Auto-download burns the owner's GitHub LFS bandwidth | 4 | 3 | 12 |
| 7 | Users stop trusting the sync warnings | 3 | 4 | 12 |
| 8 | Half-pushed state: pointers without objects | 5 | 2 | 10 |
| 9 | Proxy token leak or abuse | 5 | 2 | 10 |
| 10 | CORS assumption invalidated by a host change | 3 | 3 | 9 |
| 11 | Web tab crash on a large asset | 3 | 3 | 9 |
| 12 | Legacy-raw files create permanent warnings | 3 | 3 | 9 |
| 13 | Worker template rots | 2 | 4 | 8 |
| 14 | Deferred-object checkout makes an unauthenticated request or corrupts status | 4 | 2 | 8 |

## Stories

### 1. New tests never run in CI (rank 16)
**Narrative.** The plan extends five existing test classes and adds about 75 new ones. Under Bazel a class that is not in the suite never runs. `DomainErrorTest` and `SecretRedactionTest` live in commonTest and are in neither `AllBusinessTests` nor `business_common_tests.srcs`; `PatLeakageAuditTest` and `SteleKitAssetMapperTest` are not in `AllJvmTests`; `AndroidGitRepositoryStorageGuardTest` is not in `AllAndroidUnitTests` (all VERIFIED by `grep -c`, validation.md headline 1). The plan calls `DomainErrorTest` "already registered" (plan.md:242). The implementer trusts that, the PR is green because the LFS error-text and token-leak assertions simply do not execute, and a later refactor breaks the leak audit with no red build. The same happens to a commonTest LFS class forgotten in `business_common_tests.srcs`: `AllBusinessTestsCompletenessTest` scans businessTest only. iosTest runs only in `ci-ios.yml`, path-filtered.
**Early warning.** `bazel test //kmp:business_tests` test count does not rise by the number of added classes; `grep -c -- '--tests.*Lfs' .github/workflows/ci.yml` is not 9 (8 if the converter is not built).
**Mitigation in plan.** Partial: every task lists its registration files; E7.4a/E7.4b audit by hand; E2.8c graduates spikes.
**NEW.** N1 (fix the false claim and register the five classes), N2 (mechanical registration audit).

### 2. A raw binary is committed anyway (rank 15)
**Narrative.** The safety floor is "never commit a raw binary at a tracked path". It holds at the service (`LfsGuardCoordinator`), at the repository (`LfsIndexVerifier`) and on web (`createBlob`). A leak still happens through a path nobody guarded: a new caller of `stageSubdir`/`git add` added later, the system-git adapter (ADR-012) used for sparse graphs, an AUTO-mode graph whose clean filter silently does not run (ADR-003 s2a skips the working-file check in AUTO and relies on `stagedLfsViolations`), an Android graph with `lfs_android_enabled` true but a failed filter config read-back, or the converter's staging-ref exception. One 40 MB PDF lands in history; removing it needs a force-push and a history rewrite that SteleKit cannot do (`git lfs migrate` is out of scope).
**Early warning.** `lfs.guard.held` is always 0 on a graph with rules; `LfsRawBinaryNeverCommittedTest` fails only under the hostile global-config run; a blob over 1024 bytes at a tracked path in any dogfood repo (`git rev-list --objects --all` scan).
**Mitigation in plan.** E1.4d (three call sites), E1.5a/b (index verifier), E1.6b (web, `createBlob` re-assert), E2.3a (readback of `cleanFilterActive`, passthrough filter for GUARD_ONLY), E2.9b (system git forces GUARD_ONLY), E1.5c/E1.6c (walk every blob), E4.4a (staging ref scope is a closed pattern). Gap: Android has no index-guard test (validation V2); the JVM property test over generated repos is absent (V3).
**NEW.** N3 (enumerate callers of the staging functions and fail the build if a new one bypasses the coordinator), N4 (a post-push audit scan on the pushed range, logged and surfaced), plus validation V2 and V3.

### 3. Android shadow worktree loses or corrupts user data (rank 15)
**Narrative.** Android syncs a SAF graph through a shadow worktree that is text-only today (`GitShadowWorktree.kt:196,245,126`). The binary-safe rewrite (E2.5a) changes how every file is mirrored. A bug mirrors a PNG with replaced bytes (S4 shows corruption is real for non-UTF-8), or `deleteOrphanedShadowFiles` removes a smudged file before the flush actor writes it to SAF, or a revoked SAF grant mid-sync is read as "file deleted" and the deletion propagates to a commit. The user's only copy of a photo is in the graph folder. The flag flips on after a device pass that did not cover revocation plus airplane mode combinations.
**Early warning.** Shadow-vs-SAF sha256 mismatch in a debug check; `lfs.guard` logs a commit with deletions of binary paths; reports of black thumbnails after sync.
**Mitigation in plan.** E2.5a (streamed byte copy, size+mtime change detection), E2.5b (`LfsMaterialisedSet` protects from orphan deletion until flushed), E2.5c (preflight counts copies), E2.5d (`lfs_android_enabled` default false), E2.5e (binary and flush tests, 100 MB heap-bound test), E7.5 device checklist (rows 3, 5, 6), E7.5a flips the flag only after the owner records it.
**NEW.** N5 (quarantine instead of delete: orphan and revocation cases move files to a recoverable folder for N days and never commit a binary deletion without a second signal), N6 (sha256 round-trip verification of every mirrored binary, abort on mismatch, before any commit).

### 4. Guard false positives block or nag every sync (rank 12)
**Narrative.** The guard is only as right as the in-house `.gitattributes` matcher. A real repo uses a macro (`[attr]lfs filter=lfs diff=lfs merge=lfs -text`), a quoted pattern, nested overrides, or a pattern whose `**` behaviour differs from git; or a text file under a tracked folder is 1500 bytes (over the 1024 pointer limit, so `RawBinary` by size). The guard holds the file; ADR-003 s6 provides no override. The file never syncs, the badge stays amber, and the user concludes sync is broken. The empty-file exemption (ADR-003) turns out to differ from `git lfs clean` on some version, so a 0-byte file is committed as raw where the host rejects it.
On web the service guard judges the working file too, so a raw tracked PNG may show "held" in the UI while Direct mode would convert it (round-2 adversarial C2, not re-derived here; validation V8).
**Early warning.** `held` count stays non-zero across consecutive syncs for the same paths; the differential test's documented-divergence list grows; an issue titled "file held but git add works".
**Mitigation in plan.** E1.2a/b (macros, quoted, `**`), E1.2d (property tests, naive oracle), E1.2e (differential against JGit and `git check-attr`, empty-file check under `Assume`), hold-back not block (ADR-003 s3, E1.4d), E1.8 sheet with remedies.
**NEW.** N7 (explain each hold: show the matching `.gitattributes` file and line and the reason; a "copy diagnostics" action), N8 (run the differential test in CI with real `git` and `git lfs` present, not `Assume`-skipped; add a corpus of real-world attribute files).

### 5. JGit filter behaviour changes under us (rank 12)
**Narrative.** JGit is pinned to 7.3.0 and the plan adds `org.eclipse.jgit.lfs:7.3.0.202506031305-r`. A routine bump of one artifact (Renovate or a manual upgrade, or the Android line which the Bazel comment says uses a different version) changes `BuiltinLFS.register()` semantics, the default of `filter.lfs.useJGitBuiltin`, or the unauthenticated smudge fallback. ADR-001 depends on spikes S1, S2, S5 that are deleted or left unregistered after the decision. Pointers get committed as pointer-to-pointer, or checkout starts making unauthenticated requests, and nothing fails because the guard tests run with the filter configured the same way.
**Early warning.** A dependency PR touches only one of `org.eclipse.jgit` and `org.eclipse.jgit.lfs`; `LfsFilterActivationTest` starts failing after a bump (only if it exists and is registered).
**Mitigation in plan.** E2.1 spikes with go/no-go, E2.3a (config read back, builtin names only), E2.8c graduates S1 and S2 into `LfsFilterActivationTest` and `LfsCloneNoCheckoutTest`, E2.8a pins lfs in `MODULE.bazel`. Gap: S5 is not graduated (validation V6).
**NEW.** N9 (assert in a test that the jgit and jgit.lfs versions are equal, in Gradle and Bazel, and group them in the dependency bot config), plus V6.

### 6. Auto-download burns the owner's GitHub LFS bandwidth (rank 12)
**Narrative.** Desktop defaults are `ALL` with no caps (plan Settings table). The owner's graph repo keeps `assets/` in LFS on a free GitHub account (1 GB/month bandwidth; INFERRED from public GitHub docs, plan F section 1 cites quota but Q2 says bodies are not captured). Three devices each pull after every edit session; a bulk image import on one device makes the other two download it, plus a fresh clone downloads everything. The quota runs out mid-month, every device then gets `LfsQuotaExceeded`, and web viewing breaks as well because media downloads count too.
**Early warning.** `lfs.download.bytes` per month trends over about 60 percent of the host quota; first `LfsQuotaExceeded` appears.
**Mitigation in plan.** E2.4a-e (caps, `ON_DEMAND`, metered/wifi-only, first-sync review blocks automatic pulls until decided), E3.1b (LRU OPFS cache avoids re-download), E2.2d (quota mapping, retry never hammers).
**NEW.** N10 (a persisted bandwidth ledger per host with a soft monthly budget: warn at 80 percent and switch to `ON_DEMAND`; default `maxSyncBytes` non-zero on desktop).

### 7. Users stop trusting the sync warnings (rank 12)
**Narrative.** Users without LFS see nothing. Users with LFS see an amber "held" badge, "Your Git host's LFS quota is used up", "Open this graph on desktop or Android". Each message is accurate but the next action is unclear, so users dismiss them. A real failure (a held file that is the only copy of a note attachment) is then ignored for weeks. Separately, ADR-003 s4 says an unchanged legacy-raw file is not a violation, while plan E2.4g puts `LegacyRawPending` into `LfsActionRequired`, so the badge can stay amber with no user-caused reason.
**Early warning.** Issues asking how to clear the warning; the held set unchanged after more than N syncs.
**Mitigation in plan.** E1.8 (sheet with remedies), error table (one-line texts), E6.2 site page with UNVERIFIED labels, E2.4d review sheet.
**NEW.** N7 (explain each hold, with a concrete one-tap remedy where one exists), N11 (decide and test: `LegacyRawPending` is surfaced only in the review sheet, not as a persistent amber badge, consistent with ADR-003 s4; and a graph with no `filter=lfs` rule renders no LFS UI and logs nothing).

### 8. Half-pushed state: pointers without objects (rank 10)
**Narrative.** A second client clones and sees pointers whose objects never reached the host. Routes: push order bug; the mode switched to `GUARD_ONLY` after pointers were committed under AUTO; Android with the flag turned off; a web direct write where the object PUT succeeds but the later tree or ref call fails and the pointer is then committed by another path; or the host garbage-collects an unreferenced uploaded object before the ref update lands.
**Early warning.** `LfsObjectMissing` on a second device; `git lfs fsck` reports missing objects on a clone.
**Mitigation in plan.** E2.3g (upload before `git.push()`), E2.3i `LfsPushOrderingTest`, E2.3k `LfsModeSwitchPushTest` (mode-independent scan 3 before every push), E4.2 request ordering with the file left dirty on failure (E4.2d), E4.3e `proxyDiesMidUploadLeavesNoBlobOrRef`.
**NEW.** N12 (post-push read-back: after a successful push, batch-probe every new pointer's object and surface a loud error plus a one-click re-upload; also run the pointer-without-object check on web before commit).

### 9. Proxy token leak or abuse (rank 10)
**Narrative.** Web write on github.com and gitlab.com needs a proxy that sees the user's PAT (ADR-002). A user deploys the template but sets `ALLOWED_ORIGIN` wrongly or copies a classic broad-scope PAT into SteleKit; or points SteleKit at a proxy someone else shared; or the Worker is modified to log headers. The Origin check does not stop non-browser clients, so protection rests on requiring `Authorization` and the host allowlist. A leaked `repo`-scope PAT exposes every private repo of the owner, not just the graph.
**Early warning.** Proxy logs show requests from origins other than the SteleKit site; a classic `ghp_` token configured against a proxy.
**Mitigation in plan.** ADR-002 (user-owned only, no SteleKit-hosted proxy, O1), E4.3b (health check origin match, acknowledgment gates Save, warning copy), E4.3c (exact host allowlist, no IP literals, HMAC-signed object hrefs, `redirect: "manual"`, no header logging), E4.3d (SSRF and auth tests, manual run), E2.3e/E4.x token-only-on-batch tests.
**NEW.** N13 (token hygiene: recommend and detect a fine-grained single-repo token, warn on classic broad tokens when a proxy is configured, require re-acknowledgment when the proxy URL changes, and assert in `worker.test.mjs` that no response or log line contains the `Authorization` value).

### 10. CORS assumption invalidated by a host change (rank 9)
**Narrative.** Mode selection assumes github.com and gitlab.com send no `Access-Control-Allow-Origin` on the LFS batch endpoint (research/web-lfs.md; ADR-002 is only Proposed and E7.6 real-host checks need O5). Either the host starts sending CORS (the probe flips to Direct, but the object-storage `href` PUT still lacks CORS and every upload fails after a successful batch), or `media.githubusercontent.com` changes private-repo behaviour (Q1 unverified) and the read path breaks for private repos.
**Early warning.** `lfs.write.mode` flips for a host without a release; PUT `TypeError` after a 200 batch; `WasmLfsLiveTest` red.
**Mitigation in plan.** E4.1 runtime probe with 24 h TTL, E4.5 resolver precedence with ReadOnly fallback, E6.2 UNVERIFIED labels until E7.6, E7.6/E4.7 live checks.
**NEW.** N14 (treat a PUT-time CORS failure as a mode downgrade for that host, cached with the probe result; and a scheduled CI canary that sends unauthenticated `OPTIONS` preflights to the four hosts and opens an issue when headers change; no credentials needed).

### 11. Web tab crash on a large asset (rank 9)
**Narrative.** The 50 MiB web cap bounds the download, not the decoded bitmap. A 40 MiB PNG at 12000 x 12000 decodes to over 500 MB in the wasm heap or Skia, and the tab dies; on Safari the OPFS quota is smaller than Chrome's.
**Early warning.** Chrome tab "Aw, Snap" reports; OPFS `estimate()` usage near quota.
**Mitigation in plan.** E3.1c (streaming OPFS store, never a whole `ByteArray` for LFS), E3.1b (storage estimate refusal), E4.6a (cap, Download anyway), E3.3 placeholders with Blocked states.
**NEW.** N15 (a decode-size guard: read the image header, refuse or downscale above a pixel budget; one real-Chrome test with a 50 MiB, high-resolution image).

### 12. Legacy-raw files create permanent warnings (rank 9)
**Narrative.** A repo adds `assets/** filter=lfs` after years of raw images. With the clean filter active, unchanged tracked raw files appear modified, become `LegacyRawPending`, and sit in the held list until the user confirms conversion. A user who chooses not to convert sees a standing warning; a user who confirms triggers a mass conversion and a 1 GB upload.
**Early warning.** `LfsRepoSurvey.legacyRawCount` is large on first sync; confirmation rate low.
**Mitigation in plan.** E2.4g (never auto-converted, per-graph confirmation), ADR-003 s4, E2.4c/e (survey and review sheet).
**NEW.** N11 (where and how often legacy-raw is surfaced) and N10 (the conversion upload counts against the budget and is shown in size before confirming).

### 13. Worker template rots (rank 8)
**Narrative.** `docs/reference/lfs-cors-proxy/worker.js` is tested only by a manual `node --test` (E4.3d, "not in CI"). Cloudflare changes limits or `wrangler` config, a security fix to the SSRF allowlist is needed, and users keep running the copy they deployed. Maintenance falls on the owner for a feature few people use.
**Early warning.** README free-tier figures older than a quarter; template version in `__health` not compared with SteleKit's expected version.
**Mitigation in plan.** E4.3c (version in health, README limits flagged "re-verify"), ADR-002 consequences state the token-exposure cost.
**NEW.** N16 (run `node --test` in CI on `docs/reference/lfs-cors-proxy/**`; SteleKit refuses a proxy whose health `version` is below the minimum and says "update your Worker template").

### 14. Deferred-object checkout makes an unauthenticated request or corrupts status (rank 8)
**Narrative.** Download policy defers objects (ON_DEMAND, caps, first-sync "none"). JGit's smudge for a missing object tries its own unauthenticated download, which fails on private repos and bypasses caps, or leaves pointer text that the clean filter then hashes into a pointer-to-pointer. Round-1 adversarial review raised this as B3; the plan now has spike S5 (E2.1g) with a NO-GO fallback (`jgit://stelekit/lfs/*` filters).
**Early warning.** Any request reaching `FakeLfsServer` without `Authorization` in S5; `status` reporting pointer files modified.
**Mitigation in plan.** E2.1g/E2.1f (go/no-go), ADR-001 s3a, E3.2b on-demand resolver downloads first then checks out a single path.
**NEW.** V6 (graduate S5 into a regression test, since the decision depends on it).

## NEW mitigations required (not yet in plan.md)

| ID | Recommendation | Addresses | Where to add |
|---|---|---|---|
| N1 | Correct plan.md:242 and register the five existing extended classes (`DomainErrorTest`, `SecretRedactionTest` in `AllBusinessTests` plus `business_common_tests.srcs`; `PatLeakageAuditTest`, `SteleKitAssetMapperTest` in `AllJvmTests`; `AndroidGitRepositoryStorageGuardTest` in `AllAndroidUnitTests`) in the tasks that extend them | 1 | E1.3a, E2.3e, E2.5e, E3.4c |
| N2 | Mechanical registration audit (`LfsRegistrationAuditTest`, validation V5) or a CI shell step that lists unregistered `*Test.kt` in commonTest, jvmTest, androidUnitTest against their suite files; plan E7.4a to list all 20 commonTest files | 1 | E7.4 |
| N3 | Structural test that every call to `stageSubdir`/`add` goes through `LfsGuardCoordinator` (ast-grep or reflection over `GitSyncService` and adapters), so a new caller fails the build | 2 | E1.4e |
| N4 | Post-push audit scan of the pushed range for non-pointer blobs at tracked paths; log and set `LfsActionRequired` | 2 | E1.5 |
| N5 | Android: quarantine, never delete, for orphan and SAF-revocation cases; no commit of a binary deletion on a single signal | 3 | E2.5b |
| N6 | Android: sha256 round-trip check of mirrored binaries before commit | 3 | E2.5a |
| N7 | Held-file explanations show the matching `.gitattributes` file and line, the reason, and a copy-diagnostics action | 4, 7 | E1.8a |
| N8 | Differential test runs in CI with real `git` and `git lfs` installed (not skipped) plus a corpus of real attribute files | 4 | E1.2e |
| N9 | Test that `org.eclipse.jgit` and `org.eclipse.jgit.lfs` versions are equal in Gradle and Bazel; group them in the dependency bot | 5 | E2.8a |
| N10 | Per-host LFS bandwidth ledger with a soft monthly budget and a non-zero default sync cap on desktop; show conversion size before confirming | 6, 12 | E2.4a |
| N11 | Decide and test: `LegacyRawPending` appears in the review sheet only (ADR-003 s4), and a graph with no `filter=lfs` rule renders no LFS UI | 7, 12 | E2.4g, E1.8a |
| N12 | Post-push read-back probe of new pointers' objects, with one-click re-upload; pointer-without-object check before web commit | 8 | E2.3k, E4.2b |
| N13 | Token hygiene for proxy mode (fine-grained token advice, classic token warning, re-ack on URL change, Worker never echoes `Authorization`) | 9 | E4.3b, E4.3d |
| N14 | PUT-time CORS failure downgrades the host's write mode; scheduled unauthenticated `OPTIONS` canary against the four hosts | 10 | E4.1, E4.5 |
| N15 | Decode-size guard (pixel budget) plus a high-resolution real-Chrome test | 11 | E3.1c, E3.3 |
| N16 | CI job for `node --test docs/reference/lfs-cors-proxy/`; refuse proxies with a health `version` below the minimum | 13 | E4.3d |
| V2-V6 | From validation.md section 5: Android index-guard test, JVM generated-repo property test, untested-task tests, registration audit, graduate S5 | 2, 5, 14 | per validation.md |
