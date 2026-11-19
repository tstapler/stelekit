package dev.stapler.stelekit.platform

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpfsProbeDirectoryTest {
    private fun uuidAt(millis: Long): String {
        val hex = millis.toString(16).padStart(12, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8)}-7000-8000-000000000000"
    }

    private val now = 1_800_000_000_000L

    @Test
    fun name_should_BePrefixPlusTheUuid() {
        assertEquals("stelekit-probe-abc", OpfsProbeDirectory.nameFor("abc"))
    }

    @Test
    fun isStale_should_BeTrueOnlyPastOneDay() {
        val oneDay = OpfsProbeDirectory.STALE_AFTER_MS
        assertFalse(OpfsProbeDirectory.isStale(OpfsProbeDirectory.nameFor(uuidAt(now - oneDay)), now), "exactly one day is not stale")
        assertTrue(OpfsProbeDirectory.isStale(OpfsProbeDirectory.nameFor(uuidAt(now - oneDay - 1)), now))
        assertFalse(OpfsProbeDirectory.isStale(OpfsProbeDirectory.nameFor(uuidAt(now)), now))
    }

    @Test
    fun isStale_should_NeverSelectAnUnparsableOrForeignName() {
        assertFalse(OpfsProbeDirectory.isStale("stelekit-probe-not-a-uuid", now))
        assertFalse(OpfsProbeDirectory.isStale("my-notes", now))
        assertFalse(OpfsProbeDirectory.isStale(uuidAt(0), now), "no prefix: not a probe directory")
        assertFalse(OpfsProbeDirectory.isStale(OpfsProbeDirectory.PREFIX + uuidAt(0).replace("-7000-", "-4000-"), now), "a v4 id has no stamp")
    }

    @Test
    fun quotaExceeded_should_MapToTheTypedError_AndEveryOtherNameToWriteFailed() {
        assertIs<DomainError.FileSystemError.StorageQuotaExceeded>(OpfsErrors.write("/p", "QuotaExceededError").leftOrNull())
        val other = assertIs<DomainError.FileSystemError.WriteFailed>(OpfsErrors.write("/p", "NotAllowedError").leftOrNull())
        assertEquals("NotAllowedError", other.message)
    }
}
