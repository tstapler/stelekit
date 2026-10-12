# Research: Stack / Tooling (demo-help-feature-registry)

Labels: VERIFIED = opened source / ran command this session; INFERRED = reasoning or unchecked prior knowledge.

## 0. Corrections to requirements.md
- `gradle/libs.versions.toml` does NOT exist (VERIFIED: `find . -name libs.versions.toml` empty). Versions are inline: Kotlin 2.4.10, SQLDelight 2.3.2, detekt plugin 1.23.7 (`settings.gradle.kts:9,18,21`); kotest 6.2.4 inline (`kmp/build.gradle.kts:161-162`); Bazel pins `io.kotest:kotest-property-jvm:6.2.4` (`MODULE.bazel:225`).
- No KSP and no Konsist anywhere (VERIFIED: grep of `kmp/build.gradle.kts`, `MODULE.bazel`; only a comment at `kmp/build.gradle.kts:158` mentions "no KSP plugin").
- `@HelpExempt` has SOURCE retention (`docs/HelpExempt.kt:15`), `@HelpPage` RUNTIME (`docs/HelpPage.kt:14`). Reflection tests therefore cannot see exemptions; `DemoGraphCoverageTest.kt` hand-mirrors a `KNOWN_EXEMPT_SCREENS` set (lines ~95-100) -> drift risk. Registry decision should fix this (e.g. RUNTIME retention, or detekt/Konsist reads source).

## 1. Enforcement mechanisms

| Mechanism | Gradle | Bazel | commonMain fit | Cost | Verdict |
|---|---|---|---|---|---|
| detekt custom rule (buildSrc) | yes, existing | NOT run by Bazel (detekt is Gradle-only; CI `lint` job runs `./gradlew :kmp:detekt`, `ci.yml:134-155`) | n/a (rule is PSI-only, runs over commonMain source) | low per rule; ~1 rule file + 1 test + provider line | Best for per-declaration "needs annotation" checks; weak for cross-file (needs resource files) |
| businessTest structural test | yes (`jvmTest` dependsOn businessTest, `kmp/build.gradle.kts:426`) | yes (`//kmp:business_tests`, glob `**/*.kt`, `kmp/src/businessTest/kotlin/BUILD.bazel`) | test code in businessTest is JVM (uses `java.io.File`, `JarURLConnection`) | low-medium; needs resource bundling per extra file read under Bazel | Best for cross-artifact checks (registry vs demo pages vs site mdx vs docs) |
| jvmTest reflection test | yes | `//kmp:jvm_tests`, needs X11 for UI tests only | JVM reflection | low | Works for RUNTIME-retention data only |
| Konsist | yes (JUnit/Kotest-style test dep) | needs `maven.install` artifact + source files staged in sandbox | n/a | medium: new dep, version churn | See below |
| KSP | needs `com.google.devtools.ksp` plugin + per-target config | no Bazel KMP KSP story in this repo | generates code | high | Reject unless codegen is wanted |
| standalone script (scripts/) | `make`/CI step | runs outside Bazel | n/a | low; matches `scripts/generate-readme.sh` pattern | Good for site/markdown checks |
| GitHub Actions job | n/a | n/a | n/a | low; precedent: `readme-sync`, detekt ratchet (`ci.yml:117-166`) | Required for staleness (git-diff) + ratchet |

### detekt (VERIFIED)
- Rules live in `buildSrc/src/main/kotlin/dev/stapler/detekt/` (15 rule files + `SteleKitRuleSetProvider.kt:27` registers `MissingHelpPageAnnotationRule`). Tests in `buildSrc/src/test/kotlin/dev/stapler/detekt/*RuleTest.kt` (JUnit5 + `detekt-test:1.23.7`, `buildSrc/build.gradle.kts`).
- `MissingHelpPageAnnotationRule` (`visitClassOrObject`): fires on direct children of a `sealed class Screen`, checks annotation short names `HelpPage`/`HelpExempt`. Pure syntax -> extendable by copy to: `SettingsCategory` enum entries, `SyncState` subclasses, `EditorCommand(` sites. Limit: PSI only, cannot read demo-graph pages or site mdx, no type resolution (name match only).
- Ratchet precedent: `kmp/config/detekt/baseline.xml` with CI diff of `<ID>` count vs merge-base (`ci.yml:157-166`). Same baseline mechanism could ratchet new rules; but the baseline file is detekt's, so a separate registry allowlist file is cleaner for non-detekt checks.
- Caveat: detekt does not scan wasmJsMain/wasmJsTest (memory note); irrelevant for commonMain registry.
- Bazel gap: detekt rules only enforce in the Gradle CI job; fine since CI already runs that job (INFERRED: Bazel CI has no detekt step - not opened).

### businessTest structural tests (VERIFIED)
- `MigrationRunnerSchemaSyncTest` reads `SteleDatabase.sq` from classpath resource under Bazel, else system property `stelekit.sq.file` set in `kmp/build.gradle.kts:805-812`; Bazel bundle target `sqldatabase_schema_as_resource` (`businessTest/kotlin/BUILD.bazel`).
- `DemoFileSystemSyncTest` (`businessTest/.../db/DemoFileSystemSyncTest.kt`): lists `demo-graph/pages` via classpath (handles `file:` and `jar:` URLs, lines 28-47); Bazel gets `common_resources` (`kmp/src/commonMain/resources/BUILD.bazel`, glob `demo-graph/**/*`) and `demo_filesystem_as_resource`.
- `JourneyDocsFrontmatterTest` + `DocRepoLocator` (under `kmp/src/businessTest/...`; also used by `BacklogTriageRuleTest`): Bazel path = classpath resources from `journey_docs_as_resources` (`resource_strip_prefix="docs"`, source filegroup `//:journey_docs_source` in root `BUILD.bazel:~104`); Gradle path = walk up from CWD. Pattern to copy for any new repo file the test must read: add `filegroup` + `java_library(resources=...)` + dep on `business_tests`.
- Every business test must also be listed in `AllBusinessTests.kt`, enforced by `AllBusinessTestsCompletenessTest.kt` (VERIFIED file exists; memory: bazel business_tests runs a suite class, not discovery) -> new test class must be registered or it silently does not run under Bazel.
- Files a registry check would need under Bazel: `site/src/content/docs/user/*.mdx` (new filegroup in a package that can see `site/`; `site/` has no BUILD file -> need `exports_files`/filegroup at root `BUILD.bazel` like `journey_docs_source`), and the commonMain Kotlin sources if scanning source text.

### Konsist (partly INFERRED)
- Latest release v0.18.1 published 2026-10-10 (VERIFIED: GitHub API `LemonAppDev/konsist` releases/latest). README describes it as a Kotlin consistency linter run from JUnit/Kotest tests (VERIFIED, raw README).
- Mechanism (INFERRED, from knowledge of the library): parses `.kt` source via Kotlin PSI from disk (`Konsist.scopeFromProject()` uses the Gradle project dir / working dir), so it is JVM-test-only, works on commonMain source text, but needs source files visible at test runtime. Under Bazel that means staging all scanned `.kt` files as test data - the same filegroup workaround already used for `GraphContent*.kt` (`kmp/src/commonMain/kotlin/BUILD.bazel:90-97`) but for a whole source tree (large, slow, fragile). KMP source-set support is by directory convention; not verified. Not opened: Konsist docs on KMP/Kotlin 2.4 compatibility. Kotlin 2.4.10 is very recent; Konsist embeds its own compiler, compatibility should be tested in a spike.
- Fit verdict: nothing Konsist does that a detekt rule + a `Regex` over a bundled source filegroup cannot; adds a dep + Bazel staging cost. Not recommended for phase 1.

### KSP (VERIFIED absent)
- Latest KSP 2.3.12 (2026-09-09, GitHub API). Not used in repo. A KSP processor could emit a registry from annotations, but requires plugin wiring per KMP target and has no Bazel path here (`bazel-kotlin` rules_kotlin KSP support not evaluated). High cost; only justified if "derive registry from annotations" is chosen AND reflection (RUNTIME retention, as `@HelpPage` already works via JVM reflection in `DemoGraphCoverageTest.kt:23-30`) is insufficient.

### Cheapest KMP-portable registry shape (INFERRED, based on existing code)
- `FeatureDocRegistry` (`docs/FeatureDocRegistry.kt`) is already commonMain, factory-lambda based, no reflection, "works on iOS/JS" (its own KDoc). A plain `object`/`List<FeatureEntry>` in commonMain keeps wasm/iOS portable; structural tests then read it directly as data (no reflection, no source parsing). Combine with detekt rules for "new subclass/entry without registry mention" if declare-style is chosen.

## 2. Doc / site linters

Site: Astro Starlight; `site/package.json` deps `@astrojs/starlight ^0.38.3` (installed 0.38.3, VERIFIED via node_modules), `astro ^6.0.1`, `sharp`; scripts: dev/build/preview only (no lint). `pages.yml` builds site on push to main, `check-changes` job gates only the wasm build, not the site; no PR-time site build found in `ci.yml` (job list: android, sqldelight-sync, readme-sync, lint, android-smoke, workflow-lint, wasmjs-compile, wasm e2e - VERIFIED via grep; I did not read the tail past line 367).

| Tool | Latest (VERIFIED, API) | Fit |
|---|---|---|
| starlight-links-validator | 0.27.0 (2026-10-09); peer `@astrojs/starlight >=0.42.0`; older 0.2x releases need `>=0.38.0`/`>=0.41.0` (npm registry) | Astro integration that fails `astro build` on broken internal links/anchors/sidebar slugs. Installed starlight 0.38.3 is below the 0.27.0 peer floor -> pin an older validator (peer `>=0.38.0`, exact version not resolved here) or bump starlight. Checks site mdx only, not demo-graph markdown. Cheapest site link check (config-only). |
| lychee | lychee-v0.24.2 (2026-05-01) | Rust CLI/GitHub Action; checks external + internal links in md/mdx/html. Good for `docs/` and built `site/dist`. Does not understand `[[wikilinks]]`; would treat them as plain text (no false positives, no coverage). External links = flaky -> use `--offline` or cache + allowlist for PR gating. |
| markdownlint-cli2 | 0.23.3 (npm registry `latest`) | Style linting of md/mdx; mdx support partial; Logseq demo pages use bullet-only outliner syntax and `key:: value` properties which trip default rules (MD004/MD007/MD013/MD033...) -> would need a heavy disable list. Low value for the registry goal. |
| Vale | v3.24.0 (vale-cli/vale; the old errata-ai path returned nothing) | Prose linter; needs styles dir + config; no registry value. Skip. |

Wikilink handling (VERIFIED): `[[...]]` are parsed by the app's own parser (`WikiLinkNode` in `parser/MarkdownParser.kt:150`; `references` list on `ParsedBlock`, `model/ParsedModels.kt:48`, via `extractReferences` `MarkdownParser.kt:244-248`). No generic markdown linter validates them -> a Kotlin test (section 3) is the only way to check demo-graph links.

Recommendation: registry-aware checks in a businessTest; `starlight-links-validator` (pinned to starlight-0.38-compatible version) in `site/astro.config.mjs` plus a PR-time `npm run build` job; optional lychee `--offline` on `docs/` + `site/dist`. Skip Vale/markdownlint.

## 3. Loading the demo graph in a test (all VERIFIED)

Resources: `kmp/src/commonMain/resources/demo-graph/{pages,journals}/*.md` on classpath as `demo-graph/...` (Gradle: commonMain resources on test classpath; Bazel: `//kmp/src/commonMain/resources:common_resources`, a dep of `business_tests`).

Options, cheapest first:
1. Parse-only (no DB), pure and fast, businessTest-friendly: iterate `DemoFileSystem` or classpath files, call `MarkdownParser().parsePage(content)` (`parser/MarkdownParser.kt:16`) -> `ParsedPage.blocks: List<ParsedBlock>`; each `ParsedBlock.references` (`model/ParsedModels.kt:48`) holds wikilink targets (`[[x]]` -> "x") and block refs. Assert: no exception, blocks non-empty, every wikilink target exists as a page name (file stem under `pages/` or `journals/`, case-insensitive per Logseq; confirm normalization against `GraphLoader.stripPageExtension` `db/GraphLoader.kt:91` and `resolvePageFilePath` `:501`). Note `parsePage` rethrows on parser exceptions (`MarkdownParser.kt:29-34`). Caveat: `references` is nested per block (`children`) - must recurse.
2. Real loader, in-memory repos: `RepositorySet.createGraphLoader(fileSystem)` (`repository/RepositoryFactory.kt:87`) with `DemoFileSystem()` (`platform/DemoFileSystem.kt:17`, default graph path `"/demo"` at line 667; implements `listFiles`, `readFile`, `listDirectories`). Used exactly this way by `jvmTest/.../ui/GraphContentDemoFileSystemWiringTest.kt` (imports `GraphBackend`, `GraphManager`, `createGraphLoader`, `DemoFileSystem`; builds repos via `GraphManager(... defaultBackend = GraphBackend.IN_MEMORY ...)`, same pattern in `businessTest/.../ui/CopyFlowControllerTest.kt:148`). Then `GraphLoader.loadGraph(path, onProgress)` (`db/GraphLoader.kt:536`) or `loadGraphProgressive` (`:590`), then read back with `PageRepository.getPageByName` (`repository/PageRepository.kt:32`), `getAllPagesSnapshot()` (bounded; CLAUDE.md mandated for tests) and `BlockSearchRepository.getLinkedReferences` (`repository/BlockSearchRepository.kt:17`). Caveat: METADATA_ONLY default mode (`loadDirectory` default `ParseMode.METADATA_ONLY`, `GraphLoader.kt:1172`) and background indexing (`indexRemainingPages` `:755`) mean you must force full parse (`loadFullPage` `:950`) or use `parseAndSavePage` (`:1772`) / `importMarkdownString` (`:1580`) to assert block content.
3. Existing helpers: `getClasspathDirectory(loader, resource)` (`jvmTest/.../testing/ClasspathUtils.kt:18`, jvmTest only, not businessTest); `DemoFileSystemSyncTest.listMdFileNames()` (businessTest, handles file:/jar:) - lift into a shared helper rather than copy.
4. Source-of-truth caveat: runtime uses the GENERATED `DemoFileSystem.kt`, not the resource dir; tests that need what ships should use `DemoFileSystem` (option 2) and rely on `DemoFileSystemSyncTest` for sync. Content parity check "resource files == DemoFileSystem content" is NOT currently asserted (only filename presence, per requirements appendix) -> cheap addition.

Placement: option 1 in businessTest (runs under Bazel + Gradle; register in `AllBusinessTests.kt`). Option 2 fits jvmTest next to the wiring test (needs real DB driver only if SQLDELIGHT; IN_MEMORY needs none).

## 4. Dependency versions (VERIFIED)
- Kotlin 2.4.10 (`settings.gradle.kts:9`); SQLDelight 2.3.2; detekt 1.23.7 (plugin and buildSrc `detekt-api`/`detekt-test`); JUnit Jupiter 5.10.2 in buildSrc only; compose rules `io.nlopez.compose.rules:detekt:0.4.27` (`kmp/build.gradle.kts:1246`).
- kotest-assertions-core + kotest-property 6.2.4 in commonTest via inline coordinates (`kmp/build.gradle.kts:157-162`); Bazel only has `kotest_property_jvm` (no `kotest-assertions-core` in `business_tests` deps, `BUILD.bazel`), so businessTest code under Bazel cannot use kotest-assertions unless the dep is added (INFERRED from the deps list; verify before relying on it).
- Bazel business_tests deps: kotlin-test-junit, coroutines-test-jvm, serialization-json-jvm, kotest-property-jvm, okio-fakefilesystem-jvm; no Konsist, no detekt.
- Latest external: Konsist v0.18.1, KSP 2.3.12, lychee 0.24.2, markdownlint-cli2 0.23.3, Vale 3.24.0, starlight-links-validator 0.27.0 (all via GitHub/npm API calls this session).
