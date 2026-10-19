package dev.stapler.stelekit

/**
 * jvmTest classes that predate [AllJvmTestsCompletenessTest] and are not in [AllJvmTests], so
 * `bazel test //kmp:jvm_tests` never runs them. Gradle runs them. Register a class in
 * [AllJvmTests] and delete its entry here; the completeness test fails if an entry is stale.
 * Do not add entries: register new test classes instead.
 */
internal val UNREGISTERED_JVM_TESTS: Set<String> = setOf(
    "dev.stapler.stelekit.ui.StelekitViewModelShareExportTest", // fails under Bazel: onDone never fires within 5s
    "dev.stapler.stelekit.capture.CapturePopupWindowTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CapturePopupWindowUxTest", // fails under Bazel
)
