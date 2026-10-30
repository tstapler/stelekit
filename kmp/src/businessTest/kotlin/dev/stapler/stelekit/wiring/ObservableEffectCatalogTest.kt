package dev.stapler.stelekit.wiring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Task 1.1d: the `Seam  EntryPointTest  effect:<label>` contract. */
class ObservableEffectCatalogTest {
    private val catalog = "AssetPipelinePlugin  AssetStackEntryPointTest  effect:asset-index-rows"

    private fun entryPoint(body: String) = mapOf("androidUnitTest/AssetStackEntryPointTest.kt" to body)

    private val good = """
        class AssetStackEntryPointTest {
            @Test fun t() {
                val stack = WiredAppFixture.android().compose { rememberGraphContentAssetStack() } // GraphContentAssetSetup
                ObservableEffect.assert("asset-index-rows") {
                    assertTrue(stack.db.countAssetIndexRows() > 0)
                }
            }
        }
    """.trimIndent()

    @Test
    fun catalog_should_Pass_When_EntryPointTestCallsObservableEffectAssertWithMatchingLabelAfterRealRootFactory() {
        assertEquals(emptyList(), WiringCatalog.check(catalog, entryPoint(good)))
    }

    @Test
    fun catalog_should_FailNamingLine_When_EffectLabelMissingOrEntryPointTestNeverCallsObservableEffectAssert() {
        val noLabel = WiringCatalog.check("AssetPipelinePlugin  AssetStackEntryPointTest", entryPoint(good))
        assertTrue(noLabel.single().contains("without effect:<label>"), noLabel.toString())
        val neverCalls = WiringCatalog.check(catalog, entryPoint(good.replace("ObservableEffect.assert", "somethingElse")))
        assertTrue(neverCalls.any { "never calls ObservableEffect.assert(\"asset-index-rows\")" in it }, neverCalls.toString())
        val wrongLabel = WiringCatalog.check(catalog, entryPoint(good.replace("\"asset-index-rows\"", "\"other\"")))
        assertTrue(wrongLabel.any { "never calls ObservableEffect.assert(\"asset-index-rows\")" in it }, wrongLabel.toString())
        val classOnly = WiringCatalog.check(
            catalog,
            entryPoint(good.replace("assertTrue(stack.db.countAssetIndexRows() > 0)", "assertNotNull(GraphContentAssetSetup::class)")),
        )
        assertTrue(classOnly.any { "must assert runtime state" in it }, classOnly.toString())
        val unknownSeam = WiringCatalog.check("Plugin  AssetStackEntryPointTest  effect:x", entryPoint(good))
        assertTrue(unknownSeam.any { "unknown seam" in it }, unknownSeam.toString())
    }

    @Test
    fun catalog_should_Fail_When_AssertionPrecedesTheRealRootFactoryCall() {
        val reordered = """
            class AssetStackEntryPointTest {
                @Test fun t() {
                    ObservableEffect.assert("asset-index-rows") { assertTrue(rows() > 0) }
                    WiredAppFixture.android().compose { rememberGraphContentAssetStack() }
                }
            }
        """.trimIndent()
        val v = WiringCatalog.check(catalog, entryPoint(reordered))
        assertTrue(v.any { "before it invokes the real root factory" in it }, v.toString())
    }

    @Test
    fun observableEffect_should_NameTheLabel_When_TheBodyAsserts_And_Fails() {
        val e = assertFailsWith<AssertionError> { ObservableEffect.assert("sync-tick-logged") { assertTrue(false, "no tick") } }
        assertTrue("sync-tick-logged" in e.message.orEmpty(), e.message)
        ObservableEffect.assert("fine") { assertTrue(true) }
    }
}
