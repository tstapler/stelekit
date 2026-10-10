# Adversarial Review: bazel-full-ci-migration

**Date**: 2026-06-24
**Reviewer**: Adversarial architecture review (post-patch pass 2)
**Verdict**: CONDITIONAL — 1 blocker, 6 concerns, 5 minors

All three original blockers are fixed. One new blocker has been found. The plan is
substantially more solid than the prior version, but several concerns remain that
will cause painful CI surprises if skipped.

---

## Blockers

### BLOCKER-1: `bazel-detekt` CI job has no `actions/setup-java` step — Bazel's JVM toolchain resolution will fail

The `bazel-detekt` job snippet (Task 1.4.1a) calls `bazel test //kmp:detekt` but
includes no `actions/setup-java@v4` step. Every other Bazel job in `bazel-ci.yml`
that touches JVM targets (`bazel-jvm`, `bazel-android`, `bazel-android-tests`,
`bazel-web`) sets up Java 21 first. Bazel resolves the `kt_jvm_library` in
`//buildSrc:detekt_rules` and the `detekt_test` target against the JVM toolchain
declared with `--java_runtime_version=21` in `.bazelrc`. On a fresh Ubuntu GHA
runner, the default JDK version varies (currently Java 17 or 21 depending on the
image version) and is not guaranteed to satisfy Bazel's `--tool_java_runtime_version=21`
requirement. The job will fail intermittently or consistently during toolchain
resolution before any Detekt analysis runs.

**Fix**: Add `actions/setup-java@v4` with `java-version: "21"` and
`distribution: "temurin"` to the `bazel-detekt` job steps, before the Bazel step,
matching the pattern in every other Bazel job.

---

## Concerns

### CONCERN-1: `kt_jvm_library` in `buildSrc/BUILD.bazel` does not have a `neverlink` attribute — the plan's neverlink approach requires a `java_library` wrapper, but the BUILD snippet still shows `detekt-api` referenced directly in `kt_jvm_library.deps`

The plan correctly identifies the need for a `java_library(neverlink=True)` wrapper
(`detekt_api_neverlink`) and documents this as a note inside the `kt_jvm_library`
block. However, the final snippet shown for Task 1.2.1a still puts
`@maven//:io_gitlab_arturbosch_detekt_detekt_api` directly in the `kt_jvm_library`
`deps` (inside the comment, while the actual deps list below the comment is
incomplete/ambiguous). If an implementer copies the snippet verbatim, the
`detekt-api` transitive JARs (including `kotlin-compiler-embeddable`) will appear
on the runtime classpath of `detekt_test`, causing `ClassCastException` when the
`RuleSetProvider` SPI loader finds two copies of `detekt-api` types. The fix must
be `//buildSrc:detekt_api_neverlink` in the `kt_jvm_library` deps, not the Maven
target directly.

**Fix**: Make the `buildSrc/BUILD.bazel` snippet unambiguous — show the complete
file with `java_library(name = "detekt_api_neverlink", ...)` first, then
`kt_jvm_library(... deps = ["//buildSrc:detekt_api_neverlink"])`. Remove the
in-comment alternative that mentions the Maven target directly.

### CONCERN-2: `bazel-roborazzi` CI job uses `bazel run` with `--config=remote-cache` but the `record_android_screenshots` `sh_binary` is tagged `local` — remote cache upload will be attempted for a non-hermetic target and silently skipped or fail

`tags = ["local"]` on a `sh_binary` makes the target non-cacheable in Bazel's
action cache, but it does not prevent `--config=remote-cache` from being passed.
The `bazel run` invocation will try to upload the runfiles to the remote cache
proxy (bazel-cache-proxy), hit a `UNKNOWN_STATUS` or cache-miss for the
non-hermetic action, and log warnings. This is not a hard failure but will produce
confusing log output on every CI run, and if bazel-cache-proxy has strict mode
enabled, it may fail the health check. More critically, the `bazel-roborazzi` job
does not include the `GRADLE_ENCRYPTION_KEY` secret for `gradle/actions/setup-gradle`,
but the plan passes `cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}`.
If that secret is not accessible in the `bazel-ci.yml` workflow (it is currently
only in `ci.yml`), the Gradle home cache will silently skip encryption and fall back
to an unencrypted cache, potentially leaking build credentials cached in the Gradle
home directory.

**Fix**: (a) Drop `--config=remote-cache` from the `bazel run //kmp:record_android_screenshots`
invocation — it has no cache effect for `tags=["local"]` targets and clutters logs.
(b) Verify `GRADLE_ENCRYPTION_KEY` is accessible in `bazel-ci.yml` (add to repo
secrets if not already there) or remove the `cache-encryption-key` line and accept
unencrypted Gradle home cache for this job.

### CONCERN-3: `verify_android_screenshots` `sh_test` uses `$BUILD_WORKSPACE_DIRECTORY` but this variable is set by `bazel run`, not by `bazel test` — tests using it will get an empty variable and `gradlew` invocation will fail

The plan's `verify_android_screenshots.sh` script uses `${BUILD_WORKSPACE_DIRECTORY}/gradlew`.
`$BUILD_WORKSPACE_DIRECTORY` is populated by the Bazel client for `bazel run`
invocations. For `bazel test`, the variable is NOT automatically set by Bazel.
The `tags = ["local"]` attribute disables the sandbox, so the test runs in the
workspace, but `$BUILD_WORKSPACE_DIRECTORY` will be empty, causing
`${BUILD_WORKSPACE_DIRECTORY}/gradlew` to expand to `/gradlew` and fail with "no
such file."

The `record_android_screenshots.sh` and `record_jvm_screenshots.sh` wrappers have
the same issue if they are ever run via `bazel test` (e.g., during wildcard expansion
of `//kmp:all` — `sh_binary` is excluded from test but `sh_test` is not).

**Fix**: In `verify_android_screenshots.sh`, fall back to `$PWD` or use a relative
path when `$BUILD_WORKSPACE_DIRECTORY` is empty:
```bash
WORKSPACE="${BUILD_WORKSPACE_DIRECTORY:-$PWD}"
exec "${WORKSPACE}/gradlew" ...
```
Or use `$(cd "$(dirname "${BASH_SOURCE[0]}")" && git rev-parse --show-toplevel)`
as a fallback only — confirm that `tags=["local"]` runs from the workspace root
so `$PWD` is reliable.

### CONCERN-4: The `bazel-roborazzi` CI job invokes `bazel run //kmp:record_android_screenshots` but no `setup-android` step sets `ANDROID_HOME`, yet the Gradle wrapper inside the script depends on it

The `bazel-roborazzi` job includes `android-actions/setup-android@v3` (line in
plan's Task 2.2.1a). This should set `ANDROID_HOME`. However, the shell wrapper
script will inherit the environment from the GHA runner, and `$BUILD_WORKSPACE_DIRECTORY`
is not set for `bazel run` — wait, this IS set for `bazel run`. The real risk here
is that `./gradlew :kmp:recordRoborazziDebug` inside the shell script will look for
`local.properties` to find the Android SDK. GHA runners don't have `local.properties`;
the Gradle build relies on the `ANDROID_HOME` environment variable being set. While
`android-actions/setup-android@v3` sets `ANDROID_HOME` in the GHA environment,
`bazel run` executes shell scripts with a restricted environment (controlled by
`--action_env`). If `ANDROID_HOME` is not explicitly propagated, Gradle will fail
with "SDK location not found."

**Fix**: Add `--test_env=ANDROID_HOME` (or `--action_env=ANDROID_HOME`) to the
`bazel run //kmp:record_android_screenshots` invocation, or set
`build --repo_env=ANDROID_HOME` in `.bazelrc` (already present) and confirm it is
inherited by `bazel run` child processes. Alternatively, pass it explicitly in the
shell script: `ANDROID_HOME="${ANDROID_HOME}" ./gradlew ...`.

### CONCERN-5: Unresolved Question #1 (detekt-api version skew) is marked as "blocks Story 1.2.2" but is not gated in the execution plan — a wrong answer requires reverting MODULE.bazel and buildSrc/BUILD.bazel changes already merged

The execution order table shows Epic 1.2 (buildSrc/BUILD.bazel) depends on Epic 1.1
(MODULE.bazel deps). Both can be opened as back-to-back PRs. However, if the
`detekt-api:1.23.7` + `rules_detekt:0.8.1.13` combination causes a classpath
conflict at Story 1.2.2 (discovered only after building the JAR), the implementer
must revert Epic 1.1's MODULE.bazel changes — there is no intermediate checkpoint.

The plan text acknowledges this in the unresolved questions but does not add a
pre-merge gate. The risk is amplified by the fact that `rules_detekt 0.8.1.13`
ships Detekt `1.23.8`, while `buildSrc/build.gradle.kts` pins `detekt-api:1.23.7`
(one patch behind). If the custom rules JAR is compiled against `1.23.7` API and
loaded by `1.23.8`'s SPI loader, Detekt's internal version check may reject it
or produce `AbstractMethodError`.

**Fix**: Resolve Unresolved Question #1 locally (`mvn dependency:tree` on the
`io.gitlab.arturbosch.detekt:detekt-api:1.23.7` artifact against Detekt 1.23.8
classpath; build `//buildSrc:detekt_rules` locally; run `bazel test //kmp:detekt`
on a clean checkout) before merging Epic 1.1. Document the result in the Epic 1.1
PR description.

### CONCERN-6: `record_jvm_screenshots` `sh_binary` in the plan is tagged `["local", "manual"]` but the JVM Roborazzi screenshot tests write PNGs to `kmp/build/outputs/roborazzi/` — this path is under Bazel's output directory (`kmp/build/` is Gradle build output, not Bazel output), which means the written PNGs will not be visible to `git status` as new files without knowing the exact output path

The plan's acceptance criterion for Story 2.1.1 says "PNG files appear under
`kmp/build/outputs/roborazzi/`." This is correct for Gradle — Roborazzi JVM tests
write to the relative path supplied in `captureRoboImage("build/outputs/roborazzi/...")`.
Since Gradle runs from the workspace root (via `$BUILD_WORKSPACE_DIRECTORY`), this
resolves to `$WORKSPACE/kmp/build/outputs/roborazzi/` — not committed to git. The
baselines are therefore never in the repo and cannot be compared between runs.

Contrast with the Android Roborazzi tests: they write to
`kmp/src/androidUnitTest/snapshots/images/` (source tree), which IS committed. The
JVM screenshots have no committed baseline path and the plan does not address how
JVM screenshot regressions would be detected (there is no `verify_jvm_screenshots`
target, no JVM baseline commitment story). This is a missing coverage gap: JVM
screenshot tests exist (confirmed in `BottomNavScreenshotTest.kt`,
`TableBlockScreenshotTest.kt`) but the plan only wraps record mode and provides no
verification path.

**Fix**: Either (a) document explicitly that JVM screenshot verification is out of
scope for this migration (acceptable — current ci.yml also only records and uploads,
no verification), or (b) add a `verify_jvm_screenshots` story parallel to the Android
one, noting that JVM baselines would need to be committed to source tree with a
standardized path. At minimum, update Story 2.1.1's acceptance criterion to state
"verification is explicitly deferred — this target is record-only" so the missing
verify path is a documented decision, not an oversight.

---

## Minors

### MINOR-1: `bazel-detekt` job uses `disk-cache: detekt` but Detekt's analysis artifacts are JARs/class files already covered by `disk-cache: jvm` on `bazel-jvm` — a separate `detekt` disk-cache key buys nothing on re-use and doubles cache storage

The `bazel-jvm` job's `disk-cache: jvm` caches compiled `//buildSrc:detekt_rules`
and its deps (since they are Kotlin JARs). A separate `disk-cache: detekt` key for
the `bazel-detekt` job means these artifacts are cached twice under different keys
with no cross-job sharing. Using `disk-cache: jvm` in `bazel-detekt` as well would
let it warm up from the `bazel-jvm` job's cache write. This is a GHA cache
efficiency issue, not a correctness issue.

### MINOR-2: The `bazel-detekt` job has `if: github.event.pull_request.draft == false` but `bazel-roborazzi` has `if: github.event_name == 'push' && github.ref == 'refs/heads/main'` — on `push` to main, `bazel-detekt` will also run (not just PRs), which means Detekt runs twice on every main push (once via Gradle `lint`, once via Bazel)

This is consistent with the staged rollout intent (`continue-on-error: true`), but
it doubles CI costs during the overlap period. The double-run is bounded to the
graduation window and is acceptable, but should be documented as a known transient
cost rather than leaving it implied.

### MINOR-3: The plan's Task 1.4.1b smoke-check uses `println("test")` as a synthetic violation, which triggers `ForbiddenMethodCall` in `detekt.yml`. However, `ForbiddenMethodCall` is a core Detekt rule, not a custom `stelekit:` rule. The smoke-check as described does not verify that the 15 custom SteleKit rules are loaded and firing — it only verifies that Detekt itself runs. A rule-specific smoke-check (e.g., adding an `@InMemory` annotation without a `@DirectSqlWrite` pair, or calling `actor.execute` without `withContext`) would verify custom rule loading

This is low impact because Task 1.4.1b is already a manual verification step, but
using a violation from the `stelekit:` rule set directly would be strictly more
confident. Note: 15 custom rules are registered in `SteleKitRuleSetProvider.kt`,
not 14 as stated in the Domain Glossary (15 rule classes + 1 provider = 16 files,
15 rules). The "14 custom Detekt rules" count is wrong throughout the plan.

### MINOR-4: Story 2.2.1 adds `continue-on-error: true` to `bazel-roborazzi` but graduation (Story 2.3.1) requires removing it — there is no explicit reminder in the `bazel-roborazzi` job comment that `continue-on-error` must be removed at graduation, only in Story 2.3.1's acceptance criteria

When engineers search for "continue-on-error" in the workflow file after the
Roborazzi graduation, they will find the existing detekt job (already graduated)
and the roborazzi job. A comment on the roborazzi job's `continue-on-error: true`
line should reference the graduation story, as the detekt job's comment already does
("Remove after 3 consecutive green runs; then remove Gradle android Roborazzi step").

### MINOR-5: The `buildSrc/BUILD.bazel` `kt_jvm_library` glob path `src/main/kotlin/dev/stapler/detekt/**/*.kt` will pick up 16 files (15 rules + `SteleKitRuleSetProvider.kt`), which is correct — but the services file at `buildSrc/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` must be listed in `kt_jvm_library.resources` for it to be included in the JAR. `kt_jvm_library` does not auto-discover resource directories; without an explicit `resources` attribute, the services file will be silently omitted and Detekt will not discover `SteleKitRuleSetProvider`, causing all 15 custom rules to be silently skipped

Story 1.2.2 addresses this ("if the service file is not auto-generated") but frames
it as conditional on whether `@AutoService` is present. Since `SteleKitRuleSetProvider.kt`
has no `@AutoService` annotation (confirmed by reading the file) and the services
file exists manually at
`buildSrc/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`,
this is unconditionally required — not conditional. The Task 1.2.1a snippet must
include `resources = glob(["src/main/resources/META-INF/services/*"])` (or the
explicit path) in the `kt_jvm_library`. This is currently in Story 1.2.2's task but
not in the `buildSrc/BUILD.bazel` snippet in Story 1.2.1. An implementer following
the stories in order who applies Story 1.2.1's snippet verbatim will produce a JAR
without the services file, and `bazel build //buildSrc:detekt_rules` will succeed
(compilation succeeds) while `bazel test //kmp:detekt` silently skips all 15 custom
rules. This is a correctness trap masked behind a green build.

**Upgrade from MINOR to CONCERN if the smoke-check in Task 1.4.1b is skipped.**

---

## Carry-Forward from Prior Review (all resolved)

- `verify_android_screenshots` changed to `sh_test`: FIXED
- SM#4 emulator scope contradiction: FIXED (ADR-010 + plan callout)
- `actions/setup-android@v3` namespace: FIXED
- `detekt-api` neverlink semantics: ADDRESSED (wrapper specified)
- `jvmCommonMain` scope drift: FIXED (excluded)
- `git rev-parse` in `bazel run` scripts: FIXED (`$BUILD_WORKSPACE_DIRECTORY`)
- `--test_output=errors` global scope: FIXED (moved to `test:ci`)
- `checks: write` at workflow level: FIXED (moved to job level)
- `report_paths` glob scope: FIXED (scoped to `bazel-testlogs/kmp/detekt/test.xml`)
- Roborazzi graduation story: FIXED (Story 2.3.1 added)
- Custom-rules smoke-check: FIXED (Task 1.4.1b added)
- `sh_test` JUnit XML caveat: NOTED (plan acknowledges no auto-XML)
