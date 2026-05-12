# Changelog

## [0.24.0](https://github.com/tstapler/stelekit/compare/v0.23.0...v0.24.0) (2026-05-12)


### Features

* **security:** paranoid mode with LUKS2-style keyslots and hidden volumes ([#73](https://github.com/tstapler/stelekit/issues/73)) ([574da4e](https://github.com/tstapler/stelekit/commit/574da4e4c8919e6a6292287472b8b022758c55a9))


### Bug Fixes

* **perf:** fix slow block creation at bottom of journal on Android (BUG-008) ([#78](https://github.com/tstapler/stelekit/issues/78)) ([639c3c3](https://github.com/tstapler/stelekit/commit/639c3c3b99d9199bd751853d2fe4d192c649446c))
* **wasm:** use [@sqlite](https://github.com/sqlite).org/sqlite-wasm 3.46.1-build1 (bare 3.46.1 not on npm) ([7a00909](https://github.com/tstapler/stelekit/commit/7a00909bdef52a920efd65d038de98bbadad70f9))

## [0.23.0](https://github.com/tstapler/stelekit/compare/v0.22.1...v0.23.0) (2026-05-08)


### Features

* **web:** OPFS-backed SQLite driver + local dev script ([#74](https://github.com/tstapler/stelekit/issues/74)) ([22516f5](https://github.com/tstapler/stelekit/commit/22516f54b3d2ce061c017cda2d97c65edf2d32bc))

## [0.22.1](https://github.com/tstapler/stelekit/compare/v0.22.0...v0.22.1) (2026-05-04)


### Bug Fixes

* **build:** lower jvmToolchain from 25 to 21 to match CI runtime ([aac9538](https://github.com/tstapler/stelekit/commit/aac953819efd913f2aa9b8b7a29ee2610022aee6))
* **ci:** add always() guards to release jobs for workflow_dispatch ([63fcb45](https://github.com/tstapler/stelekit/commit/63fcb45377d44e566fe4d72e52e5a20153f3113a))

## [0.22.0](https://github.com/tstapler/stelekit/compare/v0.21.0...v0.22.0) (2026-05-04)


### Features

* **search:** two-panel layout, FTS performance, precomputed backlink counts ([#70](https://github.com/tstapler/stelekit/issues/70)) ([651d006](https://github.com/tstapler/stelekit/commit/651d0061857556e52bd825a28b2377df2820ce4d))


### Bug Fixes

* **lint:** use mutableIntStateOf for selectedTab in ReferencesPanel ([dec72d3](https://github.com/tstapler/stelekit/commit/dec72d302ae72c5ecbc4e6871df8e1772c28a877))

## [0.21.0](https://github.com/tstapler/stelekit/compare/v0.20.0...v0.21.0) (2026-05-03)


### Features

* **git:** two-way git sync with in-app conflict resolution ([#63](https://github.com/tstapler/stelekit/issues/63)) ([7269c4e](https://github.com/tstapler/stelekit/commit/7269c4eea6abf6ca41ef1d6120c2058405cde33a))


### Bug Fixes

* remove hardcoded local JDK path from gradle.properties ([#69](https://github.com/tstapler/stelekit/issues/69)) ([ca27eda](https://github.com/tstapler/stelekit/commit/ca27eda1b6615ae7cb5913dc64ac9643ab047335))

## [0.20.0](https://github.com/tstapler/stelekit/compare/v0.19.4...v0.20.0) (2026-05-03)


### Features

* **ui:** add Unlinked Mentions tab to ReferencesPanel ([53ac786](https://github.com/tstapler/stelekit/commit/53ac78682e7e1ad560f432cb8ff2f2f03a9af2cb))
* **voice:** rich markdown formatting, transcript pages, current-page insertion, UX improvements ([#66](https://github.com/tstapler/stelekit/issues/66)) ([8155563](https://github.com/tstapler/stelekit/commit/8155563fbe10279d30554b7433a6f744978dbeab))

## [0.19.4](https://github.com/tstapler/stelekit/compare/v0.19.3...v0.19.4) (2026-05-03)


### Bug Fixes

* rethrow CancellationException in loadJournalsImmediate ([#62](https://github.com/tstapler/stelekit/issues/62)) ([222a638](https://github.com/tstapler/stelekit/commit/222a63831095cb1ecde6fa4301f1c585701eb8a6))
* **voice:** fix on-device STT always falling back to Whisper on Android ([#64](https://github.com/tstapler/stelekit/issues/64)) ([2f778ef](https://github.com/tstapler/stelekit/commit/2f778ef866350fb8a29e695a884f7e8644ca20af))

## [0.19.3](https://github.com/tstapler/stelekit/compare/v0.19.2...v0.19.3) (2026-05-01)


### Bug Fixes

* address Copilot review comments ([d297890](https://github.com/tstapler/stelekit/commit/d297890ba79162a46351d283c07a6594b13fd029))
* **bench:** move FileProvider to kmp androidMain so test APK gets correct authority ([3704288](https://github.com/tstapler/stelekit/commit/37042881b6d9ac494a99874aaa148b0d35c4bb73))


### Performance Improvements

* **android:** PRAGMA tuning, MEDIUM benchmark, SAF I/O overhead test ([52ee431](https://github.com/tstapler/stelekit/commit/52ee43118e260987a9eb9930f3179a4027c4e00f))
* **android:** SAF performance — race condition fix, DocumentFile elimination, lazy Phase 3 ([574c14a](https://github.com/tstapler/stelekit/commit/574c14a64f288e056882c19e9bfd8aaf6db5b5de))
* **android:** SAF performance stories 1-3 ([d2ab252](https://github.com/tstapler/stelekit/commit/d2ab2521814112d3f3aa784c4222978bf3970edd))
* **android:** SAF shadow copy eliminates Binder IPC for Phase 3 reads ([e26fa89](https://github.com/tstapler/stelekit/commit/e26fa89103b69ce496c7843edbbd98622d8aae8b))

## [0.19.2](https://github.com/tstapler/stelekit/compare/v0.19.1...v0.19.2) (2026-04-29)


### Bug Fixes

* **wasm:** fix wasmJs compilation errors breaking GitHub Pages deploy ([bde6814](https://github.com/tstapler/stelekit/commit/bde681481adf15a9c429b6a9b29c7eeb4cd3619c))

## [0.19.1](https://github.com/tstapler/stelekit/compare/v0.19.0...v0.19.1) (2026-04-28)


### Bug Fixes

* **android:** defer sanitizeDirectory off startup critical path; add native file pickers for export ([453f75c](https://github.com/tstapler/stelekit/commit/453f75c7563374ea1305b3f577ed132213b2b833))

## [0.19.0](https://github.com/tstapler/stelekit/compare/v0.18.1...v0.19.0) (2026-04-28)


### Features

* **homebrew:** add Casks/stelekit.rb for proper macOS GUI install ([afd2613](https://github.com/tstapler/stelekit/commit/afd26136a4b1f04b38b69a5e8eee2bbc9afe5600))


### Bug Fixes

* **homebrew:** symlink SteleKit.app to ~/Applications so it appears in Finder/Launchpad ([b006a51](https://github.com/tstapler/stelekit/commit/b006a51d508e17f79661713659b38f3014d48f4c))

## [0.18.1](https://github.com/tstapler/stelekit/compare/v0.18.0...v0.18.1) (2026-04-28)


### Bug Fixes

* **journals:** fix journals showing Untitled, missing today's journal, and Claude API response deserialization ([16d2fc7](https://github.com/tstapler/stelekit/commit/16d2fc7cb7ff16cb65fb8f1e877ffefa6ee776a3))

## [0.18.0](https://github.com/tstapler/stelekit/compare/v0.17.0...v0.18.0) (2026-04-28)


### Features

* **search:** visit-recency ranking, exact-title guarantee, FTS repair, and latency benchmarks ([#52](https://github.com/tstapler/stelekit/issues/52)) ([86da88e](https://github.com/tstapler/stelekit/commit/86da88e58416880fa58c7fdeccf5890937a208e0))


### Bug Fixes

* **ci:** repair pages workflow so the site actually deploys ([#53](https://github.com/tstapler/stelekit/issues/53)) ([782448b](https://github.com/tstapler/stelekit/commit/782448bcc0fb0054721f0b6a54c24110336aac5a))

## [0.17.0](https://github.com/tstapler/stelekit/compare/v0.16.3...v0.17.0) (2026-04-27)


### Features

* **editor:** Cmd+K with selected text opens search pre-filled ([412e055](https://github.com/tstapler/stelekit/commit/412e0559f0dd405e52d33d84aeedf51026812334))


### Bug Fixes

* **detekt:** add LocalOpenSearchWithText and GraphLoader pageUuid to baseline ([0c888fa](https://github.com/tstapler/stelekit/commit/0c888fabbeff051c0428baf27a3bb6f80cbfad2d))
* **editor:** Cmd+K only consumes for link wrap when text is selected ([602c783](https://github.com/tstapler/stelekit/commit/602c7835bcda7f498dd0d70e8bffecdba56adefa))

## [0.16.3](https://github.com/tstapler/stelekit/compare/v0.16.2...v0.16.3) (2026-04-27)


### Bug Fixes

* **startup:** track warm reconcile job in backgroundIndexJob ([833296a](https://github.com/tstapler/stelekit/commit/833296a6620b0cf6322dd16ae007486952442d8a))
* **writer:** surface writeFile exceptions and fix misleading success log ([ca898c7](https://github.com/tstapler/stelekit/commit/ca898c77098c992f877fcaa75f3cd7ea26008ab1))

## [0.16.2](https://github.com/tstapler/stelekit/compare/v0.16.1...v0.16.2) (2026-04-27)


### Performance Improvements

* **android:** fix 27s startup, wrong SAF directory creation, and memory pressure ([fe94e7d](https://github.com/tstapler/stelekit/commit/fe94e7d0d48ebcc7e4b84c3ecedf0bc0490ccda8))
* **cache:** don't populate page caches during bulk background indexing ([f8a9c10](https://github.com/tstapler/stelekit/commit/f8a9c106bfa31b473afd279ae6d8799c5b177404))
* **startup:** warm-start fast path in loadGraphProgressive + Arrow docs ([e251ada](https://github.com/tstapler/stelekit/commit/e251adade93eee5b58b0eac73eb521cad6f7425c))

## [0.16.1](https://github.com/tstapler/stelekit/compare/v0.16.0...v0.16.1) (2026-04-27)


### Bug Fixes

* **ux:** surface live logs on all loading screens ([f1d7de5](https://github.com/tstapler/stelekit/commit/f1d7de5940bf68e3153abc30fc92cc960f3221e8))

## [0.16.0](https://github.com/tstapler/stelekit/compare/v0.15.0...v0.16.0) (2026-04-27)


### Features

* **arrow:** full Arrow 2.x integration — typed errors, optics, saga, and resilience ([016bdf5](https://github.com/tstapler/stelekit/commit/016bdf532dc93e322e2e91e3585f49d3549ff730))


### Bug Fixes

* **build:** resolve Either/Result type mismatches after merging main ([1103a14](https://github.com/tstapler/stelekit/commit/1103a14fdeb5a301397f58bcb34402addacc6841))

## [0.15.0](https://github.com/tstapler/stelekit/compare/v0.14.0...v0.15.0) (2026-04-27)


### Features

* **android:** add emulator smoke tests and Roborazzi screenshot tests ([9eaa880](https://github.com/tstapler/stelekit/commit/9eaa88030d40f40fea6e651882898f91a3ad2ccc))
* **android:** defense-in-depth quality layer for SAF permissions and loading state ([8ce5db1](https://github.com/tstapler/stelekit/commit/8ce5db122de758df0ab41c6e29e143a3cc29647b))
* **perf:** BlockHound, LRU cache, query-plan CI gate, pg_stat_statements analogue, CPU flamegraphs ([d782dfb](https://github.com/tstapler/stelekit/commit/d782dfbecc5c92a50a4cc6f0dcdbfe0d5b6ed47c))
* **tools:** replace flamegraph.pl with committed Kotlin implementation ([30828de](https://github.com/tstapler/stelekit/commit/30828de8ad857003cdf325ec92f5ceb406f6e16e))


### Bug Fixes

* **android:** use fully-qualified SteleKitApplication name in manifest ([1317782](https://github.com/tstapler/stelekit/commit/1317782492c4e982309d303f6a9a5b7591240b42))
* **ci:** restore upload-artifact@v7, remove perl guard from local script ([1d67b49](https://github.com/tstapler/stelekit/commit/1d67b491ad4f1c8e18f700a50df66b2bc7e008f6))
* **ci:** switch Roborazzi to record mode — goldens differ across machines ([5b88085](https://github.com/tstapler/stelekit/commit/5b880851e7d69529d6766bb7a44e70b43327d389))
* **perf+ci:** eliminate GC pressure from QueryStatsCollector, fix benchmark CI ([6ea1b54](https://github.com/tstapler/stelekit/commit/6ea1b54a5be7b1c437e3cdfb6da0600787b5aa35))
* **test:** broaden TC-E2E-002 to cover onboarding and recovery screens ([07351bc](https://github.com/tstapler/stelekit/commit/07351bc48cd737833c4a242e5f1cf5a558f1ec3d))
* **test:** change isFullyLoaded default to false to fix race in loading test ([7c467eb](https://github.com/tstapler/stelekit/commit/7c467ebf2fe6811a4b4bfe89b52b3535b77243b8))
* **tools/flamegraph:** set workingDir to repo root so relative paths resolve ([714a4a0](https://github.com/tstapler/stelekit/commit/714a4a0ccf06c94428b84efc9e49df05aadfb16d))


### Performance Improvements

* **instrumentation:** cache parseSql by identifier; benchmark % change + direction ([ea4236e](https://github.com/tstapler/stelekit/commit/ea4236ec9355d218c3c4bda70da66c5075314429))

## [0.14.0](https://github.com/tstapler/stelekit/compare/v0.13.0...v0.14.0) (2026-04-26)


### Features

* **android:** widget, tile, and share-sheet capture entry points ([e8394ae](https://github.com/tstapler/stelekit/commit/e8394ae18b4d2e89fa87b948df9f195b03903f1e))


### Bug Fixes

* **bench:** fix pageCount=0 in PR comments and clarify benchmark titles ([c66d0b7](https://github.com/tstapler/stelekit/commit/c66d0b79c84f9447337a34ee98e37226d96feacf))
* **ci:** pull --rebase before push in benchmark workflows ([1979f4f](https://github.com/tstapler/stelekit/commit/1979f4f2497fea7719d564c21a39c4b090c66204))
* **ci:** remove stray conflict marker from benchmark.yml ([a61a6d7](https://github.com/tstapler/stelekit/commit/a61a6d761ab280b9b21d57f1939176f369b29da3))
* **ios:** replace String.format() with KMP-safe formatting helpers ([d4853d0](https://github.com/tstapler/stelekit/commit/d4853d0fe6dc51f6ab8576527b00827d6dcfe891))
* **test:** make loadGraph set isFullyLoaded=false synchronously ([038637a](https://github.com/tstapler/stelekit/commit/038637a5d0c0006c26164d82196def75a9425394))

## [0.13.0](https://github.com/tstapler/stelekit/compare/v0.12.0...v0.13.0) (2026-04-26)


### Features

* **db:** enforce @DirectRepositoryWrite on SpanRepository write methods ([768d616](https://github.com/tstapler/stelekit/commit/768d61690d3ae2fb0fe9bc0c7213aa4314a681a7))
* **detekt:** rule enforcing @DirectRepositoryWrite on all *Repository write methods ([f645a9a](https://github.com/tstapler/stelekit/commit/f645a9ad39a4dd4230ff2f27f446037593ab0d94))
* **detekt:** rule enforcing @DirectRepositoryWrite on all *Repository write methods ([c3a20c9](https://github.com/tstapler/stelekit/commit/c3a20c9f81e9bee2d2a941a6c9e27e6376c88037))


### Bug Fixes

* **desktop:** resolve SQLITE_BUSY and WindowInsets crash on startup ([f081157](https://github.com/tstapler/stelekit/commit/f0811574ea8e1028583655785e6bb6d7549b1756))
* **desktop:** resolve SQLITE_BUSY and WindowInsets crash on startup ([7b93052](https://github.com/tstapler/stelekit/commit/7b93052f8aaab5f3655f14b552834f7f6bb1c72b))
* **fdroid:** enforce latest version, add metadata, replace placeholder icons ([#42](https://github.com/tstapler/stelekit/issues/42)) ([cbc41ff](https://github.com/tstapler/stelekit/commit/cbc41ff9f39bb27f082c39bc795f8e8e1b3d23cb))

## [0.12.0](https://github.com/tstapler/stelekit/compare/v0.11.0...v0.12.0) (2026-04-26)


### Features

* **android/voice:** on-device STT and LLM via SpeechRecognizer + Gemini Nano ([#27](https://github.com/tstapler/stelekit/issues/27)) ([a275dbe](https://github.com/tstapler/stelekit/commit/a275dbe912fa5838f3715f44d9ed5115a27eb931))

## [0.11.0](https://github.com/tstapler/stelekit/compare/v0.10.0...v0.11.0) (2026-04-26)


### Features

* **android:** extract Application class and share GraphManager across process lifecycle ([0e7915e](https://github.com/tstapler/stelekit/commit/0e7915e95a34f214ba9e3a19906e8a051b19fa24))


### Bug Fixes

* **search:** create-page in link picker now inserts link and appears first ([2a85c6d](https://github.com/tstapler/stelekit/commit/2a85c6dc176324feaf50df0179ca646ac15f2000))


## [0.10.0](https://github.com/tstapler/stelekit/compare/v0.9.5...v0.10.0) (2026-04-25)


### Features

* **fdroid:** fix repo version history and show version in Settings ([#33](https://github.com/tstapler/stelekit/issues/33)) ([9bb8a77](https://github.com/tstapler/stelekit/commit/9bb8a773b966cd7f79cf4c0a764a73cfb0b918b9))

## [0.9.5](https://github.com/tstapler/stelekit/compare/v0.9.4...v0.9.5) (2026-04-25)


### Bug Fixes

* **resilience:** fix loading screen hang + isolate test settings ([dc1b51b](https://github.com/tstapler/stelekit/commit/dc1b51be926992ca348b9958d15f4721acb98bd9))


### Performance Improvements

* **android:** decompose Phase 3 chunk writes to allow HIGH-priority preemption ([#26](https://github.com/tstapler/stelekit/issues/26)) ([b5a51ab](https://github.com/tstapler/stelekit/commit/b5a51ab82e52b38ceb98662412956be692114803))

## [0.9.4](https://github.com/tstapler/stelekit/compare/v0.9.3...v0.9.4) (2026-04-24)


### Bug Fixes

* **cache:** rename LruCache → SteleLruCache to bypass K2 compiler bug ([ea70c86](https://github.com/tstapler/stelekit/commit/ea70c865aa8765d4c394304101e94b563dca7b2f))

## [0.9.3](https://github.com/tstapler/stelekit/compare/v0.9.2...v0.9.3) (2026-04-24)


### Bug Fixes

* **cache:** replace coroutine Mutex with synchronized in LruCache ([17345e4](https://github.com/tstapler/stelekit/commit/17345e46528fb5e89243f5bee7c6b57e4655b9dc))

## [0.9.2](https://github.com/tstapler/stelekit/compare/v0.9.1...v0.9.2) (2026-04-23)


### Bug Fixes

* **cache:** strengthen LruCache.class workaround for Linux CI ([59ae017](https://github.com/tstapler/stelekit/commit/59ae01737e0761cbc5f88dc913b2e798d771276b))

## [0.9.1](https://github.com/tstapler/stelekit/compare/v0.9.0...v0.9.1) (2026-04-23)


### Bug Fixes

* **cache:** work around Kotlin 2.3.10 K2 compiler bug in LruCache ([ba30932](https://github.com/tstapler/stelekit/commit/ba309320c4110970a5795b3325f0763324ed408d))

## [0.9.0](https://github.com/tstapler/stelekit/compare/v0.8.1...v0.9.0) (2026-04-23)


### Features

* **observability:** comprehensive instrumentation, SLO monitoring, and perf export ([a9770de](https://github.com/tstapler/stelekit/commit/a9770de16026a95bc7d7ef28b7dc839df7f8070a))
* **search:** AND semantics, field boosting, recency + graph distance ranking ([#22](https://github.com/tstapler/stelekit/issues/22)) ([8d23437](https://github.com/tstapler/stelekit/commit/8d2343751450d7c37098c8375a628a5564aa56c0))


### Bug Fixes

* **sync:** prevent data-loss races on mobile reload and external conflict handling ([#21](https://github.com/tstapler/stelekit/issues/21)) ([105928d](https://github.com/tstapler/stelekit/commit/105928d782b9d775ddb4a142206b41474f92c5bb))

## [0.8.1](https://github.com/tstapler/stelekit/compare/v0.8.0...v0.8.1) (2026-04-22)


### Bug Fixes

* **macos:** use Homebrew openjdk and clean build for macOS 26 compat ([#18](https://github.com/tstapler/stelekit/issues/18)) ([d90968d](https://github.com/tstapler/stelekit/commit/d90968d94e202f5cdf705a36ef8eafec618239c8))

## [0.8.0](https://github.com/tstapler/stelekit/compare/v0.7.1...v0.8.0) (2026-04-22)


### Features

* **cache:** add LruCache, RequestCoalescer, and platform-aware cache sizing ([05e982a](https://github.com/tstapler/stelekit/commit/05e982ac56ef5683bf1ee3c96b275ea428284537))
* **ci:** generate flamegraph PNG and embed it inline in benchmark PR comment ([7425ee8](https://github.com/tstapler/stelekit/commit/7425ee8ce151e89081aef3af0e50f89dcc8ee7ce))
* **ci:** upload flamegraph PNG via GitHub uploads API instead of repo commit ([d4ead21](https://github.com/tstapler/stelekit/commit/d4ead21083aef83e17af0f1f574fcc4e36f37042))
* **db:** add priority channels and bulk SavePages to DatabaseWriteActor ([c9dee34](https://github.com/tstapler/stelekit/commit/c9dee34f0deaa5453593a12b20bec316b1ee3c3d))
* **db:** add spans table to schema and extend migration infrastructure ([c216f0f](https://github.com/tstapler/stelekit/commit/c216f0fd7c98cf3202d262c229aad68f39580bf8))
* **db:** enforce all SQL writes through @DirectSqlWrite + RestrictedDatabaseQueries ([7a880d1](https://github.com/tstapler/stelekit/commit/7a880d12908324490d10654d853b5cd88952dd92))
* **performance:** add OpenTelemetry tracing, JFR benchmarks, and frame metrics ([5e55e1a](https://github.com/tstapler/stelekit/commit/5e55e1afd5767dfc0ea8298d8315b20ddd3dd015))
* **repository:** savePages bulk write, page LRU cache, block domain model cache ([0f31ee0](https://github.com/tstapler/stelekit/commit/0f31ee0ab544b96b62cf969085862851b29685ae))
* **ui:** add rename-page to command palette ([f898fff](https://github.com/tstapler/stelekit/commit/f898fff2c5af43cb198fd4d2b32bd1295458a0a5))
* **ui:** expand performance dashboard and wire OTel into compose layer ([ed045f4](https://github.com/tstapler/stelekit/commit/ed045f40d1cc85ca5daa12c8db889bb114499a63))


### Performance Improvements

* **db:** add pooled JDBC SQLite driver and PlatformDispatcher.DB abstraction ([e4001e9](https://github.com/tstapler/stelekit/commit/e4001e9600e8b38f448550fa6f56a5aa586926ee))
* **graph-loader:** bulk chunk writes, skip-unchanged pages, single-pass mtime scan ([a6261cb](https://github.com/tstapler/stelekit/commit/a6261cbe4ce2a39361b3b781584ed4cfa30829cf))

## [0.7.1](https://github.com/tstapler/stelekit/compare/v0.7.0...v0.7.1) (2026-04-20)


### Bug Fixes

* **android:** fix graph directory not found and write failures for sub-directory SAF paths ([4e42f5f](https://github.com/tstapler/stelekit/commit/4e42f5f381cb35c2c348a3ef26eb645077f6a2ee))
* **db:** DatabaseWriteActor owns its scope, survives graph switch ([51045d1](https://github.com/tstapler/stelekit/commit/51045d168c3e0d9baa6f6c9d7343d40c2db1a233))
* **db:** remove scope param, recover from unexpected exceptions in actor loop ([53cb69b](https://github.com/tstapler/stelekit/commit/53cb69b128321ea122ae953cc6fec66783e8d1cf))

## [0.7.0](https://github.com/tstapler/stelekit/compare/v0.6.0...v0.7.0) (2026-04-20)


### Features

* **site:** apply stone brand palette, fix copy, replace emoji icons, brand favicon ([257e82c](https://github.com/tstapler/stelekit/commit/257e82c374462507b2c1829d9031f2d1f6dc6826))


### Bug Fixes

* **android:** fix hasStoragePermission=false after SAF folder pick for sub-directory paths ([c711e85](https://github.com/tstapler/stelekit/commit/c711e85ccd35b24839fe16758f428e0e8f4f5cb2))
* **brew:** copy desktop entry to user share dir on Linux install ([cf0c8af](https://github.com/tstapler/stelekit/commit/cf0c8af858d7028f3bcd1e15561c477fec6e698f))

## [0.6.0](https://github.com/tstapler/stelekit/compare/v0.5.0...v0.6.0) (2026-04-20)


### Features

* **site:** apply brand palette, add logo, add F-Droid install instructions ([d3f9bcf](https://github.com/tstapler/stelekit/commit/d3f9bcf3c0c9c5d68077f304c58ef681b82fc790))


### Bug Fixes

* **android:** fix permission recovery — spinner forever, silent failures, no logs ([5a9feae](https://github.com/tstapler/stelekit/commit/5a9feae1e576e06d15166231a327dbf19e4c9a8c))
* **brew:** fix Homebrew tap, centralize install docs, add README sync CI ([#14](https://github.com/tstapler/stelekit/issues/14)) ([8665537](https://github.com/tstapler/stelekit/commit/86655378490e4f2cb5544676d209ddd914e78338))
* **fdroid:** install fdroidserver via pip to fix androguard API 36 parse error ([f5fe954](https://github.com/tstapler/stelekit/commit/f5fe954ff073b22d0a2e4f2d56ea78af1861f5ec))
* **release:** use find instead of glob for artifact upload ([9d3055f](https://github.com/tstapler/stelekit/commit/9d3055f6a602089eb63b9f3d3635877c33c15b33))

## [0.5.0](https://github.com/tstapler/stelekit/compare/v0.4.0...v0.5.0) (2026-04-20)


### Features

* navigate to today's journal on app startup ([#12](https://github.com/tstapler/stelekit/issues/12)) ([262de3c](https://github.com/tstapler/stelekit/commit/262de3c0a64d5c78723452ccbd1915fa815835e6))


### Bug Fixes

* **ci:** fix artifact upload condition — release-please not in needs chain ([aa33116](https://github.com/tstapler/stelekit/commit/aa331160671d4b6b68be7ca8db25b5f9c2c4f851))
* **ci:** fix fdroid workflow race condition, missing permission, Node version ([04a286b](https://github.com/tstapler/stelekit/commit/04a286bb919797c86ec183f3d6e1741f93d756f9))

## [0.4.0](https://github.com/tstapler/stelekit/compare/v0.3.2...v0.4.0) (2026-04-19)


### Features

* add fdroid self-hosted repo workflow and config ([8134007](https://github.com/tstapler/stelekit/commit/8134007bda742662176b26d60438a1c141fcef42))
* add fdroid self-hosted repo workflow and config ([a3768fe](https://github.com/tstapler/stelekit/commit/a3768fe74ad53b2c05b49d6200ac749ab619ac63))
* **voice:** Story 1 — Android voice capture → Whisper STT → journal insert ([815ed32](https://github.com/tstapler/stelekit/commit/815ed32811f086eb0000300a562afd782de57a08))


### Bug Fixes

* **ci:** rebase onto origin/main before Homebrew formula push ([8d92c7f](https://github.com/tstapler/stelekit/commit/8d92c7f676dd30498bf12cf869733e071df6ac58))
* pin Node.js to 24 LTS in pages workflow ([ffa739e](https://github.com/tstapler/stelekit/commit/ffa739ec003ea6fb0c67701e7606605148b18c25))
* **ui:** persist graph path before switchGraph to fix spinner-forever bug ([13cc5ea](https://github.com/tstapler/stelekit/commit/13cc5ea94a48702ab64acad27dccc3955951a256))
* upgrade Node.js to 24 LTS in GitHub Pages workflow ([470df18](https://github.com/tstapler/stelekit/commit/470df18823fe22e1001139f16e7789a47e089b3e))
* upgrade Node.js to lts/* in pages workflow ([4b9790a](https://github.com/tstapler/stelekit/commit/4b9790a40454ca00ddc7a6b35c89229bbfb07290))
* **voice:** fix iOS framework link check — remove JVM-only APIs from commonMain ([5c2437e](https://github.com/tstapler/stelekit/commit/5c2437ead13652b563d8f6dd041cd4dc5737f8c0))

## [0.3.2](https://github.com/tstapler/stelekit/compare/v0.3.1...v0.3.2) (2026-04-19)


### Bug Fixes

* **highlighting:** highlight multi-word unlinked page names in view mode ([e717fed](https://github.com/tstapler/stelekit/commit/e717fedc790aea98ce0c2ba22478a6a9343fa0f3))
* **highlighting:** highlight multi-word unlinked page names in view mode ([ec54c7d](https://github.com/tstapler/stelekit/commit/ec54c7d2fa6d98c4f677ea3d602dbd6c5d4f09cb))

## [0.3.1](https://github.com/tstapler/stelekit/compare/v0.3.0...v0.3.1) (2026-04-19)


### Bug Fixes

* **desktop:** fix JFileChooser crash and add installer/tap integration ([f3eca8f](https://github.com/tstapler/stelekit/commit/f3eca8f145336ddaf51d8dec05473b09019c2d8b))
* **release:** fix SHA256 update to replace existing values not just placeholders ([7fd170c](https://github.com/tstapler/stelekit/commit/7fd170c138c22854bdfc8eba7d2827f3558e64b0))
* **release:** prepend download table to Release Please changelog ([b0e0c3e](https://github.com/tstapler/stelekit/commit/b0e0c3e64c03f4486c7dd28ba36ff803789864c1))
* **release:** trigger on release event instead of tag push ([838aad2](https://github.com/tstapler/stelekit/commit/838aad2e850689ee1fc3102ad9dfcca2e4999cf0))

## [0.3.0](https://github.com/tstapler/stelekit/compare/v0.2.0...v0.3.0) (2026-04-19)


### Features

* wasmJs browser demo, Astro/Starlight docs site, and GitHub Pages CI/CD ([4774c51](https://github.com/tstapler/stelekit/commit/4774c5151da66f4c4bbc9b990fc7c99b1bc2a00d))


### Bug Fixes

* address PR review comments ([3867b15](https://github.com/tstapler/stelekit/commit/3867b15e7832e0e65369c033400da256cfbe176c))
* **ci:** revert GraphManager Clock import to kotlin.time.Clock ([56e33e5](https://github.com/tstapler/stelekit/commit/56e33e5ad6b95fb0c9f9b08c9352e958a48d44a0))

## [0.2.0](https://github.com/tstapler/stelekit/compare/v0.1.0...v0.2.0) (2026-04-18)


### Features

* **release:** add RPM, AppImage, and Homebrew tap support ([3915208](https://github.com/tstapler/stelekit/commit/391520846e2fbb7edc96bcbb8c185c7d6bd3e31b))


### Bug Fixes

* **ci:** downgrade Gradle to 8.7 to fix iOS KotlinNativeBundleBuildService error ([ac9ff53](https://github.com/tstapler/stelekit/commit/ac9ff530dc858be6cb5b821e0c86bab262d5a851))
* **ci:** mark iOS CI non-blocking due to two pre-existing failures ([6872bc0](https://github.com/tstapler/stelekit/commit/6872bc05d9ce232e5f557bf09e4cdeacab20cd09))
* **ci:** use compileCommonMainKotlinMetadata to avoid iOS toolchain property error ([c7ec54d](https://github.com/tstapler/stelekit/commit/c7ec54deff0d7a716e3f5a7c1397a0d7bf101559))
* **ci:** work around KotlinNativeBundleBuildService/Gradle 8.8+ incompatibility in iOS CI ([710062e](https://github.com/tstapler/stelekit/commit/710062e80fa1383098c8a6659bbcd043cd56add2))
* **desktop:** include java.sql module in jlink JRE for SQLDelight JDBC ([86ab81a](https://github.com/tstapler/stelekit/commit/86ab81a4afa7c4f47be4d183900c4a70261182eb))
* **journal:** normalize journal page names to underscore format ([d79f8f0](https://github.com/tstapler/stelekit/commit/d79f8f084e2036a59c2333b035679c6a549ef513))
* **journal:** normalize journal page names to underscore format ([c6e7c50](https://github.com/tstapler/stelekit/commit/c6e7c502f6ad8857cc5bae2631213b75392dc886))
* **migration:** preserve block UUIDs and fix iOS CI in journal normalization ([217af1f](https://github.com/tstapler/stelekit/commit/217af1f4faa789ac8bbad2f2dce482941efdd4ae))
* **release:** detect ARCH dynamically via uname -m for AppImage ([c537c4e](https://github.com/tstapler/stelekit/commit/c537c4eced3e59fbd39e25500f7a5e4a7dcbe7b7))
* **release:** fix AppImage desktop integration and icon paths ([1c055c8](https://github.com/tstapler/stelekit/commit/1c055c8187a0c49a4754f460d8788ca729a43f05))
* **release:** set ARCH=x86_64 for appimagetool and fix desktop Categories ([2a9627d](https://github.com/tstapler/stelekit/commit/2a9627dabea3a5b3c139f50158ff38a8b66f7ddd))
