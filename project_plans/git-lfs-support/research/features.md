# Research: features and UX for git-lfs-support

Labels: VERIFIED = file opened or command run this session; INFERRED = reasoned, not confirmed; GAP = could not find a source.

## 1. LFS in a Logseq graph, host limits, error codes

**What a graph keeps in LFS (INFERRED, no Logseq doc found)**
- Likely candidates: `assets/` (images, PDFs, audio, video; SteleKit's layout is `assets/images|pdfs|audio|video|documents|files`, `project_plans/asset-management/requirements.md:34-39`, VERIFIED). Markdown in `pages/` and `journals/` is text and is not normally LFS-tracked. `whiteboards/*.edn` and `logseq/` config are text; tracking them in LFS would be unusual.
- Logseq's own stance: a web search for "logseq git lfs ... issue" returned no Logseq issue (GAP). Logseq desktop git auto-commit shells out to system git, so LFS works only if the user has `git lfs` installed (INFERRED, not verified). Do not cite a Logseq source for this.
- Typical `.gitattributes`: `assets/** filter=lfs diff=lfs merge=lfs -text`, or per-extension `*.png *.jpg *.pdf *.mp4 filter=lfs diff=lfs merge=lfs -text`. Detection must parse `.gitattributes` at repo root and in subdirectories (incl. `wikiSubdir`), honour `-filter` / `filter=` unset and last-match-wins (INFERRED from gitattributes semantics).
- Pointer format (spec): `version https://git-lfs.github.com/spec/v1`, `oid sha256:<64 hex>`, `size <bytes>`; about 130 bytes (VERIFIED against the spec search result; https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md).

**Batch API (VERIFIED, https://raw.githubusercontent.com/git-lfs/git-lfs/main/docs/api/batch.md)**
- `POST <lfs-url>/objects/batch`, headers `Accept` and `Content-Type: application/vnd.git-lfs+json`; body `{operation: download|upload, transfers:["basic"], ref:{name}, objects:[{oid,size}], hash_algo}`. LFS URL = `<remote>.git/info/lfs`.
- Per-object errors come back inside HTTP 200: 404 object missing, 409 hash algo mismatch, 410 removed by owner, 422 validation.
- Whole-request codes: 401 credentials needed (header `LFS-Authenticate`); 403 read but no write (upload only); 404 repo not found for user; 422 none of the objects valid; 406 bad Accept; 413 too many objects or request too large; 429 rate limit; 501 not implemented; 507 insufficient server storage; 509 bandwidth limit exceeded. 500/502/503/504 are retryable.
- Upload: server omits `actions` if it already has the object; otherwise `upload` href (+ optional `verify` action) with headers and `expires_at/expires_in`.

**GitHub (VERIFIED, https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-storage-and-bandwidth-usage)**
- Free quota: Free/Pro/Free-org 10 GiB bandwidth and 10 GiB storage; Team/Enterprise Cloud 250 GiB each.
- Downloads count against the repository OWNER's bandwidth, not the downloader's; pushes count owner storage and no bandwidth. Writers do not use personal quota.
- Over quota with no payment method or $0 budget: clones still succeed but return only pointer files; pushes of new LFS objects fail; LFS is disabled until the next calendar month. This is exactly the "unresolved pointer" steady state SteleKit must render gracefully.
- The exact error text GitHub returns when quota is exceeded: GAP (not found; capture from a real over-quota repo or fixture before hard-coding string matches; classify by status code 403/509 plus message substring, with fallback to a generic message).

**GitLab (INFERRED from search snippets only)**
- Repository-size-limit rejections read "Your push has been rejected, because this repository has exceeded its size limit"; HTTP 413 appears for >5 GB pushes through Cloudflare, but LFS is stated as not affected by that limit (https://forum.gitlab.com/t/pushing-a-large-file-5gb-with-lfs/55849). GitLab.com storage tiers: GAP, not fetched. Bitbucket 413 guidance: https://support.atlassian.com/bitbucket-cloud/kb/troubleshooting-git-lfs-push-failure-http-413-error-message/.

**Classification table to implement (proposed)**
| Signal | Domain error | Retry |
|---|---|---|
| 401 on batch | `LfsAuthFailed` (maps to existing `AuthFailed`/`CredentialExpired` UX) | after re-auth |
| 403 on upload | `LfsAuthFailed` read-only variant | no |
| 403/509 with quota text, or 507 | `LfsQuotaExceeded` | no (monthly reset) |
| per-object 404/410 | `LfsObjectMissing(oid)` | no |
| 429 | existing `RateLimited` | yes, Retry-After |
| 413 | split batch into smaller chunks, then `LfsQuotaExceeded`/`PushFailed` | once |
| 422 | `LfsPointerInvalid` | no |
| 5xx / network | existing `NetworkFailure` / `RetryExhausted` | yes |

## 2. How SteleKit renders assets today

- Image blocks: `ImageBlock(url, altText, ...)` calls Coil `AsyncImage(model = url, imageLoader = rememberSteleKitImageLoader(), ...)` with NO `error`, `placeholder` or `onError` parameters (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ImageBlock.kt:47-70`, VERIFIED). Same loader is used by the lightbox (`:92`), `GalleryScreen.kt:183`, `AssetItemCard.kt:73,137`, `AssetDetailScreen.kt:220`, `ImageAnnotationBlockItem.kt:86` (VERIFIED by grep).
- Path resolution: `SteleKitAssetMapper.map()` rewrites `../assets/<f>` to `fileSystem.resolveAssetUri(graphRoot, "assets/$f")` or `file://<root>/assets/<f>` (`ui/components/SteleKitAssetFetcher.kt:34-54`, VERIFIED). Loader built in `rememberSteleKitImageLoader()` (`:79-92`). Despite its filename there is no Fetcher class, only Mappers; `ImageBlock.kt:40` KDoc still mentions a "per-platform SteleKitAssetFetcher" (stale).
- Platform hooks: `FileSystem.resolveAssetUri` default null (`platform/FileSystem.kt:341`); Android SAF override `androidMain/.../platform/PlatformFileSystem.kt:338`; wasm override returns a blob URL from `blobUrlCache` populated by `registerBlobUrl` (`wasmJsMain/.../PlatformFileSystem.kt:871-873`; only caller `WasmMediaAttachmentService.kt:100`, i.e. newly attached files, VERIFIED). So on wasm, synced assets that never got a blob URL have no loadable URI at all today (INFERRED: they fall through to a `file://` URL the browser cannot load).
- A 130-byte pointer saved as `foo.png`: Coil's decoder fails (not an image); with no `error` painter, the block renders empty/zero-height with no message (INFERRED from the missing error handling; not run). Same for the Assets browser cards (thumbnails) and the annotation editor (`AnnotationEditorScreen.kt:400`).
- No existing LFS or pointer awareness anywhere: grep for "lfs" in `kmp/src` and `docs` found nothing; only `project_plans/git-lfs-support/requirements.md` and unrelated hits in other plans (VERIFIED).

**Hook points for "resolve pointer lazily"**
1. New `LfsPointerDetector` (commonMain) used by a new Coil `Mapper`/`Fetcher` registered in `rememberSteleKitImageLoader()` next to `SteleKitAssetMapper` (`SteleKitAssetFetcher.kt:84-90`): sniff the first 200 bytes of the resolved file; if it is a pointer, delegate to an `LfsObjectResolver` (cache lookup, else batch-API download if allowed) and return the real bytes; otherwise pass through. A Fetcher (not a Mapper) is needed because content sniffing needs I/O.
2. Placeholder UI: add `LfsAssetPlaceholder` composable (commonMain `ui/components/`) and wire it through Coil `AsyncImage` `onError`/`SubcomposeAsyncImage` in `ImageBlock.kt:59`, the lightbox `:103`, and `AssetItemCard.kt`. States: Pending (tap to download, shows size from the pointer), Downloading (progress), Failed (reason + retry), Blocked (quota/auth).
3. Web: implement the resolver in wasm via the chosen design (direct `media.githubusercontent.com` fetch for read, or user proxy; see web-lfs.md) and feed results into `registerBlobUrl` + `resolveAssetUri` (`wasmJsMain/.../PlatformFileSystem.kt:871`), so one code path serves rendering.
4. Cache location: JVM/Android resolved content must live in the working tree (that is what smudge does) and `.git/lfs/objects/<aa>/<bb>/<oid>`; wasm uses OPFS (`wasmJsMain` has `OpfsInterop.kt`, `OpfsGraphSlug.kt`, VERIFIED to exist).

## 3. Existing git sync UX to reuse

**Code facts (VERIFIED)**
- `GitSyncService.sync()` pipeline: offline check, vault check, config load, detached-HEAD, stale lock, flush, then step 5 commit (`git/GitSyncService.kt:252-345`). The conflict-marker guard sits at step 5 right after `status()` and before `stageSubdir()`: `findFilesWithConflictMarkers` filters `modifiedFiles + untrackedFiles` to `.md` (`:321-330`, `:478-488`). The LFS raw-binary guard belongs in the same spot: for every modified/untracked path matching an LFS `.gitattributes` rule whose content is not a pointer, refuse (or, on JVM/Android once clean filter exists, convert). `resolveConflicts` also commits resolved files (`:603+`, commit `5d00781169`), so the guard must be applied there too (INFERRED).
- First-sync gate: `FirstSyncConfirmation(settings)` keyed by `git_first_sync_confirmed_<graphId>` = `"remote/branch"`, pending flag, previous-branch memo (`git/FirstSyncConfirmation.kt:11-38`); `sync()` returns `GitError.FirstSyncReviewPending` for Automatic triggers while pending (`GitSyncService.kt:256-260`). Reuse the same pattern: add an `LfsFirstSyncDecision` persisted via the same `Settings` store keyed `git_lfs_decision_<graphId>`.
- `SyncState` (`git/model/SyncState.kt:9-29`): Idle, Fetching, MergeAvailable, Merging, Pushing, Committing, CredentialVaultLocked, ConflictPending, JournalMergeReady, Error(GitError), CredentialExpired, LocalChangesPending, RateLimited, Success. Add `LfsTransferring(done, total, bytes)` and optionally `LfsActionRequired(reason)`; per demo-help-feature-registry plan each new `SyncState` member is a registry candidate (see section 4).
- `DomainError.GitError` is a sealed interface at `error/DomainError.kt:73-162` with variants incl. `AuthFailed`, `RateLimited(retryAfterSeconds)`, `FileTooLarge(path,sizeBytes,maxBytes)` (`:108`), `NotSupported(platform)` (`:91`). User-message mappers are exhaustive `when`s at `DomainError.kt:~300-330` (generic) and `~360-375` (`toSyncErrorMessage`); each new error needs entries in both (VERIFIED they exist; line numbers approximate).
- Settings storage: `GitConfig` (`git/model/GitConfig.kt:10-26`) persisted in SQL table `git_config` (`SteleDatabase.sq:982-998`), repo `SqlDelightGitConfigRepository`. Adding a column requires: `.sq` edit, `MigrationRunner.all` entry, regenerated SQLDelight sources under `kmp/src/generated/sqldelight/` (CLAUDE.md rules). Lighter alternative: per-graph keys in `platform.Settings` as `FirstSyncConfirmation` does (no schema change).
- Setup UI: `ui/screens/git/GitSetupScreen.kt` with `GitSetupStep1CloneMode ... Step5TestAndSave`, `BranchRepairSheet.kt`, `ConflictResolutionScreen.kt`; badge `ui/components/SyncStatusBadge.kt`; banner `ui/components/git/GitDetectionBanner.kt`.

**Proposed settings placement**
| Setting | Where | Default |
|---|---|---|
| `lfsMode` (OFF / GUARD_ONLY / AUTO) | new `GitConfig.lfs: LfsSettings` (or Settings keys) | AUTO on JVM, GUARD_ONLY on web/iOS |
| `lfsAutoDownload` (never / on-demand / all) | same | on-demand on Android and web, all on desktop |
| `lfsMaxAutoBytes` (size cap per object and per sync) | same | 25 MiB per object, 200 MiB per sync on mobile (open question 2 in requirements; proposal only) |
| `lfsWifiOnly` (Android/iOS) | same, uses existing `NetworkMonitor` (`GitSyncService` uses `networkMonitor.isOnline`); metered detection is a new capability (GAP: not checked whether NetworkMonitor exposes metered) | true |
| `lfsProxyUrl` (web only) | web-only Settings key, shown in GitSetupStep5 / settings only when `.gitattributes` has LFS rules | empty |
Surfaces: a new "Large files (Git LFS)" section in GitSetupStep4Branch/Step5 (shown only after LFS detection) and the same fields in the per-graph git settings dialog.

**First-sync behaviour for LFS repos**
- Detect before the first checkout: fetch/clone, read `.gitattributes` from the fetched tree (JGit: read blob from FETCH_HEAD tree; web: Git Data API / contents API for `.gitattributes`). If any `filter=lfs` rule: show the LFS review step (reuse BranchRepairSheet-style bottom sheet) listing: tracked patterns, number of pointer files, summed `size` from pointers (parsed from the tree blobs, each ~130 B so cheap) as "estimated download", host quota note (GitHub downloads count against the repo owner's bandwidth), and choices "Download all now / On demand / Don't download (placeholders only)". Persist the choice; mark review pending through `FirstSyncConfirmation`-style flag so automatic sync does not pull LFS before the user chooses.
- Clone must not be blocked on LFS; with shallow clones (`DEFAULT_CLONE_DEPTH = 50`, `GitConfig.kt`) smudge only HEAD-tree pointers (INFERRED).

**User-facing messages (proposed text, one line each)**
| Condition | Message |
|---|---|
| Unresolved pointer | "This file is stored with Git LFS and has not been downloaded. Tap to download (2.4 MB)." |
| Quota exceeded | "Your Git host's LFS quota is used up, so this file cannot be downloaded or uploaded until the quota resets or is raised. Your text notes still sync." |
| Missing object | "The Git host no longer has this file (it was removed or never uploaded). Ask whoever pushed it to push it again." |
| Auth failure | "Git LFS rejected your credentials. Reconnect your account to download or upload large files." (read-only token on upload: "Your token can read but not write LFS files.") |
| Proxy not configured (web) | "Downloading large files in the browser needs an LFS proxy. Add one in Sync settings, or use the desktop or Android app. Text notes sync normally." |
| Raw binary blocked by guard | "Sync paused for assets/photo.png: this repository stores that path with Git LFS and SteleKit cannot upload it here yet. The file was not committed. Move it out of the tracked folder or sync from desktop." |

## 4. Related plans and registry (VERIFIED paths)

- `/home/tstapler/Programming/stelekit/project_plans/git-smart-sync/` (requirements.md, research/, implementation/)
- `.../project_plans/git-sync-resilience/` (requirements.md, decisions/, design/, implementation/, research/); source of `RetryExhausted`, `ShallowHistoryInsufficient`, clone-depth state.
- `.../project_plans/git-setup-ux/` (requirements.md, research/, implementation/)
- `.../project_plans/asset-management/requirements.md` (asset layout, `../assets/...` links must stay Logseq-compatible, `:161`).
- `.../project_plans/web-git-writeback/` incl. `decisions/ADR-017-wasm-git-repository-actual-reachability.md`, `design/ux.md`; and `docs/adr/ADR-015-wasm-git-data-api-write-back.md` ("no proxy" at `:15`, `:32`, deferred server-side proxy `:49-53`) which a web LFS proxy must supersede or qualify. Note a different ADR-015 exists: `project_plans/stelekit/decisions/ADR-015-android-wal-connection-pool.md`; cite the docs/adr one by full path.
- `.../project_plans/git-lfs-support/{decisions,implementation}` are empty; `research/` had no sibling files when this was written (requirements references `research/web-lfs.md`, not present on disk, VERIFIED).
- PR #413, branch `docs/demo-help-feature-registry-plan` (`gh pr view 413 --json files`, 12 files, all under `project_plans/demo-help-feature-registry/`): requirements.md, research/{architecture,features,pitfalls,stack}.md, decisions/ADR-001-registry-shape.md, ADR-002-derive-vs-declare.md, ADR-003-enforcement-strictness.md, implementation/{plan,validation,pre-mortem,adversarial-review}.md.
  - Registry shape (ADR-001): `FeatureEntry(id, title, kind, audience, covers, demoPage, sitePage, sources, exempt)` per-area files under `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/featureregistry/` (`ScreenFeatures.kt`, `SettingsFeatures.kt`, `CommandFeatures.kt`, `SyncFeatures.kt`, `FeatureRegistry.kt`).
  - An LFS entry would go in `SyncFeatures.kt` with `kind = SYSTEM`/`SETTING`, `covers` = any new `sync:LfsTransferring`-style candidates and `settings:` entries; plan.md decision D1 marks git-sync MARKETED (owner-resolved 2026-10-11), so LFS inherits MARKETED unless exempted and needs `demoPage` + `sitePage` or a dated `Exemption` (`PENDING_DEMO_WITH_ISSUE`). Candidate counts (14 `SyncState`) in that plan change when a state is added (plan.md A1.1.2 recount). Registry code is not on `feat/git-sync-branch-fix` yet; coordinate ordering.
- Docs/site to update when LFS ships (VERIFIED existence): `docs/tasks/git-sync.md`, `docs/tasks/git-sync-ux.md`, `docs/ux/journey-map.md`, `docs/adr/` (new ADR superseding/qualifying ADR-015 if a proxy), `site/src/content/docs/user/` (no git-sync page exists today: getting-started, outliner, backlinks, etc. only; add `git-sync.mdx` or an LFS section), demo graph `demo-graph/pages` has no git page (`ls` returned nothing), `README.md` features list.
- `scripts/generate-readme.sh` only inlines `docs/install.md` between `<!-- BEGIN_INSTALL -->`/`<!-- END_INSTALL -->` in README.md (`:1-40`); it does not generate a feature list, so the README feature text is hand-edited and install.md needs a note only if `git lfs` becomes a prerequisite (it should not on JVM/Android if JGit LFS is used).

## 5. iOS (VERIFIED, `kmp/src/iosMain/kotlin/dev/stapler/stelekit/git/IosGitRepository.kt:1-67`)

- Entire class is a stub. Every method returns `DomainError.GitError.NotSupported("iOS").left()` except `isGitRepo` and `hasDetachedHead`, which return `false`. Its KDoc says kgit2 integration is not yet validated ("Task 3.4"). No libgit2, no shell, no network, no file I/O.
- Consequences: iOS cannot clone, commit, push, or fetch today, so the raw-binary-commit risk cannot occur through `GitRepository` on iOS, and LFS pointers on iOS can only arrive via a graph folder synced by another means (iCloud/Files).
- Recommended stance (proposal): guard-only plus read-only pointer handling. (a) Ship the common pointer detector and placeholder UI on iOS (it is commonMain) so pointer files render as the "not downloaded" placeholder rather than blank. (b) Keep `NotSupported("iOS")` for git ops; add a regression test asserting the stub never reaches commit. (c) Native LFS download on iOS is out of scope until kgit2/libgit2 sync itself ships; if iOS git is later implemented it must call the shared guard before `stageSubdir`. Decision needed from owner on requirements Open Question 4.

## Gaps to close before planning
1. Exact GitHub/GitLab quota-exceeded response bodies (capture from fixtures or a real over-quota repo).
2. Whether `NetworkMonitor` exposes metered/Wi-Fi state.
3. Whether JGit 7.3.0 ships usable `org.eclipse.jgit.lfs` artifacts for the pinned version (`kmp/build.gradle.kts:146,175,342` pins `7.3.0.202506031305-r` without lfs) and Android-compatibility (belongs to stack research).
4. Real Logseq behaviour with LFS repos (no source found).
