# Build vs Buy: unwired features and test coverage

Date: 2026-10-27. Scope: requirements.md in the parent directory. Verdict scale: Recommended / Viable / Not recommended.

Evidence labels: VERIFIED = opened in this repo this session; BACKGROUND = my prior knowledge of external tools, not re-checked online this session (versions and feature claims should be spot-checked before a plan commits to them).

## Repo facts that drive the verdicts (VERIFIED)

- Build: Gradle `kmp/build.gradle.kts` (no `libs.versions.toml`; versions inline) plus Bazel canonical. Plugins already applied: `jacoco`, `io.gitlab.arturbosch.detekt` (+ compose-rules, custom buildSrc rules). `UnusedImports` and `UnusedPrivateMember` are configured in `kmp/config/detekt/detekt.yml:325-327`.
- Coverage/mutation already exist: `jacocoTestReport` (JVM), `jacocoAndroidTestReport`, `diffCoverage` (diff-cover, threshold 80), and PIT via `org.pitest:pitest-command-line:1.17.4` hand-wired (`kmp/build.gradle.kts:1744-1760`), change-scoped, capped by `mutate.maxClasses`. The canonical Bazel suite has no JaCoCo (comment at ~1541); coverage is Gradle-only.
- Dependencies already present: PDFBox 3.0.4 (JVM) and pdfbox-android 2.0.27.0; ONNX Runtime 1.26.0 (JVM) and 1.20.0 (Android); ML Kit image-labeling 17.0.9, text-recognition 16.0.1, genai-prompt; Ktor client 3.1.3 on all targets; WorkManager (Android); sqlite-android (requery) bundled with FTS5; SQLDelight 2.3.2; `blocks_fts` and `pages_fts` FTS5 tables in `SteleDatabase.sq:112-121`.
- Per-platform ML ports already exist: `PdfTextExtractor` (JVM/Android/iOS impls), `ImageLabeler` (`MlKitImageLabeler`, `OnnxImageLabeler`, `CoreMlImageLabeler`). The plugins (`PdfTextPlugin`, `OcrPlugin`, `OnDeviceLabelingPlugin`) are thin adapters over these.
- `CloudClaudePlugin.processAsset` is a stub returning empty labels ("Full implementation deferred: requires Ktor HTTP call to Anthropic API"). `CloudVisionPlugin` has the same shape (same imports and config; body not opened).
- `DatalogEngine.execute` returns `emptyList().right()`; `VisualQueryBuilder.buildFromSchema` is a TODO; `DatalogQuery.parse` is a regex over triple patterns (98 lines total). `JsPluginBridge` methods log and return null. `RTCManager` (92 lines) is placeholder comments around `pullRemoteData`/`pushUpdates`.
- `VectorSearch` (145 lines): brute-force `cosineSimilarity` over a caller-supplied `List<Block>`, embedding every block's text on every query via an `InferenceWorker` interface that has no implementation.
- `DesktopSyncScheduler` (jvmMain/git, ~45 lines): a coroutine `while(true){delay; onTick()}` implementing `BackgroundSyncScheduler`; no `onTick` error guard visible in the lines read.

## 1. Repo-wide coverage and mutation tooling

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Keep JaCoCo (already wired) + add a repo-wide `jacocoTestReport` baseline run | Zero new dependencies; JVM and Android reports already exist and are unioned by diff-cover; gives metric 2 numbers immediately | JaCoCo on KMP sees compiled JVM/Android bytecode only (no commonMain-as-source view; wasm/iOS absent); Gradle-only while Bazel is canonical, so the number comes from a different test run than CI's | Recommended (measurement of record) |
| Kover (JetBrains) | Kotlin-aware (inline functions, `when`, default args report correctly), KMP-aware merged report, can coexist with the existing JaCoCo setup (BACKGROUND) | A second coverage engine to maintain; two numbers will disagree; Android+Robolectric instrumentation needs care (the repo already needed special JaCoCo config for Robolectric classloaders) | Viable (adopt only if JaCoCo's Kotlin-synthetic noise skews the map) |
| Bazel coverage (`bazel coverage`) | Would put coverage on the canonical suite | `rules_kotlin` has weak/no LCOV support for Kotlin (BACKGROUND, unverified); repo comment says Bazel has no JaCoCo | Not recommended now (revisit after Epic 7) |
| PIT (pitest-command-line 1.17.4, already wired) for mutation | Already works change-scoped with thresholds; free | JVM bytecode only; Kotlin generates many equivalent/uninteresting mutants (without the Kotlin-aware plugin); full-repo run is slow, hence the 40-class cap | Recommended, tiered (see below) |
| Arcmutate (commercial PIT plugins) | Kotlin-specific mutation filtering removes the most common false survivors; Git-diff mode (BACKGROUND, paid) | Paid; one-person project; adds license handling to CI | Viable only if PIT survivor noise makes the tiered run unusable; otherwise Not recommended |
| SaaS (Codecov, Sonar) | Dashboards, PR comments | Uploads source and coverage of a private notes app; adds nothing diff-cover does not; no mutation | Not recommended |
| LLM-written bespoke coverage-gap analysis | Cheap to ask "which modules are untested" from a jacoco.xml | Numbers must come from the tool, not a model; an LLM ranking is fine only as a layer over measured data | Viable for ranking (risk x churn from `git log` + jacoco.xml), Not recommended as a measurement source |
| Fork/adapt | n/a | n/a | Not applicable |

Mutation tiering recommendation: run PIT per tier, not repo-wide. Tier 1 (db, repository, parser, merge, git) nightly or on demand with `mutate.classes`; everything else only via the existing change-scoped gate. This matches the existing `mutate.maxClasses` design.

## 2. Dead-code detection

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| detekt (already applied) | `UnusedPrivateMember`/`UnusedImports` already configured; no new tooling | Detekt is file/type-resolution-light: it does not find public classes unreferenced anywhere (the actual problem here) | Keep, but Not sufficient |
| Kotlin compiler unused warnings | Free | Warns on locals/params only, not unreferenced public types | Not sufficient |
| Android R8/ProGuard usage report (`-printusage`) | True whole-program reachability from the manifest entry points for Android; resolves manifest components and reflection keeps correctly | Android-only; commonMain classes unreachable from Android entry points show up, but desktop-only code (e.g. `DesktopSyncScheduler`) is not covered; needs a minified release build; keep rules hide things | Recommended as a cross-check for androidMain+commonMain |
| ProGuard on the Desktop distributable (Compose Desktop supports ProGuard) | Same idea for JVM entry point `Main.kt` | Extra build config; Compose's ProGuard path is finicky (BACKGROUND) | Viable, one-off run |
| Gradle dependency-analysis plugin (autonomousapps) | Finds unused/misdeclared dependencies, not unused code. Useful side effect: confirms whether `onnxruntime`, `pdfbox`, etc. are only needed by dormant code | Does not answer the dormant-types question; KMP support is partial (BACKGROUND) | Viable for dependency pruning after wire-or-delete |
| Rerun the existing name-based scan, filtered | Cheap; already done once | Noisy (the requirements say so): same-file helpers, DTOs, manifest components | Viable as the first pass |
| `sg` (ast-grep) / compiler-resolved call graph (IDE inspection, kibitzer `list_callers` is Go/TS/JS only) | Structural scan of class declarations vs. identifiers | Still name-based unless resolved by the compiler; K2 analysis API / IntelliJ "Unused declaration" headless inspection is the only compiler-resolved route (BACKGROUND) | Recommended: IntelliJ headless "Unused declaration" or R8 usage as the precise pass; name scan to triage |
| Bespoke LLM review of each candidate | Handles reflection/DI/manifest judgement the scan cannot | Per-candidate cost; must verify with grep or R8, not trust | Viable as the last-step filter |

Verdict: combine rerun name-scan (triage) + R8 `-printusage` (Android reachability) + one IntelliJ headless unused-declaration pass; do not build a custom analyzer.

## 3. In-app read-only SQL viewer (dev console)

Constraint: raw SQL is security-sensitive; ADR-002 says read-only default; Web has no read-only connection (open question in requirements).

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Build in Compose over the existing SQLDelight driver (query box + result table + EXPLAIN + diagnostics export) | Uses what the plan already specifies; shares `Logger`/theme; works on all KMP targets via the common driver; read-only can be enforced with `PRAGMA query_only=ON` on a dedicated connection plus single-statement/SELECT-prefix check (BACKGROUND on pragma semantics, standard SQLite) | UI work; must handle unbounded result sets (cap rows, LIMIT wrap) given 451 MB DB and 512 MB heap; must not route through `DatabaseWriteActor` write path | Recommended |
| Embed an existing SQLite browser library | None found that is a Compose-KMP embeddable component (BACKGROUND: DB Browser / Android "Database Inspector" / Stetho-style tools are standalone apps or Android-Studio-attached, not embeddable KMP widgets) | Android Studio's Database Inspector requires adb/Studio, which the owner cannot use; third-party debug-db libs open an HTTP server on the device (security and 'no adb' mismatch) | Not recommended |
| Reuse the Datalog engine as the query surface | Higher-level, would keep users away from raw SQL | `DatalogEngine.execute` returns an empty list; implementing it is a new query compiler; does not provide EXPLAIN / schema inspection / diagnostics, which is the actual need (OOM triage) | Not recommended |
| SaaS | None fits an on-device debugging tool | Would export a confidential DB | Not recommended |
| LLM-generated bespoke code | The console is small (screen + one read-only connection + table renderer); well within LLM-draft territory | Security-sensitive part (read-only enforcement) needs explicit tests, not trust in generation | Viable and effectively the same as the build row; require tests that assert INSERT/UPDATE/DELETE/DROP/ATTACH/PRAGMA writes are rejected |
| Fork/adapt | n/a (no candidate) | | Not applicable |

## 4. Desktop scheduled sync

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Existing `DesktopSyncScheduler` (coroutine delay loop), wire it | Already written and implements `BackgroundSyncScheduler`; matches "pull while app is open" (metric 4); testable with `runTest` virtual time; no dependencies | `onTick` exceptions would kill the loop (a Throwable in `onTick` escapes the `while(true)`; the scope has `SupervisorJob` but no handler or try/catch in the lines read), so wrap `onTick` in a catch (non-cancellation) and log; first tick only after a full interval; runs only while the app is open | Recommended (with a try/catch hardening and a "last success" log per Observability) |
| `ScheduledExecutorService` (java.util.concurrent) | Standard, no deps | Less natural than coroutines in this codebase, harder virtual-time testing; no benefit | Not recommended |
| Quartz | Cron expressions, persistence, misfire handling | Heavy (JDBC job store, threads) for one fixed-interval pull; JVM-only; the sync must run in-process with the open graph anyway | Not recommended |
| OS scheduler (systemd timer / launchd / Task Scheduler) | Runs when app is closed | Needs a headless entry point that opens the graph DB without the GUI (SQLite write contention with the app, `GraphWriteLock` and watcher rules do not span processes); three OS-specific installers; out of the stated "while open" scope | Not recommended |
| SaaS (hosted cron, GitHub Actions) | n/a | Cannot reach a local working tree | Not recommended |

## 5. OCR / PDF text / image labeling plugins

Most of the ports already use the right libraries. The question is wiring, plus the two cloud plugins.

| Capability | Option | Pros | Cons | Verdict |
|---|---|---|---|---|
| PDF text | PDFBox (JVM 3.0.4, Android pdfbox-android 2.0.27.0, already in deps and wired into `JvmPdfTextExtractor`/`AndroidPdfTextExtractor`) | Already a dependency; text-layer extraction is deterministic and testable | Scanned PDFs have no text layer; pdfbox-android is an old fork (2.0.27); heap use on large PDFs vs the 512 MB cap | Recommended (wire `PdfTextPlugin`) |
| Image OCR | ML Kit text-recognition 16.0.1 (Android, in deps) | On-device, no network, already a dependency | Android only; `OcrPlugin` takes an `ImageLabeler` port (see Gap below) | Recommended on Android |
| Image OCR | Tesseract / tess4j (desktop) | Free, offline, mature (BACKGROUND) | Native binary + tessdata packaging per OS; not currently a dependency; weaker than ML Kit on photos | Viable for desktop if OCR on desktop matters; otherwise defer |
| Image labeling | ML Kit image-labeling 17.0.9 (Android), ONNX Runtime (JVM `OnnxImageLabeler`), CoreML (iOS) | All three already implemented behind `ImageLabeler` | ONNX model file distribution/size must be checked; label quality of generic models is modest | Recommended (wire `OnDeviceLabelingPlugin`) |
| Cloud labeling/OCR | Claude (Anthropic API) via Ktor (already a dependency) | Best OCR/description quality; one HTTP client already present on all targets; `CloudClaudePlugin` has the gating (enabled flag, provider, session cap) | Stub body today; needs API key storage, consent UX, per-call cost cap, image downscaling to protect the heap; sends confidential images to a third party (requirements: opt-in only) | Viable (opt-in, after on-device path works) |
| Cloud labeling/OCR | Google Cloud Vision | Cheap per call, good OCR | Second provider/credential flow; same privacy cost; `CloudVisionPlugin` also a stub | Viable but Not recommended to do both; choose one cloud provider to implement |
| Bespoke LLM-written model/OCR code | n/a | | Writing recognizers is not viable; writing the HTTP adapter is | Not recommended for recognition; fine for the Ktor adapter |
| Fork/adapt | Adapt the existing plugins (they are thin) | Lowest cost | | Recommended |

Gap to verify (not checked): `OcrPlugin` imports `ImageLabeler` rather than a dedicated OCR port, so ML Kit text recognition may not be reachable through it. Read `OcrPlugin.kt` fully and `MlServiceStubs.kt` before deciding whether an `OcrEngine` port is needed.

Decision hint: wire PdfText + OnDeviceLabeling + OCR (Android) first; keep Cloud Claude as the only cloud plugin; delete `CloudVisionPlugin` unless there is a stated reason for Google.

## 6. Vector search vs dormant `VectorSearch.kt`

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Wire existing `VectorSearch` | No new code | Re-embeds every candidate block on every query (O(N) inference per search); no persistent index; `InferenceWorker` has no implementation; at 9.5k pages it would OOM or stall under the 512 MB cap. Architecturally violates the "graph-scale reads must be bounded" rule | Not recommended |
| sqlite-vec | Stores vectors in the same SQLite file, KNN in SQL (BACKGROUND) | Needs a loadable extension: unavailable with the Android `sqlite-android` (requery) bundle unless compiled in, and absent on wasm (`@sqlite.org/sqlite-wasm` build) and iOS native driver; a multi-platform build/packaging effort | Not recommended now |
| Store embeddings as BLOB column + brute-force or chunked cosine in Kotlin, using ONNX/ML Kit embeddings | Works on every target with existing drivers; bounded chunks fit the repo's pagination rules; the cosine function in `VectorSearch.kt` is reusable | Needs an embedding model (size, license, download), a background indexer, a migration, and a `MigrationRunner` entry; 9.5k pages x 384 dims is about 15 MB of floats if per-page, much more per-block | Viable (only if semantic search is a wanted product feature) |
| Cloud embeddings API | Quality, no model shipping | Confidential notes leave the device | Not recommended |
| Keep FTS5 only | Already shipped, bounded, fast | No semantic matching | Recommended default |

Verdict: delete `VectorSearch.kt` and `InferenceWorker` unless the owner wants semantic search as a product feature, in which case re-plan as a new feature (BLOB store + indexer) and salvage only `cosineSimilarity`.

## 7. Datalog

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Wire `DatalogQuery`/`DatalogEngine` | Parser exists | Engine returns empty list; regex parser handles only flat triple patterns with no joins/rules/functions (so it cannot express Logseq's real queries); `VisualQueryBuilder` is a TODO | Not recommended |
| Real Datalog (DataScript port, Datomic-style lib) | Compatible semantics with Logseq queries | No maintained KMP Datalog library known (BACKGROUND, unchecked); mapping blocks/properties into EAV is a large project | Not recommended |
| Translate a Datalog subset to SQL over existing tables | Reuses FTS/indices | Only worth it if Logseq query-block compatibility is a goal; that is a feature decision, not a wiring task | Viable only on an explicit product decision |
| SQL FTS5 (shipped) + the dev-console SQL viewer for power queries | Zero new code | No user-facing structured query | Recommended |

Verdict: delete `DatalogQuery.kt` (engine, builder, `DatalogSearchSupport`) unless the owner commits to Logseq query compatibility.

## 8. RTC

`RTCManager` is placeholder comments around `pullRemoteData`/`pushUpdates` with no transport, no server, no CRDT. The product model is single-user, with git sync for multi-device (`BackgroundSyncScheduler`, WorkManager). Real-time multi-user editing would need a server and a CRDT/OT layer (Yjs ports, Automerge-kt, or a hosted service such as Liveblocks) and is outside the stated scope ("sole user").

| Option | Verdict |
|---|---|
| Delete `RTCManager` and `RTCOp` | Recommended |
| Adopt a CRDT library / hosted RTC | Not recommended (no requirement; conflicts with markdown-files-as-source-of-truth and `GraphWriter` conflict model) |
| Keep as placeholder | Not recommended (counts against metric 1) |

## 9. JsPluginBridge (also unreferenced)

Both methods are logging stubs returning null; they exist for CLJS plugin compatibility. Embedding a JS engine (QuickJS via quickjs-kt, Rhino, J2V8, or the browser on Web) is a large per-platform effort with no consumer. Verdict: delete `JsPluginBridge` and its accessor in `PluginSystem.kt`; keep the Kotlin plugin interface only if something else uses it (not verified).

## Summary table

| Item | Verdict |
|---|---|
| JaCoCo baseline + PIT tiered (existing) | Recommended; Kover/Arcmutate only if noise demands |
| Dead code: name scan + R8 usage + IntelliJ headless | Recommended; no custom analyzer |
| SQL viewer | Build in Compose, `query_only` connection |
| Desktop sync | Wire `DesktopSyncScheduler` (add onTick try/catch) |
| PDF/OCR/labeling plugins | Wire on-device three; Claude opt-in later; drop Vision |
| Vector search | Delete (or re-plan as new feature) |
| Datalog | Delete |
| RTC, JsPluginBridge | Delete |

## Unverified items to confirm during planning

- Whether `OcrPlugin` can reach ML Kit text recognition (see gap in section 5).
- Whether `CloudVisionPlugin` body is a stub (assumed from shared shape).
- External-tool claims marked BACKGROUND (Kover/Arcmutate feature sets, sqlite-vec platform support, `rules_kotlin` coverage support).
- Current coverage numbers: not run in this research pass.
