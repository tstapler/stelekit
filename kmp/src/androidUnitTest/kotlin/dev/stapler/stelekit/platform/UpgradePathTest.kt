package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * TC-012: Regression guard for the F-Droid uninstall+reinstall scenario (TR-3).
 *
 * When a user reinstalls the app (to work around F-Droid repo gaps), Android assigns a new UID
 * and wipes all persistable URI grants. SharedPreferences may survive via Android Backup or a
 * test-seeded state. This test asserts that init() detects the missing grant, clears the stale
 * URI from prefs, and leaves hasStoragePermission() returning false — the precondition that
 * causes the UI to route to PermissionRecoveryScreen instead of silently showing an empty graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpgradePathTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(PlatformFileSystem.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun `F-Droid reinstall with stale prefs — grant wiped — recovery screen precondition`() {
        // Simulate "v1" state: user previously granted SAF access; URI is in prefs
        val storedUri = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki"
        prefs().edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, storedUri).commit()

        // No takePersistableUriPermission call — simulates post-reinstall: grants wiped by UID change

        val fs = PlatformFileSystem().apply { init(context) }

        // The UI routes to PermissionRecoveryScreen (not LibrarySetupScreen) based on this state.
        // init() must detect the missing grant and clear prefs so the next composition evaluates
        // isSafPath = false (currentGraphPath is now the legacy default, not a saf:// path).
        assertFalse(
            fs.hasStoragePermission(),
            "hasStoragePermission must be false after reinstall — grant was wiped by UID change"
        )
        assertNull(
            prefs().getString(PlatformFileSystem.KEY_SAF_TREE_URI, null),
            "init() must clear the stale SAF URI from prefs when no grant is held"
        )
    }
}
