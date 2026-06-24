# Architecture Research: Bazel Full CI Migration

**Date**: 2026-06-24  
**Scope**: Detekt lint, Roborazzi screenshot tests, Android emulator smoke tests  
**Constraint set**: Bazel 9.1.1, rules_kotlin 2.3.20, rules_android 0.7.1, bzlmod, hermetic linux-sandbox

---

## Codebase Baseline

### Existing Bazel graph

The project has a mature Bazel setup for JVM and Android targets. Key observations from reading the existing BUILD files:

- `//kmp/src/androidUnitTest/kotlin:android_unit_tests` already uses `android_local_test` with Robolectric; Roborazzi tests are **explicitly excluded** via glob patterns `**/*Roborazzi*.kt` and `**/*Screenshot*.kt` with a comment "no Bazel integration; kept in Gradle"
- `//kmp:web_app` demonstrates the established genrule-with-Gradle pattern (`tags = ["local"]`) for cases where native Bazel rules are absent
- `//kmp/BUILD.bazel` has a comment block (lines 91–104) explicitly documenting that Detekt was evaluated and **intentionally left as Gradle** — "Option A: Detekt runs as a standalone CI step via the Gradle wrapper — not a Bazel target — because Bazel caching does not benefit a fast linter that already has its own incremental cache"
- Detekt uses a custom buildSrc.jar ruleset plus `io.nlopez.compose.rules:detekt:0.4.27` that must be discoverable at analysis time
- Roborazzi `UiStateScreenshotTest` uses `@RunWith(RobolectricTestRunner::class)` + `captureRoboImage()` — it is a standard Robolectric test augmented with image capture; no golden PNGs are committed yet (snapshots/ does not exist)
- CI runs `recordRoborazziDebug` (not `verifyRoborazziDebug`) — baselines are recorded fresh each run and uploaded as artifacts, **not verified against stored goldens**

### Current Gradle CI jobs to migrate

| Job | Gradle task | Hermetic? | Status in Bazel |
|-----|------------|-----------|-----------------|
| `lint` | `:kmp:detekt` | Yes (Gradle wrapper) | Intentionally excluded |
| `android` (screenshot portion) | `:kmp:recordRoborazziDebug` | Yes | Excluded from android_unit_tests |
| `android-smoke` | `:androidApp:connectedDebugAndroidTest` | No (emulator) | Not started |

---

## Epic 1: Detekt Lint

### Background

Detekt config lives at `kmp/config/detekt/detekt.yml` + `baseline.xml`. Two plugin JARs are required at runtime: `buildSrc/build/libs/buildSrc.jar` (custom Compose rules) and the Maven artifact `io.nlopez.compose.rules:detekt:0.4.27`. The current `build.gradle.kts` wires these via `detektPlugins(...)`.

### Option 1A — `java_binary` / `java_test` wrapping the Detekt CLI JAR

**Concept**: Declare `com.pinterest:ktlint` / `io.gitlab.arturbosch.detekt:detekt-cli` as a Maven artifact in `MODULE.bazel`, create a `java_test` that invokes Detekt CLI with source files and config as runfile inputs.

```python
# Sketch only — not a working rule
java_test(
    name = "detekt",
    main_class = "io.gitlab.arturbosch.detekt.cli.Main",
    runtime_deps = [
        "@maven//:io_gitlab_arturbosch_detekt_detekt_cli",
        "@maven//:io_nlopez_compose_rules_detekt",
        ":buildSrc_jar",     # genrule building buildSrc.jar
    ],
    args = [
        "--input kmp/src/commonMain/kotlin,kmp/src/androidMain/kotlin,...",
        "--config kmp/config/detekt/detekt.yml",
        "--baseline kmp/config/detekt/baseline.xml",
        "--plugins ...",
    ],
    data = [
        "//kmp/config/detekt:detekt.yml",
        "//kmp/config/detekt:baseline.xml",
        ":buildSrc_jar",
    ] + glob(["kmp/src/**/*.kt"]),
)
```

**Pros**:
- Fully hermetic; Bazel tracks input files → cache hit when no Kotlin source changed
- Output (XML report) is a declared output → `diff_test` can compare against baseline
- Aligns with `bazel test //...` goal

**Cons**:
- Detekt CLI `--input` takes filesystem paths, not Bazel labels; paths inside the sandbox are not the same as source-relative paths — this requires either `$(rootpath)` / `$(execpath)` translation or a wrapper script
- buildSrc.jar is itself built by Gradle (not Bazel); a `genrule` is needed to build it first, reintroducing a Gradle dependency at analysis time
- `detekt-cli` is not a standard Maven coordinate (it's a shadow JAR); may need a `http_file` fetch instead of `maven.install`
- Baseline XML references file paths relative to the project root; those paths look different inside the sandbox

**Verdict**: Feasible but requires a non-trivial wrapper script and a genrule for buildSrc.jar. Medium implementation complexity.

### Option 1B — `genrule` delegating to `./gradlew :kmp:detekt` (current approach formalized)

**Concept**: Wrap the existing Gradle invocation in a Bazel `genrule` with `tags = ["local"]`, identical to the `//kmp:web_app` precedent.

```python
genrule(
    name = "detekt_report",
    srcs = glob(["kmp/src/**/*.kt"]) + [
        "kmp/config/detekt/detekt.yml",
        "kmp/config/detekt/baseline.xml",
    ],
    outs = ["detekt-report.xml"],
    cmd = """
        WORKSPACE=$$(dirname $$(realpath gradlew)) && \
        "$$WORKSPACE/gradlew" :kmp:detekt --no-daemon --quiet && \
        cp "$$WORKSPACE/kmp/build/reports/detekt/detekt.xml" $(OUTS)
    """,
    tags = ["local"],
)
```

**Pros**:
- Zero risk: identical to what CI already runs
- buildSrc.jar, plugin classpath, and Kotlin incremental cache all work as-is
- `bazel test //...` becomes satisfied once the genrule is added
- Minimal investment; proven by the web_app precedent already in the repo

**Cons**:
- Not hermetic; `tags = ["local"]` bypasses sandbox, so cache hits depend on Gradle's own incremental cache, not Bazel's
- Violates the spirit of the migration goal ("covers all three without Gradle")
- Gradle daemon startup adds ~10–15 s per invocation

**Verdict**: Shortest path to `bazel test //...` passing. Appropriate as a **Phase 1 bridge** until buildSrc is migrated to Bazel.

### Option 1C — Custom `kt_detekt_aspect` Starlark rule

**Concept**: A Bazel aspect that visits every `kt_jvm_library` / `kt_android_library` target and generates per-target Detekt XML reports, which are then aggregated.

```python
def _kt_detekt_aspect_impl(target, ctx):
    srcs = [f for f in ctx.rule.files.srcs if f.extension == "kt"]
    if not srcs:
        return []
    output = ctx.actions.declare_file(target.label.name + "_detekt.xml")
    ctx.actions.run(
        inputs = srcs + [ctx.file._config] + ctx.files._plugin_jars,
        outputs = [output],
        executable = ctx.executable._detekt_cli,
        arguments = ["--input", ..., "--output", output.path],
    )
    return [OutputGroupInfo(detekt_reports = depset([output]))]

kt_detekt_aspect = aspect(
    implementation = _kt_detekt_aspect_impl,
    attrs = {
        "_detekt_cli": attr.label(default = "@maven//:detekt_cli", executable = True),
        "_config": attr.label(default = "//kmp/config/detekt:detekt.yml"),
        "_plugin_jars": attr.label_list(default = [...]),
    },
)
```

**Pros**:
- Per-target caching — only re-runs Detekt on changed source sets, not the whole project
- No Gradle dependency at all
- Most hermetic option; cleanly composable with `bazel build --aspects`

**Cons**:
- Highest implementation cost (~2–3 days of Starlark work)
- Detekt CLI JAR must be hermetically available (either via `rules_jvm_external` + fat-JAR download or a custom `http_file`)
- buildSrc.jar still needs Bazel build or committed artifact
- Aspect-based testing does not integrate naturally with `bazel test`; requires a separate test target that runs the aggregation

**Verdict**: Best long-term architecture; too expensive for initial migration.

### Recommended approach for Detekt

**Phase 1 (immediate)**: Keep Gradle CI job. `kmp/BUILD.bazel` already has the rationale documented. The existing comment at lines 91–104 is the correct decision record — no new Bazel target needed.

**Phase 2 (after buildSrc migration to Bazel)**: Switch to Option 1A with a Starlark wrapper script. Aspect approach (1C) is the eventual target if per-file caching becomes a pain point at scale.

**Decision tree**:
```
Does buildSrc.jar build cleanly under rules_kotlin?
  YES → Option 1A (java_test + Detekt CLI JAR)
  NO  → Option 1B (genrule with tags=local) as bridge
        → revisit after buildSrc migration
```

---

## Epic 2: Roborazzi Screenshot Tests

### Background

`UiStateScreenshotTest.kt` uses `@RunWith(RobolectricTestRunner::class)` and `captureRoboImage()` from `com.github.takahirom.roborazzi:roborazzi-compose:1.59.0`. The tests run on the JVM via Robolectric (Android API 29 simulation). No baseline PNGs are committed — CI currently only records, not verifies.

The existing `android_local_test` already compiles and runs non-screenshot Robolectric tests hermetically. The Roborazzi library is a pure Kotlin/JVM library that generates PNG files as side effects — it does not require an Android emulator.

### Option 2A — Extend `android_local_test` with Roborazzi on classpath

**Concept**: Re-include `UiStateScreenshotTest.kt` in the `kt_android_library` and `android_local_test` by adding Roborazzi Maven artifacts to deps. Use `--test_output=all` to surface image output, and declare output PNGs via `--test_env`.

The compilation graph would be:
```
kt_android_library(android_unit_test_lib)
  srcs: glob(["**/*.kt"])   ← no more exclusions
  deps: [
    "@maven//:io_github_takahirom_roborazzi_roborazzi",
    "@maven//:io_github_takahirom_roborazzi_roborazzi_compose",
    ...existing deps...
  ]
  
android_local_test(android_unit_tests)
  deps: [:android_unit_test_lib]
  env: {"roborazzi.output.dir": "$(RULEDIR)/snapshots"}
```

Roborazzi's annotation processor (`roborazzi-gradle-plugin`) is a Gradle plugin only; the core capture library works without the plugin — the plugin only adds `record`/`verify` Gradle tasks. Under Bazel, the test always runs in "record" mode by default.

To switch between record and verify:
- **Record**: test always passes, writes PNGs to a declared output directory
- **Verify**: test fails if `roborazzi.test.verify` system property is set and no baseline PNG exists at the expected path

The baseline PNGs must be declared as `data` inputs for verify mode to work hermetially:
```python
android_local_test(
    name = "screenshot_tests",
    data = glob(["snapshots/images/**/*.png"]),   # baseline PNGs
    jvm_flags = ["-Droborazzi.test.verify=true"],
    ...
)
```

**Pros**:
- No Gradle; fully hermetic within android_local_test's existing sandbox
- Bazel cache invalidation is correct: re-runs when source `.kt` files or baseline PNGs change
- Existing android_local_test infrastructure (Robolectric offline, android-all runfiles) already works
- Recording and verification can be split into two named test targets with different `jvm_flags`

**Cons**:
- Roborazzi 1.59.0 must be added to Maven artifacts in MODULE.bazel
- The snapshot output directory path inside Bazel's test sandbox differs from Gradle's `kmp/src/androidUnitTest/snapshots/images/` — baseline PNG commit paths need coordination
- Image rendering can differ between JVM versions or OS fonts; sandbox must pin the font environment or results will drift
- `android_local_test` does not natively expose test-produced files as declared Bazel outputs; PNGs are written to the test's `TEST_TMPDIR`, not the Bazel output tree

**Verdict**: Most hermetic approach. Feasible with the existing android_local_test infrastructure. The key challenge is wiring PNG output paths.

### Option 2B — `genrule` delegating to `./gradlew :kmp:verifyRoborazziDebug`

**Concept**: Identical to the web_app pattern — a `genrule` with `tags = ["local"]` that calls the Gradle task and declares the result XML as output.

```python
genrule(
    name = "roborazzi_verify",
    srcs = glob([
        "kmp/src/androidUnitTest/kotlin/**/*.kt",
        "kmp/src/androidUnitTest/snapshots/**/*.png",
    ]),
    outs = ["roborazzi-report.xml"],
    cmd = """...""",
    tags = ["local"],
)
```

**Pros**:
- Zero risk; uses identical Gradle task
- Preserves existing baseline management workflow

**Cons**:
- Not hermetic; depends on Gradle, Android SDK installation, correct Java version on path
- CI currently runs `recordRoborazzi` not `verifyRoborazzi` — baselines must be committed first for this to be meaningful
- Does not satisfy "without Gradle" goal

**Verdict**: Bridge option only. Use temporarily until baselines are committed and Option 2A is validated.

### Option 2C — Switch to Paparazzi

**Concept**: Paparazzi (Square) is an alternative screenshot library with native Bazel support via `rules_jvm_external` + standard `kt_jvm_test`. It uses a native rendering engine rather than Robolectric.

**Pros**:
- Official Bazel integration story; Square uses it internally with Bazel
- Rendered output is deterministic (no font/OS variance)
- `java_test` rather than `android_local_test` — simpler dependency graph

**Cons**:
- **Requires migrating existing Roborazzi test code** — different API (`paparazzi.snapshot {}` vs. `captureRoboImage()`)
- Different rendering engine may produce visually different screenshots → all baselines must be re-recorded, violating the "existing PNGs must stay valid" constraint
- Paparazzi renders at pixel-perfect level using Android's LayoutLib, not Robolectric; test behavior may differ
- Additional migration risk on top of the CI migration

**Verdict**: Not recommended for this migration. Violates the "no baseline churn" constraint.

### Cache invalidation analysis for screenshot tests

Bazel re-runs the test (cache miss) when:
1. Any `.kt` source file in the `srcs` glob changes (correct)
2. Any baseline PNG in `data` changes (correct for verify mode)
3. The `jvm_flags` list changes (safe)
4. The `deps` version changes (correct)

Bazel uses cached result (cache hit) when:
- Source files are identical → screenshot tests are **not** re-run on unrelated code changes
- This is the primary benefit over Gradle's `UP-TO-DATE` which requires warming the Gradle cache

### Recommended approach for Roborazzi

**Phase 1**: Add Roborazzi Maven artifacts to MODULE.bazel, remove the exclusions from `android_unit_test_lib`'s glob, create a separate `android_local_test` target for screenshot tests with appropriate `jvm_flags`. Run in record mode initially; commit baseline PNGs.

**Phase 2**: Add a verify-mode test target using committed baselines as `data` inputs.

**Decision tree**:
```
Are baseline PNGs committed to the repo?
  NO  → Record mode android_local_test; upload PNGs as CI artifact; commit to repo
  YES → Verify mode android_local_test with data = glob("snapshots/**/*.png")
        → Does rendering match between Bazel sandbox and Gradle? 
            YES → Retire Gradle Roborazzi job
            NO  → Re-record under Bazel; update baselines
```

**Failure fallback**: If `android_local_test` + Roborazzi fails (e.g., Roborazzi uses Gradle plugin hooks not accessible from the test runtime), fall back to Option 2B genrule as a bridge. **Existing non-screenshot `android_unit_tests` target is isolated and unaffected** — the glob exclusions ensure any failure in the new screenshot target does not break the existing CI job.

---

## Epic 3: Android Emulator Smoke Tests

### Background

The `android-smoke` job runs `./gradlew :androidApp:connectedDebugAndroidTest` against an emulator (API 29, x86_64) started by `reactivecircus/android-emulator-runner@v2`. This requires KVM hardware acceleration and an ADB connection.

### Option 3A — `android_instrumentation_test` (rules_android 0.7.1)

**Concept**: rules_android 0.7.1 ships `android_instrumentation_test` rule (defined in `@rules_android//rules:rules.bzl`). This rule compiles an instrumentation test APK and runs it against either a running emulator or a device.

```python
load("@rules_android//rules:rules.bzl", "android_instrumentation_test")

android_instrumentation_test(
    name = "smoke_tests",
    test_app = ":androidApp_test_apk",
    target_device = "@android_test_support//tools/android/emulated_devices/generic_phone:android_29_x86_64",
    data_binding_enabled = False,
)
```

**API surface in 0.7.1**:
- `test_app`: label of the test APK (built via `android_binary` with `instruments = ...`)
- `target_device`: device descriptor (points to an avd definition in `@android_test_support`)
- `support_apks`: additional APKs to install on the device
- Runs via the `DeviceBroker` infrastructure — requires `--strategy=TestRunner=local` to escape the hermetic sandbox

**Pros**:
- Official rules_android API; the rule exists in 0.7.1
- Output (XML test result) is a declared Bazel test output → `bazel test` reports pass/fail natively
- Integrates with `--test_output=all` and `--flaky_test_attempts`

**Cons**:
- `android_instrumentation_test` requires the `@android_test_support` repository — not currently in MODULE.bazel; adding it pulls in a large `http_archive`
- The device broker infrastructure requires `--sandbox_writable_path=/dev/kvm` and access to the Android emulator binary — both break the hermetic linux-sandbox
- `target_device` descriptors for API 29 x86_64 must be sourced from `android_test_support` which is a legacy `WORKSPACE`-based repo; bzlmod compatibility is uncertain for `android_test_support`
- Emulator startup adds 3–5 minutes of non-cacheable wait time regardless of code changes

**Verdict**: Most Bazel-native option but the highest integration complexity. The bzlmod / `android_test_support` compatibility gap is a real blocker for rules_android 0.7.1 with bzlmod-only setup.

### Option 3B — `genrule` with `tags = ["local"]` + `--strategy=TestRunner=local`

**Concept**: Wrap the existing Gradle emulator test in a genrule, or use a `sh_test` with `tags = ["local", "exclusive"]` that calls the Gradle task directly.

```python
sh_test(
    name = "smoke_tests",
    srcs = ["run_smoke_tests.sh"],
    data = [":android_app"],   # ensures APK is built first
    tags = ["local", "exclusive"],  # exclusive = serialize, local = no sandbox
)
```

`run_smoke_tests.sh` would:
1. Start emulator (or assume it's already running via GHA's `android-emulator-runner`)
2. `adb install` the APK from `$BUILD_WORKSPACE_DIRECTORY`
3. `adb shell am instrument -w ...`
4. Parse exit code

**Pros**:
- No changes to existing emulator setup in CI
- `bazel test //...` includes this target (when run with `--test_tag_filters=-no-emulator`)
- Decouples emulator lifecycle from Bazel internals

**Cons**:
- Not hermetic; `tags = ["local"]` bypasses caching entirely
- Emulator must be pre-started in the CI job before `bazel test` runs
- The script must handle ADB wait-for-device, install, and test execution — duplicating logic already in Gradle

**Verdict**: Viable bridge option. Lower integration risk than 3A.

### Option 3C — GHA emulator pre-start + `bazel test` with ADB socket passthrough

**Concept**: In the GitHub Actions workflow, start the emulator as a service step (using `reactivecircus/android-emulator-runner@v2` with `script: echo started`), then run `bazel test` with sandbox writable paths for ADB:

```yaml
- name: Start emulator
  uses: reactivecircus/android-emulator-runner@v2
  with:
    api-level: 29
    arch: x86_64
    script: echo "Emulator started"

- name: Run smoke tests via Bazel
  run: |
    bazel test //androidApp:smoke_tests \
      --config=android \
      --sandbox_writable_path=/tmp/.android \
      --sandbox_writable_path=/dev/kvm \
      --test_env=ANDROID_SERIAL \
      --test_env=ANDROID_SDK_ROOT
```

The Bazel test target uses `sh_test` or a custom `android_instrumentation_test` that calls `adb` directly.

**Pros**:
- Emulator lifecycle managed by the proven GHA action (handles KVM, AVD creation, boot wait)
- `--sandbox_writable_path` gives the Bazel test access to ADB socket without full `tags=local` bypass
- More hermetic than Option 3B — Bazel still tracks inputs

**Cons**:
- `/dev/kvm` and ADB socket passthrough are `--sandbox_writable_path` hacks; Bazel cannot cache tests that write to external sockets
- The emulator is an external service from Bazel's perspective — cache invalidation cannot account for emulator state
- Requires careful CI YAML restructuring to separate emulator start from `bazel test` invocation

**Verdict**: Best balance for the emulator epic. Emulator lifecycle stays with the GHA action; Bazel gains control over test execution and reporting.

### Recommended approach for emulator smoke tests

**Phase 1 (staged rollout)**: Keep the Gradle `android-smoke` CI job as-is. The emulator job has no Bazel equivalent today.

**Phase 2**: Implement Option 3C in `bazel-ci.yml`:
1. Add a new `bazel-android-smoke` job after `bazel-android`
2. Pre-start emulator with `reactivecircus/android-emulator-runner@v2` (script: echo)
3. Add a `sh_test` target `//androidApp:smoke_tests` that calls `adb shell am instrument`
4. Run `bazel test //androidApp:smoke_tests --sandbox_writable_path=/dev/kvm --sandbox_writable_path=/tmp/.android`

**Phase 3 (if Option 3A bzlmod support improves)**: Migrate to `android_instrumentation_test` once `android_test_support` is available in BCR.

**Decision tree**:
```
Is android_test_support available in BCR for bzlmod?
  YES → Option 3A (android_instrumentation_test)
  NO  → Option 3C (sh_test + ADB socket passthrough)
        → Does --sandbox_writable_path for /dev/kvm work reliably?
            YES → Retire Gradle android-smoke job after 3 green runs
            NO  → Option 3B (genrule with tags=local) as fallback
```

**Failure modes specific to emulator**:
- **KVM not available**: tests fail non-hermetically; mitigation: `--test_tag_filters=-requires-kvm` to skip on incompatible hosts
- **ADB socket not found in sandbox**: tests timeout; mitigation: `--sandbox_writable_path` list in `.bazelrc` under `[config:android-emulator]`
- **APK version mismatch**: `sh_test` must reference the Bazel-built APK via `$(rootpath //androidApp:android_app)`, not a Gradle-built path

---

## Integration Summary

### Hook-in points for new targets

| Target | Hooks into | Notes |
|--------|-----------|-------|
| Detekt (Phase 2) | `//kmp:BUILD.bazel` | New `java_test` or `genrule` alongside existing `web_dist` |
| Screenshot tests | `//kmp/src/androidUnitTest/kotlin:BUILD.bazel` | New `android_local_test` target; existing `android_unit_tests` unchanged |
| Emulator smoke | `//androidApp:BUILD.bazel` | New `sh_test` target; no changes to existing `android_app` |
| `//...` coverage | Top-level `test_suite` in `//kmp:BUILD.bazel` | Add new targets to `jvm_tests` or create `android_tests` suite |

### Staged rollout checklist

The requirement is "Bazel runs alongside Gradle until green for 3 runs":

1. Add new Bazel targets without removing Gradle CI jobs
2. Add new `bazel-*` jobs to `bazel-ci.yml` with `continue-on-error: true` initially
3. Monitor for 3 consecutive green runs
4. Remove `continue-on-error` flag; add branch protection requirement
5. Remove corresponding Gradle CI job from `ci.yml`

### Cache invalidation summary

| Target | Cache miss trigger | Cache hit condition |
|--------|-------------------|---------------------|
| Detekt (genrule bridge) | Any `.kt` file changes (Bazel tracks via srcs glob) | No `.kt` changes since last run |
| Screenshot record | Any `.kt` source change | Identical sources |
| Screenshot verify | Any `.kt` source OR baseline PNG change | Both unchanged |
| Emulator smoke (sh_test local) | **Never cached** (`tags=["local", "exclusive"]`) | N/A — always runs |
| Emulator smoke (with sandbox passthrough) | `.kt` source + APK changes | No source changes (ADB socket not tracked) |

---

## Recommended Approach Summary

| Epic | Recommended | Fallback | Timeline |
|------|------------|---------|----------|
| **Detekt** | Keep Gradle (intentional, documented) | genrule bridge if forced into `//...` | Already decided |
| **Roborazzi** | Option 2A: extend `android_local_test`, record mode first | Option 2B: genrule bridge | Phase 1: commit baselines; Phase 2: verify mode |
| **Emulator smoke** | Option 3C: GHA emulator pre-start + `sh_test` with ADB passthrough | Option 3B: genrule with `tags=local` | After Roborazzi is green |
