# Pitfalls: Bazel Full CI Migration

Severity and likelihood ratings: **High / Medium / Low** across each axis.

---

## 1. Detekt

### 1.1 Custom rule set compiled from buildSrc — JAR must be available at Bazel analysis time
**Severity: High | Likelihood: High**

The current setup passes `detektPlugins(files("${rootProject.projectDir}/buildSrc/build/libs/buildSrc.jar"))` — a Gradle-built JAR — to the Detekt Gradle task. Under Bazel, there is no equivalent auto-build of buildSrc; the custom rule set JAR (`SteleKitRuleSetProvider` + 15 rule classes) must be built as a first-class `kt_jvm_library` target and passed to a Bazel Detekt action as a classpath input. If the JAR is stale or absent, Detekt silently skips all custom rules rather than failing — meaning CI would pass with rule regressions undetected.

**Mitigation**: Define `//buildSrc:detekt_rules` as a `kt_jvm_library` and wire it into whatever genrule/Detekt binary action is used. Add a `SteleKitRuleSetProvider` load test to fail fast if the plugin is missing.

---

### 1.2 No type resolution currently — some custom rules may degrade silently
**Severity: Medium | Likelihood: Medium**

None of the 15 custom rules in `buildSrc/src/main/kotlin/dev/stapler/detekt/` use `BindingContext`/`KtAnalysisSession` calls, so they are syntax-only. The `io.nlopez.compose.rules:detekt:0.4.27` plugin also ships with its own compiled-classpath-optional mode. The current Gradle invocation (`./gradlew :kmp:detekt`) does **not** enable `--type-resolution`, so there is no gap to close there.

However, if a future rule needs type resolution, adding it to the Bazel Detekt action will require the fully compiled KMP classpath as an explicit input — which in turn requires all KMP targets to build before Detekt runs, eliminating any parallelism benefit.

**Mitigation**: Document explicitly that type-resolution is off; gate any future type-resolution rules behind a separate `detekt-typed` target that depends on `//kmp:jvm_library`.

---

### 1.3 Compose rules plugin version skew
**Severity: Medium | Likelihood: Low**

The Gradle config pins `io.nlopez.compose.rules:detekt:0.4.27` by Maven coordinate. Under Bazel, Maven resolution goes through the `MODULE.bazel` / `maven` extension — if the lock file version drifts or a different transitive is resolved, findings can change. Because there is no baseline enforcement between Gradle and Bazel runs, the first Bazel Detekt CI run may surface zero, more, or different findings without any code change.

**Mitigation**: Pin the same coordinates in `MODULE.bazel` and lock them. Run both Gradle and Bazel Detekt on the same PR during the transition period, diff SARIF outputs, and document any permanent divergence before decommissioning the Gradle job.

---

### 1.4 Detekt needs compiled baseline.xml; Bazel sandbox isolates the repo root
**Severity: Low | Likelihood: Medium**

`config/detekt/baseline.xml` is read at detekt startup to suppress known findings. When the Detekt binary runs inside a Bazel sandbox, the working directory is a hermetic tree, not the source root. The baseline file must be declared as an explicit `data` input in the Bazel rule. Missing the declaration means every suppressed finding re-emerges and fails CI.

**Mitigation**: Declare `//kmp/config/detekt:baseline.xml` as a `data` dep in the Detekt genrule/action.

---

## 2. Roborazzi (Screenshot Tests)

### 2.1 Sandbox write-blocking — fundamental architecture mismatch
**Severity: High | Likelihood: High**

`RoborazziRule` (record mode) writes PNG baselines **in-place next to the test source file** (resolved via `System.getProperty("user.dir")` at runtime). Bazel's linux-sandbox mounts the source tree read-only inside a content-addressed execution root; any attempt to write outside the declared outputs fails with `Permission denied`. This affects both `recordRoborazziDebug` and `recordRoborazziDesktop` modes.

The existing CI intentionally does **not** commit baselines:
```
# Goldens are intentionally not committed — rendering differs between machines.
# To enable regression detection: download artifacts from a baseline CI run,
# commit to kmp/src/androidUnitTest/snapshots/images/, then switch to verifyRoborazziDebug.
```
This means the project currently runs in **record-only** mode (no verification), uploading PNGs as CI artifacts. Under Bazel the only workable equivalent is a `genrule` or `sh_binary` wrapper that:
1. Runs Gradle's `recordRoborazziDebug` / `recordRoborazziDesktop` task as a Bazel action
2. Declares the snapshot output directory as an explicit Bazel output tree artifact

This is a genrule-wrapping-Gradle pattern, not native Bazel integration, and forfeits incremental caching on individual test files.

**Mitigation**: Accept the genrule wrapper for the near term. Do not attempt native `kt_jvm_test` integration for Roborazzi until `rules_roborazzi` exists (currently no published artifact). Track the upstream issue.

---

### 2.2 JVM Desktop screenshots require X11 — already worked around, but fragile
**Severity: Medium | Likelihood: Medium**

The `.bazelrc` already mounts `/tmp/.X11-unix` into every test sandbox:
```
test --sandbox_add_mount_pair=/tmp/.X11-unix
```
The `DesktopScreenshotTest` and `MobileScreenshotTest` tests use `createComposeRule()` + `captureRoboImage` from `roborazzi-compose-desktop`. This requires a live X11/Wayland socket. The mount pair covers the local case. On CI the `ci.yml` wrapper runs under `xvfb-run`. The Bazel CI workflow (`bazel-ci.yml`) currently has no Roborazzi targets, so this is not yet exercised — if Roborazzi tests move to Bazel, the `bazel-ci.yml` job must also start xvfb before `bazel test`.

**Mitigation**: In `bazel-ci.yml`, add `xvfb-run --auto-servernum bazel test //kmp:jvm_screenshot_tests` rather than a bare `bazel test`.

---

### 2.3 Font/rendering non-determinism across JVM environments
**Severity: High | Likelihood: Medium**

Compose Desktop renders fonts via the system font stack (FreeType / Skia on Linux). CI runners using `ubuntu-latest` may differ from developer machines in:
- installed font families (missing fonts fall back, changing glyph metrics)
- FreeType version and hinting flags
- Skia rendering pipeline differences across JVM versions (JDK 21 vs 17 vs 11 can produce sub-pixel differences)

The project acknowledges this ("Goldens are intentionally not committed — rendering differs between machines"). Under Bazel, remote caching amplifies this: a test result cached by an x86 GitHub runner with Ubuntu 22.04 would be served to a developer on Ubuntu 24.04 with a different font stack, potentially surfacing false failures on cache hits.

**Mitigation**: Keep screenshot tests in `--no_cache` mode or tag them `tags = ["no-cache"]` / `tags = ["local"]` in the Bazel BUILD file until a reproducible hermetic font environment is established.

---

### 2.4 KSP annotation processor — rules_kotlin support required
**Severity: Medium | Likelihood: Medium**

Roborazzi's `@RoborazziConfig` annotations are processed by KSP at build time. The patched `rules_kotlin` in this repo (see `third_party/patches/rules_kotlin_kmp.patch`) adds `common_srcs` for KMP expect/actual but does not add KSP support. KSP integration in `rules_kotlin` is tracked upstream (rules_kotlin#567) and is experimental as of 2.0.x. Without KSP, any Roborazzi annotation-driven configuration will be silently skipped rather than failing with a compile error.

**Mitigation**: Audit which Roborazzi annotations the project uses. If `@RoborazziConfig` or `@ExperimentalRoborazziApi` are used for runtime behavior (not just for compilation), the Bazel build will need a KSP plugin wired via `plugins = [...]` in `kt_jvm_test`. Verify with a canary test that expected config objects are present at runtime.

---

## 3. Emulator / Instrumented Android Tests

### 3.1 Bazel sandbox blocks /dev/kvm and ADB socket
**Severity: High | Likelihood: High**

`android_instrumentation_test` under `rules_android 0.7.1` is marked experimental. It requires:
- `/dev/kvm` accessible (blocked by Bazel's linux-sandbox by default)
- An ADB server running outside the sandbox
- The emulator process itself or an already-running AVD

The linux-sandbox `--sandbox_add_mount_pair` directive can expose `/dev/kvm`, but this requires explicit configuration and elevated permissions on the CI runner. GitHub-hosted runners do not support KVM on `ubuntu-latest` (it is available only on `ubuntu-latest` with nested virtualization, which is not enabled by default on free tier).

**Mitigation**: Gate instrumented tests behind `--config=android` + `--config=emulator` and require `self-hosted` runners with KVM. Alternatively, keep instrumented tests in Gradle for the foreseeable future — this matches the requirement declaration that `android_instrumentation_test` is experimental.

---

### 3.2 Emulator warmup time inflates per-action wall clock
**Severity: High | Likelihood: High**

If the emulator must start within a Bazel action (rather than being pre-warmed outside Bazel), each test action pays the full ~60–90 second cold-start penalty. With Bazel's action parallelism, multiple emulator instances may attempt to start concurrently, exhausting RAM and KVM slots on the runner. This alone can push wall-clock time above the 20% regression budget.

**Mitigation**: Use an external emulator lifecycle script that starts/stops the emulator outside Bazel, then passes `--device_serial=emulator-5554` as a test flag. The `android_instrumentation_test` rule supports `--test_env=ADB_SERIAL` for this pattern.

---

### 3.3 Emulator test flakiness interacts poorly with Bazel caching
**Severity: Medium | Likelihood: High**

Bazel caches test results by action key (inputs + env). Flaky instrumented tests may be cached as "pass" from a lucky run and never re-executed until inputs change. `--runs_per_test=3` (Bazel retry) re-runs the full action including emulator cold-start, which is expensive. The alternative (`--test_keep_going` + `--flaky_test_attempts=3`) still caches failures.

**Mitigation**: Tag emulator tests `tags = ["no-cache", "external"]` in BUILD files. This prevents stale cache hits and signals to the scheduler that these tests talk to external state.

---

### 3.4 ADB serial targeting in a hermetic environment
**Severity: Low | Likelihood: Medium**

If multiple Android tests run in parallel (multiple Bazel test actions), each needs to target a specific emulator instance via `adb -s emulator-XXXX`. Without explicit serial assignment, adb defaults to the first connected device, causing test cross-contamination. The current Gradle setup avoids this because tests run single-threaded against the default connected device.

**Mitigation**: Assign stable ports per test shard (e.g. 5554, 5556) and inject `ADB_SERIAL` via `env = {"ADB_SERIAL": "emulator-5554"}` in each `android_instrumentation_test` target.

---

## 4. General Migration Pitfalls

### 4.1 Dual-running disagreement period — Gradle vs. Bazel test results
**Severity: Medium | Likelihood: High**

During the transition, both Gradle and Bazel CI jobs run. Their results can diverge because:
- Gradle's `ciCheck` task runs detekt, jvmTest, androidUnitTest, and assembleDebug in one Gradle invocation; Bazel separates these into independent actions
- Classpath differences: Gradle uses POM-resolved fat classpaths; Bazel uses strict transitive deps from the explicit BUILD graph
- JVM flag differences: Gradle sets `jvmArgs("-Djava.awt.headless=false")` and X11 env; Bazel must replicate these exactly via `jvm_flags` in `kt_jvm_test`

A test that passes Gradle and fails Bazel (or vice versa) has no automated tiebreaker. The migration plan must define which system is authoritative per-phase.

**Mitigation**: Define an explicit "Bazel is authoritative for: business_tests, jvm_tests (non-screenshot)" and "Gradle is authoritative for: screenshot tests, android instrumented tests" cutover document. Fail the PR if either system fails for its authoritative targets.

---

### 4.2 MODULE.bazel.lock churn from new Maven dependencies
**Severity: Medium | Likelihood: Medium**

Every new `maven.artifact()` in `MODULE.bazel` requires regenerating `MODULE.bazel.lock`. The lock file is a large JSON (~10k lines) containing SHA256 hashes for every resolved artifact. Detekt plugins (`detekt-api`, `io.nlopez.compose.rules:detekt:0.4.27`, `buildSrc` custom rules), Roborazzi, and KSP dependencies are not currently in the Bazel dependency graph — adding them will generate lock file noise in every PR during the migration period.

**Mitigation**: Batch all new Maven artifact additions into a single "deps migration" PR to minimize per-PR lock churn. Use a `.gitattributes` entry to mark `MODULE.bazel.lock` as a generated merge=ours file.

---

### 4.3 Roborazzi baseline storage is outside the Bazel output tree
**Severity: High | Likelihood: High**

The project stores (or would store) Roborazzi PNGs at `kmp/src/androidUnitTest/snapshots/` — inside the source tree. Bazel cannot declare source-tree writes as outputs from a sandbox action. Even in the genrule-wrapping-Gradle pattern, the sandbox must either be disabled (`--spawn_strategy=local`) or the output directory must be declared explicitly and then copied back. The existing CI workflow uses `recordRoborazziDebug` (writes in-place) then uploads the snapshots directory as a GitHub Actions artifact. Under Bazel, mimicking this requires either:
- `--spawn_strategy=local` for the Roborazzi action (disables hermeticity)
- A post-action copy from the Bazel output base into the source tree (fragile)
- A custom Starlark rule that runs Gradle in non-sandboxed mode

**Mitigation**: Adopt a hybrid approach: keep Roborazzi under Gradle for CI record/verify, wrap only in a Bazel `sh_binary` target that delegates to Gradle. Do not attempt in-sandbox baseline writes. Document this as a known limitation in the migration ADR.

---

### 4.4 buildSrc custom rules need their own Bazel target
**Severity: Medium | Likelihood: High**

The `buildSrc` Gradle subproject produces `buildSrc.jar` which is referenced in `detektPlugins(files(...))`. Under Bazel, `buildSrc` has no meaning — Gradle's special-case convention doesn't apply. The 15 custom detekt rule classes plus `SteleKitRuleSetProvider` must be compiled as `//buildSrc:detekt_rules` using `kt_jvm_library`. This requires:
- A new `buildSrc/BUILD.bazel` file
- All 15 rule files explicitly listed in `srcs`
- `detekt-api` on the compile classpath
- The JAR passed as an explicit `data` input to the Detekt action

This is moderate effort but straightforward — the risk is incomplete migration where some rules silently fail to load.
