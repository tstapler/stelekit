package dev.stapler.stelekit.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafPermissionStateTransitionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(PlatformFileSystem.PREFS_NAME, Context.MODE_PRIVATE)

    private val validUriStr = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fnotes"
    private val validUri: Uri get() = Uri.parse(validUriStr)

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    // TC-003 (explicit hasStoragePermission assertion): stored URI, no grant → false + prefs cleared
    // The prefs-cleared assertion is already in SafPermissionPersistenceTest.
    // This test adds the explicit hasStoragePermission() == false assertion for TR-1 traceability.
    @Test
    fun `stored URI with no grant returns hasStoragePermission false`() {
        prefs().edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, validUriStr).commit()
        // No takePersistableUriPermission call — simulates post-reinstall state

        val fs = PlatformFileSystem().apply { init(context) }

        assertFalse(fs.hasStoragePermission(), "hasStoragePermission must return false when no persistable grant is held")
        assertNull(
            prefs().getString(PlatformFileSystem.KEY_SAF_TREE_URI, null),
            "init() must clear prefs when the stored URI has no persistable grant"
        )
    }

    // TC-004 (explicit hasStoragePermission assertion): corrupt stored URI → false
    // The prefs-cleared and no-crash assertions are in SafPermissionPersistenceTest.
    @Test
    fun `corrupt stored URI results in hasStoragePermission false`() {
        prefs().edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, "not a valid uri!!!").commit()

        val fs = PlatformFileSystem().apply { init(context) }

        assertFalse(fs.hasStoragePermission(), "hasStoragePermission must return false for corrupt stored URI")
    }

    // TC-002 / TC-005 note: testing hasStoragePermission == true requires DocumentFile.fromTreeUri()
    // to return a non-null instance with exists() == true. In Robolectric, fromTreeUri() returns null
    // because no ExternalStorageProvider is registered, so isSafPermissionValid always returns false
    // even when a persistable grant is held. These scenarios require instrumented tests on a real
    // emulator (see androidInstrumentedTest/SmokeTest.kt) for full coverage.
    //
    // The grant-check path (hasGrant in isSafPermissionValid) IS tested via the fast-fail path
    // in TC-003 and UpgradePathTest — those verify that missing grants are detected and prefs cleared.
}
