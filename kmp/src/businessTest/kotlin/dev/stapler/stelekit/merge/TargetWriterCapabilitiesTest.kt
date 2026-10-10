package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetWriterCapabilitiesTest {
    private val inactive = OffGraphTarget(GraphId("b"), "/graphs/b", isActive = false)
    private val saf = inactive.copy(storage = StorageLocation.SafFolder("b", "content://tree"))
    private val desktop = TargetWriterCapabilities(platformSupportsOffGraphWrite = true)

    private fun reason(c: TargetWriterCapabilities, t: OffGraphTarget) = c.canWriteOffGraph(t).leftOrNull()

    @Test
    fun activeGraphIsAlwaysWritableEvenWhenEncryptedOrUnsupported() {
        val active = inactive.copy(isActive = true, encrypted = true)
        assertTrue(TargetWriterCapabilities().canWriteOffGraph(active) is Either.Right)
    }

    @Test
    fun encryptedInactiveGraphIsRefused() {
        assertEquals(WriteCapabilityReason.Encrypted, reason(desktop, inactive.copy(encrypted = true)))
    }

    @Test
    fun safWithoutVerifiedGrantIsNoGrantWithRegrantActionWhenSupported() {
        val withRegrant = TargetWriterCapabilities(platformSupportsOffGraphWrite = true, canRegrantSaf = true)
        val r = reason(withRegrant, saf)
        assertEquals(WriteCapabilityReason.NoGrant(canRegrant = true), r)
        assertEquals(CapabilityAction.RegrantAccess, r!!.action)
        assertEquals("Re-grant access", r.action!!.label)
    }

    @Test
    fun noGrantFallsBackToOpenGraphActionWhenRegrantIsUnavailable() {
        assertEquals(CapabilityAction.OpenGraph, reason(desktop, saf)!!.action)
    }

    @Test
    fun grantedSafIsSafInboxOnlyUntilAtomicReplaceIsVerified() {
        val granted = TargetWriterCapabilities(platformSupportsOffGraphWrite = true, hasVerifiedSafGrant = { true })
        assertEquals(WriteCapabilityReason.SafInboxOnly, reason(granted, saf))
        assertFalse(SAF_ATOMIC_REPLACE_VERIFIED, "Spike 0.1.2 has not run; flip only with a recorded device result")
    }

    @Test
    fun grantedSafIsWritableOnceAtomicReplaceIsVerified() {
        val verified = TargetWriterCapabilities(
            platformSupportsOffGraphWrite = true, hasVerifiedSafGrant = { true }, safAtomicReplaceVerified = true,
        )
        assertTrue(verified.canWriteOffGraph(saf) is Either.Right)
    }

    @Test
    fun safPathWithoutStorageLocationIsTreatedAsNoGrant() {
        val unknown = inactive.copy(path = "saf://tree/b")
        assertEquals(WriteCapabilityReason.NoGrant(canRegrant = false), reason(desktop, unknown))
    }

    @Test
    fun platformThatCannotAddressInactivePathsIsUnsupportedByDefault() {
        assertEquals(WriteCapabilityReason.PlatformUnsupported, reason(TargetWriterCapabilities(), inactive))
        val host = inactive.copy(storage = StorageLocation.HostFolder("b", "notes"))
        assertEquals(WriteCapabilityReason.PlatformUnsupported, reason(desktop, host))
    }

    @Test
    fun plainDesktopFolderIsWritable() {
        assertTrue(desktop.canWriteOffGraph(inactive) is Either.Right)
        val direct = inactive.copy(storage = StorageLocation.DirectAccessFolder("b", "/sdcard/b"))
        assertTrue(desktop.canWriteOffGraph(direct) is Either.Right)
    }

    @Test
    fun everyReasonHasUserTextAndAnAction() {
        listOf(
            WriteCapabilityReason.Encrypted, WriteCapabilityReason.NoGrant(), WriteCapabilityReason.SafInboxOnly,
            WriteCapabilityReason.PlatformUnsupported,
        ).forEach {
            assertTrue(it.userText.isNotBlank())
            assertTrue(it.action != null)
        }
    }
}
