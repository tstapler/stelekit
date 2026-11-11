package dev.stapler.stelekit.merge

import kotlin.test.Test
import kotlin.test.assertTrue

class OwnedRefusalMappingTest {
    @Test
    fun everyNewRefusalHasAUserFacingMessageNamingThePage() {
        listOf(
            WriteRefusedReason.NotOwned("Digest"),
            WriteRefusedReason.EditedByUser("Digest"),
            WriteRefusedReason.Conflicted("Digest"),
        ).forEach { assertTrue("Digest" in it.message, it.message) }
    }
}
