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
    "dev.stapler.stelekit.capture.CaptureSocketClientTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CaptureSocketListenerTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CaptureWriterSourceAuditTest", // fails under Bazel
    "dev.stapler.stelekit.desktop.MainCaptureFlowTest", // fails under Bazel
    "dev.stapler.stelekit.desktop.MainCliCaptureIntegrationTest", // fails under Bazel
    "dev.stapler.stelekit.docs.WebsiteDocsCoverageTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ListBacklinksToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ListGraphsToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpAdapterTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpClientIdentityTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpEndToEndReadTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpLauncherMainTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpRealSubprocessSmokeTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpSocketServerTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpToolTimeoutTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.PageResourceTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ProposeBlockEditToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ProposeNewPageToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ReadPageToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.SearchGraphToolTest", // fails under Bazel
    "dev.stapler.stelekit.transfer.AirGapGuardTest", // fails under Bazel
    "dev.stapler.stelekit.git.PatLeakageAuditTest", // reads wasmJsMain sources; not staged in the Bazel sandbox
)
