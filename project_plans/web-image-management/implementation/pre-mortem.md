# Pre-mortem: web-image-management

**Date**: 2026-11-01
**Exercise**: it is three weeks after launch and the web image work has failed or hurt users. Each story below is a plausible cause of death, with the early signal, the guard in the plan, and a residual risk. Likelihood and impact are INFERRED (L/M/H).

| # | Failure story | L | I | Early signal | Guard in plan | Residual |
|---|---|---|---|---|---|---|
| 1 | **Pushes silently stop working for everyone.** Images became dirty entries without bytes; the existing commit path returns `CommitFailed` for the whole batch (VERIFIED `WasmGitWriteService.kt:509-513`), so notes stop syncing too. | H if sequenced wrong | H | push error rate in manual test M4 after E2 | ordering note, `WebBinaryDirtyTracking.enabled` flag, E8.1 before release, task 8.1.1c | low once flag discipline holds |
| 2 | **Raw JPEG pushed to a repo that tracks `*.jpg` with LFS** because the guard skipped store-resident files (`?: continue`, VERIFIED `:172-173`). | M | H (repo bloat, privacy, history rewrite) | none visible until the remote shows a big blob | fail-closed rewrite 8.1.1c; `guard_should_FailClosed_When_ContentUnlocatable`; `LfsRawBinaryNeverCommitted*` extended to store-resident paths | low |
| 3 | **Thumbnails get committed to the user's repo and host folder** (`.stelekit/cache/images/*.jpg`) because one write path forgot `LocalOnlyPaths`. | M | H | repo diff shows `.stelekit/cache` | single predicate, structural test over all write paths, property test | low-medium: new write paths added later could forget; structural test is the guard |
| 4 | **Images do not render on web** because Coil cannot load `blob:` URLs, found only after E3/E4 are built. | M | H | S1 failing | S1 first; E4.3 conditional task; render test | medium until S1 runs |
| 5 | **Safari users cannot attach at all** (no main-thread `createWritable`), unnoticed because CI is Chrome only. | M | M-H | M1 on Safari | S3, D2 worker path, capability probe, `StorageUnavailable` state, manual matrix | medium: iOS Safari remains the least tested |
| 6 | **Tab crashes on image-heavy graphs** (reconcile loads host images into `bytesCache`; indexing loads bytes; blob URLs never revoked). | M | H | reload loops on mobile | JS-owned bytes, 1.3.1c, 1.4.1, LargeGraph test, LRU revoke | medium: JS-side memory is not capped by the plan |
| 7 | **Users lose images**: browser-only graph, no host folder, Safari evicts after inactivity, or "clear site data". | M | H (irrecoverable) | support reports | persist() on first attach, storage panel, banner copy, zip export shortcut, M2 | medium: platform behavior, can only be communicated not prevented |
| 8 | **Remote lazy fetch hammers the git host** (hundreds of requests per page, rate limiting, token flagged). | M | M | 403/429 in network log | concurrency cap 3, backoff, negative cache, setting to disable | low |
| 9 | **Share target breaks the PWA update flow** (SW changes cache an old shell again, as in prior incidents noted in the SW comments). | L-M | H (users stuck on old bundle) | stale version after deploy | isolated POST branch, never cache POST, extended JS tests, M3, staged rollout behind manifest only | low-medium |
| 10 | **Host-folder sync regresses for notes** after the 1.3.1c reconcile change (1,953-line file). | M | H | `HostDirectorySync*Test` failures or data-loss reports | confined change, differential test, full suite run | medium: high-risk file |
| 11 | **LFS extraction breaks JVM/Android transfer** (PR #514 code moved to commonMain). | L-M | H (just shipped) | `JvmGitRepositoryLfsTransferTest` red | delegate pattern, keep JVM tests as contract, small steps in 9.1.1a/b | low |
| 12 | **Converter mode leaks user data or leaves staging refs** | L | M | stale `stelekit/lfs-inbox/*` refs | opt-in with confirmation, ref validation regex in workflow, failure leaves ref surfaced with link, B5 | medium: depends on real-host run |
| 13 | **Security**: an SVG attachment executes script in the app origin via an "open original" feature. | L | H (token theft) | none | task 3.2.1c, P17 structural test, display only via Skia/`<img>` | low |
| 14 | **ML downloads fill the quota** and make attach fail with QuotaExceeded. | M | M | near-quota banner | quota pre-check, models in local-only, clear-caches button, models counted in storage panel | low-medium |
| 15 | **Wasm tests flake or silently do not run** (stale `continue-on-error`, Karma tooling), so regressions ship. | M | M | green CI with no wasm test count | 11.1d verifies counts and that the job is blocking; show output in PR | medium: recurring in repo history (memory notes) |
| 16 | **Wiring gate fails late** because the wasm `Main.kt` is not a recognized composition root. | H if untouched | L (CI red) | `WiringCompletenessTest` | task 5.2.1c edits `WiringRoots` | low |
| 17 | **Scope fatigue**: E7/E9 Converter dominate cost and block the common path. | M | M | burn vs table > 25 percent | independent epics, E7 last, Direct/ReadOnly shippable without Converter, overrun checkpoint | low |
| 18 | **Name/encoding bugs**: images with spaces/`#`/Unicode break markdown links on one platform. | M | L-M | broken image icons for specific names | AttachmentPolicy property tests, URL-encoding test across web and JVM | low |

## Top mitigations to schedule first
1. S1 (Coil blob), S3 (Safari OPFS), S4 (heap) before epics E1/E4.
2. E8.1.1c fail-closed guard and the dirty-tracking flag before any user-visible release of E2.
3. `LocalOnlyPaths` predicate and structural test in the first E1 story.
4. A fresh-context reviewer pass on this plan before Phase 5 (the adversarial review here was inline).

## Stop conditions (kill or pause switches)
- If S1 and the Coil `Fetcher` hook both fail: pause E4 and ask the owner for D3 option B (DOM overlay) before building more UI.
- If S3 shows Safari cannot do either main-thread or worker writes: ship web image attach as Chromium/Firefox only with an explicit Safari disabled state, and record it in the parity matrix.
- If E1.3.1c regresses any `HostDirectorySync*Test`: revert the reconcile change and keep the old path behind the flag until the differential test passes.
