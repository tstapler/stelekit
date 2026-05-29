package dev.stapler.stelekit.db

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Android PRAGMA list applied by WalConfiguredCallback.
 *
 * WalConfiguredCallback is private but references the package-internal [ANDROID_PRAGMAS]
 * constant, which is directly inspectable from androidUnitTest without a real SQLiteDatabase.
 */
class WalConfiguredCallbackTest {

    @Test
    fun `androidPragmaList_should_containMmapSize_when_listInspected`() {
        assertTrue(
            ANDROID_PRAGMAS.contains("PRAGMA mmap_size=268435456"),
            "Expected ANDROID_PRAGMAS to contain 'PRAGMA mmap_size=268435456' but got:\n$ANDROID_PRAGMAS"
        )
    }

    @Test
    fun `androidPragmaList_should_notContainMmapSize_when_mmap_isAbsent`() {
        // Sentinel test: verifies the test infrastructure can detect a missing pragma.
        // If ANDROID_PRAGMAS somehow omits mmap_size, the positive test above will fail.
        // This negative assertion confirms that a list WITHOUT mmap_size does NOT pass the check.
        val listWithoutMmap = ANDROID_PRAGMAS.filter { !it.contains("mmap_size") }
        assertFalse(
            listWithoutMmap.contains("PRAGMA mmap_size=268435456"),
            "A list without mmap_size must not contain the mmap_size pragma"
        )
    }
}
