# Build vs. Buy: Bazel CI Migration

**Targets**: Detekt, Roborazzi screenshot tests, Android emulator smoke tests
**Context**: Bazel 9.1.1 · bzlmod · rules_kotlin 2.3.20 · rules_android 0.7.1 · GHA CI

---

## Target 1: Detekt (`./gradlew :kmp:detekt` → `bazel test //kmp:detekt`)

### Current Gradle config (relevant facts)
- Detekt 1.23.x via Gradle plugin `io.gitlab.arturbosch.detekt`
- Custom ruleset: `buildSrc.jar` (local `kt_jvm_library` output)
- Compose rules: `io.nlopez.compose.rules:detekt:0.4.27` (Maven artifact)
- Config: `config/detekt/detekt.yml` + `config/detekt/baseline.xml`
- Sources: 4 KMP source sets (commonMain, jvmMain, androidMain, iosMain)

### Note: `kmp/BUILD.bazel` already has a comment (line 91–103) that defers Detekt to Gradle and flags Option B (genrule + CLI JAR) as the upgrade path. This research evaluates whether native rules are now worth it.

---

### Option 1 — `buildfoundation/bazel_rules_detekt` (BCR)

**What it is**: A dedicated `kt_detekt_test` Starlark rule. BCR package name: `rules_detekt`. Latest: v0.8.1.13 (Feb 2025). Bundles Detekt 1.23.8 + Kotlin 2.0.21.

**Pros**
- On BCR → single `bazel_dep(name = "rules_detekt", version = "0.8.1.13")`, no patch needed
- Full bzlmod support confirmed
- `plugins` attribute accepts any `JavaInfo` label → `buildSrc.jar` from `kt_jvm_library` works directly
- Type-resolved analysis supported via `jvmTarget` / `language_version` attributes
- Actively maintained (5 releases in Feb 2025; repo updated June 2026)
- Bazel action caching: Detekt only reruns when inputs (sources, config, plugins) change

**Cons**
- Detekt version pinned to 1.23.8 — cannot independently upgrade Detekt without bumping rules_detekt
- Detekt 2.x support exists but marked alpha/not-recommended; project uses 1.23.x so no issue now
- JDK 25+ breaks Detekt 1.23.x (hard compiler version check); not a concern while JVM target stays 21
- Need to wire 4 source sets + config/baseline as Bazel file inputs — moderate BUILD plumbing

**Verdict: Recommended.** The BCR availability and `JavaInfo`-based plugin support resolve all key concerns from the existing BUILD.bazel comment. One `bazel_dep` + a `kt_detekt_test` target is ~30 lines of Starlark. Bazel caching benefit is real for a 4-source-set analysis on a large KMP codebase.

---

### Option 2 — Gradle genrule wrapper

**Pros**: Zero migration risk; existing config works as-is

**Cons**: Reintroduces Gradle daemon on every CI run (~15–30s cold start), breaks hermeticity, no Bazel cache benefit (genrule is always-dirty or requires `--cache_test_results=no` workaround), inconsistent with the project's hermetic direction

**Verdict: Not recommended.** The existing `kmp/BUILD.bazel` comment already notes this as a temporary fallback. Given rules_detekt is BCR-available and production-ready, the genrule path adds permanent tech debt for zero benefit.

---

### Option 3 — Custom Starlark `kt_detekt_test` rule

**Pros**: Full control over Detekt version

**Cons**: ~200–400 lines of Starlark to replicate what rules_detekt already does; maintenance burden; no need given Option 1 exists

**Verdict: Not recommended.**

---

### Option 5 — `java_test` with Detekt CLI JAR

**What it is**: Pull `detekt-cli-VERSION-all.jar` from Maven Central via `rules_jvm_external`, wrap in `java_test`.

**Pros**: Simple; Detekt CLI is a single fat JAR; no additional Bazel rules needed

**Cons**: Type resolution requires passing `--classpath` with the full compilation classpath — non-trivial to assemble from Bazel depsets. The Detekt maintainers explicitly recommend the Gradle plugin for classpath feeding ([discussion #4945](https://github.com/detekt/detekt/discussions/4945)). Without type resolution, Compose rules (`io.nlopez.compose.rules`) lose most of their checks. `java_test` is also semantically wrong (not a test, a linter).

**Verdict: Viable but inferior to Option 1.** Only worth it if type resolution is not needed. This project uses Compose rules which depend on type resolution; skip it.

---

### Detekt Decision: **Use `rules_detekt` from BCR**

---

## Target 2: Roborazzi (`./gradlew :kmp:recordRoborazziDebug` → Bazel equivalent)

### Current Gradle config
- Roborazzi 1.59.0 via Gradle plugin `io.github.takahirom.roborazzi`
- Runs on JVM via Robolectric (no emulator needed) using `@GraphicsMode(NATIVE)`
- Tests live in `jvmTest` / `androidUnitTest` source sets
- Two modes: **record** (generate baselines) and **verify** (compare against baselines)
- Baseline PNGs checked into the repo
- Uses `roborazzi-compose-desktop:1.59.0` — KMP desktop screenshots included

### Option 1 — Native Bazel rules for Roborazzi

Roborazzi's own README explicitly states: *"As of now, there is no direct support for running Roborazzi with Bazel."* No BCR package exists. No community `rules_roborazzi` has traction.

**Verdict: Not available.** Skip.

---

### Option 2 — Gradle genrule wrapper

Since Roborazzi is a Gradle plugin (it hooks into the Gradle task graph for record/verify/compare orchestration), a genrule is the most practical path today.

**Pros**
- Zero migration risk — existing Roborazzi config, Robolectric setup, and baseline PNGs work unchanged
- The project already uses a `genrule` for the WASM target with the same "local, no sandbox" pattern (`tags = ["local"]`)
- Hermetic precedent for non-hermetic genrules is established in this codebase

**Cons**
- Not hermetic — `tags = ["local"]`, no Bazel cache benefit
- Record mode (`bazel run //kmp:roborazzi_record`) and verify mode (`bazel test //kmp:roborazzi_verify`) require two separate genrule targets
- Gradle daemon startup cost on every run
- The genrule output (baseline PNG files) cannot be declared as Bazel `outs` since they're written to the source tree, not `bazel-bin` — use `local_repository` or just leave the record target as a `run` target with no declared outputs

**Verdict: Recommended (only viable option).** Wire as two genrules mirroring the existing WASM pattern:
- `//kmp:roborazzi_record` — `bazel run`, wraps `./gradlew :kmp:recordRoborazziDebug`, `tags = ["local", "manual"]`
- `//kmp:roborazzi_verify` — `bazel test`, wraps `./gradlew :kmp:verifyRoborazziDebug`, `tags = ["local"]`

---

### Option 3 — Custom Starlark `kt_screenshot_test` rule

**Pros**: Hermetic, cacheable

**Cons**: Roborazzi's screenshot rendering depends on Robolectric Native Graphics (RNG) which calls into native `.so` libraries. Wiring native library sandboxing in Bazel for a JVM test rule is significant Starlark work (analogous to the existing `rules_android_unzip.patch` effort, but larger). Roborazzi has no public Bazel integration surface (no `@bazel` annotation, no CLI mode). This would require reverse-engineering the Gradle plugin's task graph. Estimated 2–4 weeks of Starlark work with high maintenance risk.

**Verdict: Not recommended** for initial migration. Revisit if Roborazzi ships native Bazel support.

---

### Option 4 — Switch to Paparazzi

**Current state**: Paparazzi 2.0.0-alpha05 (released May 2026) — actively developed but **alpha**.

**Pros**
- Cash App maintains it (well-funded, active)
- JVM-based (no emulator), similar positioning to Roborazzi
- No known Bazel rules but same genrule wrapper approach would apply

**Cons**
- Alpha API — 2.0 is a major rewrite; production stability unknown
- **Does not support Compose Multiplatform or KMP desktop** — this project renders KMP composables (including `roborazzi-compose-desktop:1.59.0`); Paparazzi is Android-only
- Would require re-recording all baseline PNGs (significant CI noise for reviewers)
- Paparazzi is incompatible with Robolectric (they both mock the Android framework) — the project uses Robolectric extensively in `androidUnitTest`; coexistence is problematic

**Verdict: Not recommended.** The KMP desktop screenshot dependency (`roborazzi-compose-desktop`) has no Paparazzi equivalent. Switching is a major regression.

---

### Roborazzi Decision: **Gradle genrule wrapper (two targets: record + verify)**

---

## Target 3: Android Emulator Smoke Tests (`./gradlew :androidApp:connectedDebugAndroidTest`)

### Option 1 — `android_instrumentation_test` (native Bazel / rules_android)

**Critical finding**: `rules_android 0.7.1`'s exported rules (`rules/rules.bzl`) do **not** include `android_instrumentation_test`. Only `android_local_test` (Robolectric) is exported. The `android_instrumentation_test` rule exists in Bazel's built-in Android support (deprecated, pre-Starlark), but:
- Requires KVM hardware acceleration on Linux (available on GHA `ubuntu-latest` via nested virtualization, but requires `reactivecircus/android-emulator-runner`)
- Requires Xvfb for headless operation
- Requires `@android_test_support` workspace setup (a separate legacy repository, not BCR-available)
- Known issue: forked `adb` server processes are not terminated after tests
- The GHA setup for Bazel + Android emulator is not documented in rules_android; the Bazel docs reference a 2018-era GCE gist
- No bzlmod-compatible path for `@android_test_support`

**Pros**: Native Bazel target, hermetic in principle

**Cons**: Effectively unsupported in rules_android 0.7.1 + bzlmod; would require `WORKSPACE` fallback or a large custom implementation; KVM + Xvfb CI setup complexity; `adb` leak issue

**Verdict: Not recommended** for bzlmod-based setup.

---

### Option 2 — Gradle genrule wrapper

Same pattern as WASM and (proposed) Roborazzi — a `genrule` wrapping `./gradlew :androidApp:connectedDebugAndroidTest`.

**Pros**: Works today with existing GHA `android-emulator-runner` action; zero migration risk

**Cons**: Not hermetic; no Bazel cache benefit; the emulator runner is a GHA action, not a local tool, so the genrule cannot actually start an emulator — it would only work in CI where the emulator is already running

**Verdict: Viable but limited.** The genrule would only be meaningful as a CI alias, not for local developer runs. The emulator lifecycle is managed by the GHA action (`reactivecircus/android-emulator-runner`), not by Bazel.

---

### Option 3 — Keep as Gradle CI step, do not migrate to Bazel

**Pros**
- The emulator tests already have their own GHA job with the correct `android-emulator-runner` action
- No Bazel cache benefit possible (emulator tests are inherently side-effectful and device-dependent)
- Gradle manages the full lifecycle (APK build → install → run → collect results)
- `ciCheck` in `build.gradle.kts` already has the correct `-PciInstrumentedTests` opt-in pattern

**Cons**: One more thing that is not in Bazel; inconsistency with the migration direction

**But consider**: The requirement says "Bazel equivalent" — however, since `android_instrumentation_test` is not available in rules_android 0.7.1 + bzlmod, and the tests are inherently device-dependent (no Bazel cache benefit even if it worked), keeping them as a Gradle/GHA step is architecturally correct, not a shortcut.

**Verdict: Recommended.** Keep the emulator tests as a dedicated GHA job using `android-emulator-runner` + Gradle. Add a `//kmp:emulator_tests` comment-target or Bazel alias that shells out to Gradle for consistency in developer documentation if needed, but do not invest in a native Bazel instrumentation test integration.

---

### Android Emulator Decision: **Keep as Gradle CI step; do not migrate to Bazel**

---

## Decision Matrix Summary

| Target | Option | Verdict | Effort |
|---|---|---|---|
| Detekt | `rules_detekt` from BCR | **Recommended** | Low (~30 LOC BUILD) |
| Detekt | Gradle genrule | Not recommended | — |
| Detekt | `java_test` + CLI JAR | Viable fallback | Medium |
| Roborazzi | Gradle genrule (record + verify) | **Recommended** | Low (~20 LOC BUILD) |
| Roborazzi | Custom Starlark rule | Not recommended | Very high |
| Roborazzi | Switch to Paparazzi | Not recommended | High + regression |
| Android emulator | Keep Gradle CI step | **Recommended** | Zero |
| Android emulator | `android_instrumentation_test` | Not recommended | Very high + blocked |
| Android emulator | Gradle genrule | Viable fallback | Low |

## Recommended Approach Per Target

1. **Detekt → `rules_detekt` (BCR)**: Add `bazel_dep(name = "rules_detekt", version = "0.8.1.13")` to `MODULE.bazel`. Wire a `kt_detekt_test` target with `buildSrc.jar` (via `kt_jvm_library`) as a plugin and the 4 KMP source sets as inputs. Replace the existing `//kmp:detekt` BUILD comment with a real target.

2. **Roborazzi → Gradle genrule (two targets)**: Add `//kmp:roborazzi_record` (`bazel run`, manual) and `//kmp:roborazzi_verify` (`bazel test`, local) using the same `tags = ["local"]` pattern as `//kmp:web_dist`. CI runs verify only; developers run record manually.

3. **Android emulator tests → Keep Gradle/GHA**: No Bazel migration. Document explicitly in `kmp/BUILD.bazel` that emulator tests are a Gradle-only CI step until `android_instrumentation_test` lands in rules_android with bzlmod support.
