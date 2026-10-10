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
            "PRAGMA cache_size=-5000",
            "PRAGMA analysis_limit=400",
            "PRAGMA optimize=0x10002",
        )
        required.forEach { pragma ->
            assertTrue(
                ANDROID_PRAGMAS.contains(pragma),
                "Expected ANDROID_PRAGMAS to contain '$pragma' but got:\n$ANDROID_PRAGMAS"
            )
        }
    }

    @Test
    fun `analysis_limit_must_appear_before_optimize_to_bound_startup_analyze`() {
        val analysisIdx = ANDROID_PRAGMAS.indexOf("PRAGMA analysis_limit=400")
        val optimizeIdx = ANDROID_PRAGMAS.indexOf("PRAGMA optimize=0x10002")
        assertTrue(analysisIdx >= 0, "ANDROID_PRAGMAS must contain PRAGMA analysis_limit=400")
        assertTrue(optimizeIdx >= 0, "ANDROID_PRAGMAS must contain PRAGMA optimize=0x10002")
        assertTrue(
            analysisIdx < optimizeIdx,
            "analysis_limit must appear before optimize=0x10002 so the row limit applies " +
            "when optimize triggers ANALYZE internally (idx: $analysisIdx vs $optimizeIdx)"
        )
    }
}
