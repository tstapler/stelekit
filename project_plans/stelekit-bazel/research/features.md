# Bazel Migration Features Research

**Project**: SteleKit — KMP project targeting Desktop (JVM), Android, iOS, Web  
**Research Date**: 2026-05-17  
**Goal**: Understand what Bazel provides vs Gradle, real migration experiences, and how to set up remote caching on GitHub Actions.

---

## 1. OSS Projects That Migrated Android/Kotlin from Gradle to Bazel

### Grab (Superapp — 2.5M+ LOC)

- **Scale**: Android Passenger app with 2.5 million lines of code across hundreds of modules.
- **Motivation**: Gradle performs reasonably up to ~1M LOC; beyond that, build times degrade enough to hurt engineering velocity. At Grab's scale, Bazel was essential.
- **Tool**: Built [Grazel](https://github.com/grab/Grazel) — a Gradle plugin that generates equivalent Bazel BUILD rules from existing Gradle scripts, enabling incremental/automated migration.
- **Result**: Grab stated they would make the same choice again without hesitation. ABI jars provided significant compile avoidance on non-ABI changes.
- **Timeline**: ~5 months for initial proof of concept and impact estimation, before full rollout.
- **Source**: [How Grab is Blazing Through the Superapp Bazel Migration](https://engineering.grab.com/how-grab-is-blazing-through-the-super-app-bazel-migration)

### Turo

- **Scale**: Large multi-module Android app.
- **Approach**: Ran Gradle and Bazel side-by-side during transition — Gradle remained the source of truth; Bazel was evaluated on selected CI workflows.
- **Tools**: Built [Airin](https://github.com/Morfly/airin) and Pendant (open source). Airin is a Gradle plugin that scans project structure and generates Starlark scripts via Pendant (a Kotlin DSL for Bazel file generation).
- **Key lesson**: "Bazel adoption is not an overnight process." Running two build systems in parallel reduces risk but requires ongoing sync.
- **Automation benefit**: With automated tooling, migration cost dropped by at least 50% vs manual effort.
- **Source**: [Boosting Bazel Adoption on Android With Automation — Turo Engineering](https://medium.com/turo-engineering/boosting-bazel-adoption-on-android-with-automation-6dc79d298628)

### General Android/Kotlin Migration Consensus (2022–2024)

- Migrating from Gradle to Bazel loses some "magic" Gradle gives for free (plugin ecosystem, convention plugins, AGP auto-wiring).
- Custom Gradle build logic and plugins require equivalent Bazel rules — these have no automated migration path.
- Dependency conflict management is harder in Bazel: `rules_android` and `rules_kotlin` version bumps often require a Bazel version bump too.
- Fully automatic migration is not feasible for projects with custom Gradle tasks/plugins — partial automation (Airin, Grazel) is the practical approach.
- Source: [Migrating Android Projects from Gradle to Bazel — Medium](https://medium.com/@imamyusufb123/migrating-android-projects-from-gradle-to-bazel-a-complete-guide-for-developers-4e67aea0883c), [Automated migration with Airin — ProAndroidDev](https://proandroiddev.com/automated-migration-of-your-projects-to-bazel-build-system-with-airin-304fa8b3680c)

---

## 2. Bazel vs Gradle for KMP Specifically

### KMP Support Status in Bazel (CRITICAL FINDING)

**Bazel does not natively support Kotlin Multiplatform as of mid-2025.** This is the most important finding for SteleKit.

- `rules_kotlin` supports: JVM, Android, and JS (JS is under-maintained).
- `rules_kotlin` does NOT support: **iOS/Kotlin Native**, **WebAssembly/Wasm**, or the **expect/actual cross-compilation mechanism** that KMP depends on.
- A Google Summer of Code 2025 project is specifically focused on improving Bazel's KMP support (dependency resolution, rules_kotlin + rules_jvm_external compatibility, expect/actual handling) — this indicates the feature is still actively in development, not yet production-ready.
- The Kotlin community on Slack explicitly states: "Bazel's support for Kotlin is evolving, but proper KMP integration remains a challenge" and "there is no support for KMP" in Bazel as currently packaged.
- JetBrains is developing a Kotlin Build Tools API (unified entry point for build systems) to make Bazel/Buck integration easier, but this is forward-looking, not yet released.
- Sources: [rules_kotlin GitHub](https://github.com/bazelbuild/rules_kotlin), [KMP Cross-Platform Roadmap Issue #567](https://github.com/bazelbuild/rules_kotlin/issues/567), [GSoC 2025 Kotlin](https://kotlinlang.org/docs/gsoc-2025.html), [Kotlinlang Slack — Compose Multiplatform Bazel support](https://slack-chats.kotlinlang.org/t/3145636/does-compose-multiplatform-support-bazel)

### Incremental Build Accuracy

- **Bazel**: Correctness by construction. Bazel computes a cryptographic hash of every input (source, deps, compiler flags). If the hash hasn't changed, the output is reused. This is more accurate than Gradle's task-based approach and avoids "stale output" false incremental builds.
- **Gradle**: Relies on users correctly declaring inputs/outputs. Many potential problems caught by the Gradle Plugin Development Plugin, but correctness is an honor system for custom tasks. Real-world Gradle incremental builds frequently produce incorrect results when inputs are not fully declared.
- **Bazel sandboxing**: Ensures all inputs/outputs are correctly declared by isolating actions. Many users disable it (especially macOS) due to performance overhead — when disabled, the correctness guarantee weakens.
- **Key nuance**: Gradle claims 5–16x faster incremental builds than Bazel in their own benchmark (for the monolith case). However, this comparison is disputed and reflects Gradle's situation without remote caching, vs Bazel with a cold local cache. At scale with remote caching active, Bazel wins decisively.
- Sources: [Bazel vs Gradle — Buildkite](https://buildkite.com/resources/comparison/bazel-vs-gradle/), [Gradle vs Bazel for JVM Projects — Gradle Blog](https://blog.gradle.org/gradle-vs-bazel-jvm), [Empirical Study — Does Bazel Speed Up CI Builds? (2024)](https://dl.acm.org/doi/10.1007/s10664-024-10497-x)

### Developer Experience vs Gradle

| Dimension | Bazel | Gradle |
|---|---|---|
| First-build time | Similar or slower | Similar |
| Incremental (cached) | Faster at scale; reproducible | Faster for small monolith; stale-output risk |
| BUILD file maintenance | Manual; no auto-generation for KMP | Automatic via plugins/conventions |
| Plugin ecosystem | Limited; fewer rules than Gradle plugins | Very large ecosystem |
| Custom build logic | Starlark (Python-like) — unfamiliar to most | Kotlin/Groovy DSL — familiar |
| KMP expect/actual | **Not supported** | Fully supported |
| Compose Multiplatform | **Not supported** | Fully supported |
| Learning curve | High (new language: Starlark, new concepts) | Moderate (familiar for Android devs) |

---

## 3. GitHub Actions Remote Caching with Bazel

### Option A: BuildBuddy Free Tier

- BuildBuddy is open-source (MIT-licensed for individual features) and offers a free cloud tier for small teams and OSS projects.
- Provides a remote cache + build event viewer at `remote.buildbuddy.io`.
- GitHub Actions integration via `.bazelrc`:
  ```
  build:remote --remote_cache=grpcs://remote.buildbuddy.io
  build:remote --remote_executor=grpcs://remote.buildbuddy.io
  build:remote --remote_header=x-buildbuddy-api-key=${{ secrets.BUILDBUDDY_ORG_API_KEY }}
  ```
- Also supports Remote Build Execution (RBE) for parallelizing compilation across machines (paid tiers).
- Free tier limits not explicitly published; suitable for OSS/small-team use.
- Sources: [BuildBuddy RBE with GitHub Actions](https://www.buildbuddy.io/docs/rbe-github-actions/), [Bazel Remote Caching Explained — BuildBuddy](https://www.buildbuddy.io/blog/bazels-remote-caching-and-remote-execution-explained/)

### Option B: bazel-remote (Self-Hosted)

- [buchgr/bazel-remote](https://github.com/buchgr/bazel-remote): A dedicated remote cache server, battle-tested (serving TBs/day since 2018).
- Implements the Bazel Remote Execution API v2 (ActionCache + CAS + Capabilities + Byte Stream).
- Can be run as a Docker container; backends: local disk, AWS S3, GCS, Azure Blob.
- With GitHub Actions: deploy bazel-remote on a persistent server or AWS/GCP, point Bazel at its gRPC/HTTP endpoint.
- Self-hosting cost vs BuildBuddy free tier: bazel-remote requires infrastructure management; BuildBuddy free removes this overhead for small teams.

### Option C: bazel-github-actions-cache

- [tsawada/bazel-github-actions-cache](https://github.com/tsawada/bazel-github-actions-cache): Acts as a local Bazel remote cache server that uses GitHub Actions' cache API as its backend.
- Usage: add one workflow step + point Bazel at the local cache server.
- Advantage: zero infrastructure cost (uses GitHub's existing cache storage).
- Disadvantage: GitHub Actions cache is per-repo, has 10 GB limit, and the undocumented API may break.
- Best for: small-to-medium projects where BuildBuddy free tier limits are a concern.

### "Build without the Bytes" (Bazel 7.0+, Dec 2023)

- Enabled by default in Bazel 7.0 LTS.
- Prevents intermediate output downloads from remote cache/execution — only final outputs are downloaded.
- Major CI speedup for projects using remote caching, as network transfer was previously the bottleneck.
- Source: [Bazel 7.0 Release Blog](https://blog.bazel.build/2023/12/11/bazel-7-release.html)

### Reported Cache Hit Rates

- No specific public benchmarks for cache hit rates with BuildBuddy free tier on Android/Kotlin were found. 
- General Bazel literature reports: with a properly seeded remote cache and `--config=ci`, typical CI hit rates for large monorepos are 70–90% on non-main-branch builds (i.e., only changed modules rebuild).
- The 2024 empirical study (3,500 experiments, 383 GitHub projects) found: incremental builds achieve **median 4.22x speedup** (tool-independent cache) and **4.71x speedup** (Bazel-specific cache) vs clean builds.

---

## 4. Developer Experience Features

### `bazel run` / `bazel test`

- `bazel run //kmp:run` — analogous to `./gradlew run`. Builds the target and executes it.
- `bazel test //...` — runs all tests. `bazel test //kmp:jvmTest` — runs specific test target.
- Test caching: Bazel caches test results by input hash. If no inputs changed, the test is reported as passed from cache (no rerun). This is a significant CI speedup.
- Bazel test results are cached even across machines via remote cache — a PR that shares an ancestor with main can skip tests already green on main.

### `ibazel` (Watch Mode)

- [ibazel](https://github.com/bazelbuild/bazel-watcher): Watches source files and re-runs a Bazel command on change. Analogous to `gradle -t` (continuous build).
- Supports `ibazel run //app:desktop` for live-reload style development.
- Note: no specific 2024–2025 ibazel improvements were found in this research; it is a stable tool.

### IntelliJ / Android Studio Bazel Plugin

- **Legacy plugin** (Google): [Bazel for IntelliJ](https://plugins.jetbrains.com/plugin/8609-bazel-for-intellij) — still available but being deprecated in 2026.
- **New JetBrains plugin** (GA as of 2025.2): Full IDE experience — code assistance during editing, run/debug targets, code coverage, Search Everywhere for Bazel targets. Android support available for early testing as of Q2 2025.
- Transition: JetBrains took over from Google's original plugin. Google's plugin will be kept with compatibility updates through 2025, fully deprecated 2026.
- Sources: [Bazel Plugin GA Release — JetBrains Blog](https://blog.jetbrains.com/idea/2025/07/bazel-ga-release/), [Getting Started with JetBrains Bazel Plugin](https://blog.jetbrains.com/idea/2024/12/getting-started-with-the-jetbrains-bazel-plugin/)
- **Critical gap for SteleKit**: Android Studio Bazel plugin is built from a separate Google branch; Android Studio support lags IntelliJ support significantly.

---

## 5. Migration Tooling

### Airin + Pendant (Turo / Morfly)

- [Airin](https://github.com/Morfly/airin): Gradle plugin that analyzes project structure and generates Bazel BUILD files.
- Pendant: Kotlin DSL for generating Starlark code programmatically.
- Best for: Android/JVM Kotlin projects. Does not handle KMP targets (iOS, Wasm).
- Last known active: May 2024.

### Grazel (Grab)

- [Grazel](https://github.com/grab/Grazel): Gradle plugin for incremental, automated Gradle-to-Bazel migration.
- Generates `WORKSPACE`, `BUILD.bazel` files from existing Gradle scripts.
- Designed for large Android monorepos; Grab uses it for their 2.5M LOC app.
- Does not handle KMP multi-target builds.

### Gazelle Kotlin Extension

- [gazelle-kotlin](https://pkg.go.dev/github.com/srmocher/gazelle-kotlin): Early-stage Gazelle extension for Kotlin that generates `kt_jvm_library` targets from `.kt` files.
- Still experimental / work-in-progress. Not suitable as a primary migration tool.

### bazel-diff (Tinder)

- [bazel-diff](https://github.com/Tinder/bazel-diff): Computes the affected Bazel target set between two Git revisions.
- Enables "run only affected tests" in CI — a major CI time saver for large projects.
- Supports projects with tens of thousands of targets (uses streaming protobuf for scalability).
- BazelCon 2024 talk: "Not Going the Distance: Filtering Tests by Build Graph Distance."

### Bazel Project Generator (Cirrus Labs)

- [bazel-project-generator](https://github.com/cirruslabs/bazel-project-generator): Generates Bazel BUILD files for Kotlin/Java projects.
- Automates ~90% of the work for dependency declarations.

---

## 6. Realistic Timeline / Effort for ~50k LOC KMP Project

### Baseline: Pure Android/Kotlin JVM Projects

- **Small Android app** (no custom Gradle logic, <10 modules): 1–4 weeks with automated tooling (Airin/Grazel).
- **Medium Android app** (20–50 modules, some custom Gradle tasks): 2–3 months with automation + manual fixes.
- **Large Android monorepo** (100+ modules): 5+ months for PoC (Grab's reported timeline); 12–18 months for full migration with CI integration.

### SteleKit-Specific Assessment (KMP with Desktop, Android, iOS, Web)

SteleKit is NOT a typical Android/Kotlin project — it is a full KMP project. This fundamentally changes the migration calculus:

| Target | Bazel Support Status | Effort Implication |
|---|---|---|
| JVM (Desktop) | Supported via `rules_kotlin` | Feasible |
| Android | Supported via `rules_android` + `rules_kotlin` | Feasible but complex (AGP replacement) |
| iOS (Kotlin Native) | **NOT supported** in rules_kotlin | **Blocker** — requires custom rules or waiting for GSoC 2025 output |
| WebAssembly | **NOT supported** | **Blocker** — no Wasm compilation in Bazel Kotlin rules |
| Compose Multiplatform | **NOT supported** | **Blocker** — no Compose compiler integration in Bazel for KMP |

**Honest timeline estimate for full SteleKit migration (all targets)**:
- **JVM-only (Desktop + shared JVM code)**: 2–4 months. Feasible with current tooling.
- **JVM + Android (dropping iOS/Wasm for now)**: 4–8 months. Requires replacing AGP entirely with `rules_android`. Dependency management with `rules_jvm_external` is the main challenge.
- **Full KMP (all 4 targets)**: **Not feasible with current Bazel tooling.** iOS/Kotlin Native and Wasm support must wait for `rules_kotlin` KMP work (GSoC 2025 deliverables, likely available late 2025–2026). Estimated: **12–24+ months**, mostly blocked on upstream tooling maturity.

**Key risks and pain points for any KMP Bazel migration**:
1. `expect/actual` mechanism requires compiler coordination across platform compilations — Bazel has no model for this today.
2. Compose Multiplatform's compiler plugin integration is tightly coupled to the Kotlin Gradle Plugin (KGP); extracting it for Bazel is non-trivial.
3. `rules_jvm_external` (Maven dependency resolution for Bazel) is the only viable dependency manager, but it does not understand Gradle Metadata (`.module` files) which KMP libraries use for platform-specific artifact selection.
4. Every KMP library update (e.g., Kotlinx Coroutines, SQLDelight) requires verifying it works in the Bazel build — no automated mechanism.
5. The developer inner loop (edit → compile → test) requires IntelliJ's Bazel plugin, which has rough edges for Android and no KMP-specific support.

---

## Summary Table

| Topic | Finding |
|---|---|
| Best OSS migration examples | Grab (Grazel, 2.5M LOC), Turo (Airin, large multi-module) |
| KMP support in Bazel | iOS, Wasm, Compose: NOT supported. JVM + Android: feasible. |
| Remote caching options | BuildBuddy free tier (easiest), bazel-remote (self-hosted), bazel-github-actions-cache |
| Incremental build benefit | 4.22–4.71x median speedup over clean builds with caching |
| IntelliJ IDE support | JetBrains new plugin GA 2025.2; Android Studio lags |
| Migration tooling | Airin, Grazel, bazel-diff — all Android/JVM only, not KMP-aware |
| Timeline (JVM-only) | 2–4 months |
| Timeline (JVM + Android) | 4–8 months |
| Timeline (full KMP) | 12–24+ months, blocked on upstream rules_kotlin KMP work |

---

## Sources

- [How Grab is Blazing Through the Superapp Bazel Migration](https://engineering.grab.com/how-grab-is-blazing-through-the-super-app-bazel-migration)
- [Grazel — Gradle to Bazel Migration Tool (Grab)](https://github.com/grab/Grazel)
- [Boosting Bazel Adoption on Android With Automation — Turo Engineering](https://medium.com/turo-engineering/boosting-bazel-adoption-on-android-with-automation-6dc79d298628)
- [Airin — Automated Gradle-to-Bazel Migration](https://github.com/Morfly/airin)
- [Automated migration with Airin — ProAndroidDev](https://proandroiddev.com/automated-migration-of-your-projects-to-bazel-build-system-with-airin-304fa8b3680c)
- [Migrating Android Projects from Gradle to Bazel — Medium](https://medium.com/@imamyusufb123/migrating-android-projects-from-gradle-to-bazel-a-complete-guide-for-developers-4e67aea0883c)
- [Taming Bazel Dependency Conflicts: An Android Case Study](https://medium.com/@taufik.amary/taming-bazel-dependency-conflicts-an-android-case-study-44b7926839be)
- [rules_kotlin — GitHub](https://github.com/bazelbuild/rules_kotlin)
- [Kotlin Bazel Cross-Platform Roadmap Issue #567](https://github.com/bazelbuild/rules_kotlin/issues/567)
- [Kotlin Bazel Roadmap (rules_kotlin)](https://bazel.googlesource.com/rules_kotlin/+/59dc7473c777b5054e91c1af6b95ed0ecbdc0ace/ROADMAP.md)
- [GSoC 2025 Kotlin — KMP Bazel project](https://kotlinlang.org/docs/gsoc-2025.html)
- [Compose Multiplatform Bazel support — Kotlinlang Slack](https://slack-chats.kotlinlang.org/t/3145636/does-compose-multiplatform-support-bazel)
- [We need a build system for Kotlin — Kotlinlang Slack](https://slack-chats.kotlinlang.org/t/16406255/we-need-a-build-system-for-kotlin-we-have-reached-our-limit-)
- [Bazel vs Gradle — Buildkite](https://buildkite.com/resources/comparison/bazel-vs-gradle/)
- [Gradle vs Bazel for JVM Projects — Gradle Blog](https://blog.gradle.org/gradle-vs-bazel-jvm)
- [Does Using Bazel Help Speed Up CI Builds? (2024 Empirical Study)](https://dl.acm.org/doi/10.1007/s10664-024-10497-x)
- [Android Build Performance — Bazel Docs](https://bazel.build/docs/android-build-performance)
- [Bazel 7.0 LTS Release](https://blog.bazel.build/2023/12/11/bazel-7-release.html)
- [BuildBuddy RBE with GitHub Actions](https://www.buildbuddy.io/docs/rbe-github-actions/)
- [Bazel Remote Caching Explained — BuildBuddy](https://www.buildbuddy.io/blog/bazels-remote-caching-and-remote-execution-explained/)
- [buchgr/bazel-remote](https://github.com/buchgr/bazel-remote)
- [bazel-github-actions-cache](https://github.com/tsawada/bazel-github-actions-cache)
- [Remote Caching — Bazel Docs](https://bazel.build/remote/caching)
- [bazel-diff — Tinder](https://github.com/Tinder/bazel-diff)
- [JetBrains Bazel Plugin GA Release](https://blog.jetbrains.com/idea/2025/07/bazel-ga-release/)
- [Introducing the New Bazel Plugin EAP for IntelliJ IDEA](https://blog.jetbrains.com/idea/2024/12/introducing-the-new-bazel-plugin-eap-for-intellij-idea/)
- [Getting Started with JetBrains Bazel Plugin](https://blog.jetbrains.com/idea/2024/12/getting-started-with-the-jetbrains-bazel-plugin/)
- [Bazel with Android + Kotlin — Scalio](https://scal.io/blog/bazel-with-android-kotlin)
- [KMP Scalability Challenges — ProAndroidDev](https://proandroiddev.com/kotlin-multiplatform-scalability-challenges-on-a-large-project-b3140e12da9d)
- [gazelle-kotlin](https://pkg.go.dev/github.com/srmocher/gazelle-kotlin)
- [bazel-project-generator — Cirrus Labs](https://github.com/cirruslabs/bazel-project-generator)
