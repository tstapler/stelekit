package dev.stapler.stelekit.platform

import androidx.documentfile.provider.DocumentFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for DocumentFile. By default, DocumentFile.fromTreeUri().exists() always
 * returns false in Robolectric because no real ExternalStorageProvider is registered. This shadow
 * allows tests to control the exists() result and exercise the full isSafPermissionValid() path.
 *
 * Usage: add @Config(shadows = [ShadowDocumentFile::class]) to the test class, then set
 * ShadowDocumentFile.mockExistsResult = true before calling init().
 */
@Implements(DocumentFile::class)
class ShadowDocumentFile {

    companion object {
        @JvmField
        var mockExistsResult: Boolean = false
    }

    @Implementation
    fun exists(): Boolean = mockExistsResult
}
