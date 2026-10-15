# STACK research: tests for editor.commands

## 1. Source set and Bazel/Gradle registration
- Gradle: `businessTest` is a custom source set `dependsOn(commonTest)` and `jvmTest.dependsOn(businessTest)` (kmp/build.gradle.kts:429-433). Anything in commonTest or businessTest is auto-discovered by `./gradlew jvmTest` (JUnit discovery, no suite list).
- Bazel `//kmp:business_tests` (kmp/BUILD.bazel:55) -> `kt_jvm_test` in kmp/src/businessTest/kotlin/BUILD.bazel with `srcs = glob(["**/*.kt"])` (businessTest only) and `test_class = dev.stapler.stelekit.AllBusinessTests`: a JUnit @Suite that runs ONLY listed classes (AllBusinessTests.kt:1-30, imports from line ~33). A new class must be added to that import list + SuiteClasses; `AllBusinessTestsCompletenessTest` fails the suite otherwise.
- commonTest is NOT globbed by Bazel. commonTest files reach `business_tests` only if listed explicitly in `business_common_tests` srcs (kmp/src/commonTest/kotlin/BUILD.bazel:98-120, e.g. `ui/PageSelectionTest.kt`, `error/DomainErrorTest.kt`) and also imported into AllBusinessTests.
- Recommendation: put `CommandManagerTest` etc. in `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/` (sibling of existing `editor/LinkInsertionTest.kt`, which is imported in AllBusinessTests.kt). Then: glob picks it up, add one import + suite entry, runs under Bazel and Gradle jvmTest. (Only use commonTest if wasm/iOS coverage is wanted; then must also edit commonTest BUILD.bazel srcs.)
- Note commonMain code only depends on Logger + NotificationType, so it is platform-agnostic; businessTest compiles against jvm_main_lib (associates) fine.

## 2. Libraries available
- commonTest deps (kmp/build.gradle.kts:151-164): kotlin("test"), kotlinx-coroutines-test 1.10.2, serialization-json, okio-fakefilesystem, kotest-assertions-core 6.2.4, kotest-property 6.2.4. businessTest inherits.
- Bazel business_tests deps: kotlin_test_junit, kotlinx_coroutines_test_jvm, io_kotest_kotest_property_jvm, okio fakefilesystem (businessTest/BUILD.bazel deps list). NOTE: `kotest-assertions-core` is NOT in the Bazel deps list -> use kotlin.test asserts only (assertEquals/assertTrue), or add the dep.
- Pattern to mirror: kmp/src/commonTest/kotlin/dev/stapler/stelekit/coroutines/GraphScopedSessionIdempotencyTest.kt (imports `kotlinx.coroutines.test.runTest`, `kotlin.test.Test/assertEquals/assertTrue`; `fun \`name\`() = runTest { }`).

## 3. JaCoCo
- Defined: kmp/build.gradle.kts:1517 `jacoco { toolVersion = "0.8.12" }`, :1521 `tasks.register<JacocoReport>("jacocoTestReport")` dependsOn jvmTest; reads `build/jacoco/jvmTest.exec`; classDirs `build/classes/kotlin/jvm/main`.
- Command: `scripts/jvm-display-check.sh -- ./gradlew :kmp:jacocoTestReport` (runs whole jvmTest incl. Compose UI tests -> needs X11/Xvfb per CLAUDE.md). Faster headless: `./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.commands.*' :kmp:jacocoTestReport` (jacocoTestReport still dependsOn jvmTest, so pass --tests on the same invocation; the filter applies to the jvmTest task; UI tests excluded by the filter so display not strictly needed - UNVERIFIED, not run).
- XML: `kmp/build/reports/jacoco/jacoco.xml`; HTML `kmp/build/reports/jacoco/html`. Branch coverage: in the XML find `<package name="dev/stapler/stelekit/editor/commands">` and its `<counter type="BRANCH" missed= covered=>`. No existing build/reports/jacoco dir in this worktree (not yet run).
- Bazel has no JaCoCo (comment at build.gradle.kts ~1555 "canonical suite runs under Bazel (no JaCoCo)").

## 4. Deterministic init
- CommandManager(scope, notificationManager: ((String, NotificationType, Long?) -> Unit)? = null); `init { scope.launch { registry.registerAll(EssentialCommands.getAll()) ... } }` (CommandManager.kt:19-34). Constructor does not take a dispatcher; it launches on the passed scope.
- Pattern: `runTest { val mgr = CommandManager(this /* TestScope */, notif); advanceUntilIdle(); ... }`. With runTest's StandardTestDispatcher the launch is queued and only runs at advanceUntilIdle()/yield; with `CoroutineScope(UnconfinedTestDispatcher(testScheduler))` or `backgroundScope` it runs eagerly in the constructor. Passing `this` (TestScope) is fine since the launched coroutine completes before runTest ends. Do not leave long-lived collectors on `this`; use `backgroundScope` if any.
- Companion factory at CommandManager.kt:~99 takes a scope too.
- isAvailable lives on EditorCommand (CommandTypes.kt:115, used at :128), and CommandRegistry.kt:102 / CommandSystem.kt:77 filter on it -> pure, testable without coroutines.
