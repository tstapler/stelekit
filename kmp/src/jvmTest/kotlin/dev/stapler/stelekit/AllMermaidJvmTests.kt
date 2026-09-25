package dev.stapler.stelekit

import dev.stapler.stelekit.ui.components.MermaidEngineActorTest
import dev.stapler.stelekit.ui.components.MermaidJvmEngineTest
import dev.stapler.stelekit.ui.components.MermaidRendererFallbackTest
import dev.stapler.stelekit.ui.components.MermaidRendererSmokeTest
import dev.stapler.stelekit.ui.components.MermaidSecurityDirectiveTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Mermaid engine test suite — the GraalJS-backed tests split out of [AllJvmTests] so the
 * mermaid Bazel target (`//kmp/src/jvmTest/kotlin:mermaid_jvm_tests`) rebuilds and reruns
 * only when mermaid sources change, not on every app change. Only engine tests (zero
 * app-code imports) live here; the `MermaidBlock*` Compose UI tests stay in [AllJvmTests]
 * because they depend on `ui.theme` from the monolith.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    MermaidEngineActorTest::class,
    MermaidJvmEngineTest::class,
    MermaidRendererFallbackTest::class,
    MermaidRendererSmokeTest::class,
    MermaidSecurityDirectiveTest::class,
)
class AllMermaidJvmTests
