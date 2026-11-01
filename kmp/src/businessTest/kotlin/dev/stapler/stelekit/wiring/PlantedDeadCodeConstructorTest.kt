package dev.stapler.stelekit.wiring

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Task 1.1e: proves the second ADR-002 layer. A seam implementation constructed only inside a
 * never-called function of a test-only root copy passes the text-match gate (its stated limit) but
 * its effect never happens through the real root factory, so the catalog's `ObservableEffect`
 * assertion fails. Runs under Gradle and `bazel test //kmp:business_tests`.
 */
class PlantedDeadCodeConstructorTest {
    /** Mirror of the planted seam implementation: counts constructions at runtime. */
    private class PlantedDeadPlugin {
        init { constructed++ }

        companion object { var constructed = 0 }
    }

    /** Test-only root copy: its real factory never calls [neverCalled]. */
    private object PlantedRoot {
        fun composeStack(): String = "stack composed"

        @Suppress("unused")
        fun neverCalled() { PlantedDeadPlugin() }
    }

    private val rootText = """
        object PlantedRoot {
            fun composeStack() = "stack composed"
            fun neverCalled() { PlantedDeadPlugin() }
        }
    """.trimIndent()

    private val sources = mapOf("commonMain/a/PlantedDeadPlugin.kt" to "class PlantedDeadPlugin : AssetPipelinePlugin {\n}\n")

    @Test
    fun plantedDeadCodeConstructor_should_FailCatalogEffectCheck_When_SeamImplConstructedOnlyInUnreachableFunctionOfTestOnlyRootCopy() {
        // Layer 1 (text match) passes: the limit stated in WiringCompletenessTest's KDoc.
        val layerOne = WiringAudit.evaluate(sources, mapOf("ui/GraphContentPlantedSetup.kt" to rootText), "", LocalDate.now())
        assertEquals(emptyList(), layerOne, "the text-match gate cannot see dead code")

        // Layer 2: the entry-point shape is fine statically, but running it shows the effect never happens.
        PlantedDeadPlugin.constructed = 0
        PlantedRoot.composeStack()
        val e = assertFailsWith<AssertionError> {
            ObservableEffect.assert("planted-plugin-constructed") { assertTrue(PlantedDeadPlugin.constructed > 0, "constructed=${PlantedDeadPlugin.constructed}") }
        }
        assertTrue("planted-plugin-constructed" in e.message.orEmpty(), e.message)
    }

    @Test
    fun plantedLiveConstructor_should_PassCatalogEffectCheck_When_RootFactoryReallyConstructsIt() {
        PlantedDeadPlugin.constructed = 0
        PlantedDeadPlugin()
        ObservableEffect.assert("planted-plugin-constructed") { assertTrue(PlantedDeadPlugin.constructed > 0) }
    }
}
