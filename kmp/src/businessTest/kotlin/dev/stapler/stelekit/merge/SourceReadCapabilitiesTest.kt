package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.model.GraphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceReadCapabilitiesTest {
    private val graph = OffGraphTarget(GraphId("b"), "/graphs/b", isActive = false)
    private fun reason(c: SourceReadCapabilities, g: OffGraphTarget = graph) = c.canReadOffGraph(g).leftOrNull()

    @Test
    fun spike015IsUnrecordedSoIosAndWebDegrade() {
        assertFalse(IOS_OFF_GRAPH_READ_VERIFIED, "Spike 0.1.5 has not run; flip only with a recorded device result")
        assertFalse(WEB_OFF_GRAPH_READ_VERIFIED, "Spike 0.1.5 has not run; flip only with a recorded browser result")
        assertEquals(ReadCapabilityReason.PlatformUnsupported, reason(SourceReadCapabilities(SourcePlatform.Ios)))
        assertEquals(ReadCapabilityReason.PlatformUnsupported, reason(SourceReadCapabilities(SourcePlatform.Web)))
    }

    @Test
    fun verifiedFlagOpensOnlyItsOwnPlatform() {
        val ios = SourceReadCapabilities(SourcePlatform.Ios, iosVerified = true)
        assertTrue(ios.canReadOffGraph(graph) is Either.Right)
        assertEquals(ReadCapabilityReason.PlatformUnsupported, reason(SourceReadCapabilities(SourcePlatform.Web, iosVerified = true)))
    }

    @Test
    fun desktopAndAndroidReadByDefault() {
        assertTrue(SourceReadCapabilities(SourcePlatform.Desktop).canReadOffGraph(graph) is Either.Right)
        assertTrue(SourceReadCapabilities(SourcePlatform.Android).canReadOffGraph(graph) is Either.Right)
    }

    @Test
    fun encryptedWinsOverPlatform() {
        assertEquals(ReadCapabilityReason.Encrypted, reason(SourceReadCapabilities(SourcePlatform.Ios), graph.copy(encrypted = true)))
    }

    @Test
    fun missingGrantOffersRegrantOnlyWhenPossible() {
        val can = SourceReadCapabilities(SourcePlatform.Web, webVerified = true, hasGrant = { false }, canRegrant = true)
        val r = reason(can)
        assertEquals(ReadCapabilityReason.NoGrant(true), r)
        assertEquals(CapabilityAction.RegrantAccess, r!!.action)
        val cannot = SourceReadCapabilities(SourcePlatform.Web, webVerified = true, hasGrant = { false })
        assertEquals(CapabilityAction.OpenGraph, reason(cannot)!!.action)
    }

    @Test
    fun missingFolderAndUnreadableIoAreDistinct() {
        assertEquals(ReadCapabilityReason.FolderMissing, reason(SourceReadCapabilities(SourcePlatform.Desktop, folderExists = { false })))
        assertEquals(ReadCapabilityReason.UnreadableIo, reason(SourceReadCapabilities(SourcePlatform.Desktop, isReadable = { false })))
    }
}
