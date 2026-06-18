package dev.stapler.stelekit.db

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Unit tests for the Android PRAGMA list applied by WalConfiguredCallback.
 *
 * WalConfiguredCallback is private but references the package-internal [ANDROID_PRAGMAS]
 * constant, which is directly inspectable from androidUnitTest without a real SQLiteDatabase.
 */
class WalConfiguredCallbackTest {

    @Test
    fun `androidPragmaList_should_contain_all_performance_pragmas`() {
        val required = listOf(
            "PRAGMA mmap_size=67108864",
            "PRAGMA wal_autocheckpoint=1000",
            "PRAGMA temp_store=MEMORY",
            "PRAGMA cache_size=-8000",
        )
        required.forEach { pragma ->
            assertTrue(
                ANDROID_PRAGMAS.contains(pragma),
                "Expected ANDROID_PRAGMAS to contain '$pragma' but got:\n$ANDROID_PRAGMAS"
            )
        }
    }
}
