package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals

/** A consent token has exactly one mint site, and tests get theirs from the real gate. */
class GrantedConsentMintSiteAuditTest {
    // Built from parts so this file does not contain the needle it searches for.
    private val constructorCall = Regex("\\b" + "Granted" + "Consent\\(")
    private val issueCall = Regex("\\b" + "Granted" + "Consent\\.issue\\(")

    @Test
    fun grantedConsent_should_BeMintedOnlyInCloudConsentGate_When_ProductionSourcesAudited() {
        val constructing = WiringInputs.productionSources.filterValues { constructorCall.containsMatchIn(it) }.keys.map { it.substringAfterLast('/') }
        val issuing = WiringInputs.productionSources.filterValues { issueCall.containsMatchIn(it) }.keys.map { it.substringAfterLast('/') }
        val inTests = WiringInputs.testSources.filterValues { constructorCall.containsMatchIn(it) || issueCall.containsMatchIn(it) }.keys

        assertEquals(listOf("GrantedConsent.kt"), constructing, "only the declaration's own companion may call the constructor")
        assertEquals(listOf("CloudConsentGate.kt"), issuing, "only the gate may ask for a token to be issued")
        assertEquals(emptySet(), inTests, "no test-only mint path: tests run the real gate over fake inputs")
    }
}
