package dev.stapler.stelekit.platform

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Tests for the legacy (non-SAF) path validation logic in [PlatformFileSystem].
 *
 * [PlatformFileSystem.validateLegacyPath] is private but exercised through the public
 * [readFile] / [writeFile] methods for non-saf:// paths.  The three invariants enforced:
 *
 *   1. Null bytes in the path are rejected (security: POSIX strings end at \0).
 *   2. Paths longer than 4096 characters are rejected.
 *   3. After canonicalization the path must stay inside the public Documents directory
 *      (path-traversal prevention: "../../etc/passwd" etc.).
 *
 * Robolectric initialises [Environment.getExternalStoragePublicDirectory] to a real temp
 * directory for the test process, so canonical-path checks work correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyPathValidationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fs() = PlatformFileSystem().apply { init(context) }

    private val homeDir: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            ?.absolutePath ?: "/storage/emulated/0/Documents"

    // -------------------------------------------------------------------------
    // Null-byte rejection
    // -------------------------------------------------------------------------

    @Test
    fun `readFile rejects path containing null byte`() {
        val path = "$homeDir/notes\u0000evil.md"
        assertNull(fs().readFile(path),
            "Null byte in path must not be passed to the filesystem — readFile must return null")
    }

    @Test
    fun `writeFile rejects path containing null byte`() {
        val path = "$homeDir/notes\u0000evil.md"
        assertFalse(fs().writeFile(path, "content"),
            "Null byte in path must not be passed to the filesystem — writeFile must return false")
    }

    // -------------------------------------------------------------------------
    // Maximum path length
    // -------------------------------------------------------------------------

    @Test
    fun `readFile rejects path exceeding 4096 characters`() {
        val tooLong = "$homeDir/" + "a".repeat(4090)
        assertNull(fs().readFile(tooLong),
            "Path longer than 4096 chars must cause readFile to return null")
    }

    @Test
    fun `writeFile rejects path exceeding 4096 characters`() {
        val tooLong = "$homeDir/" + "a".repeat(4090)
        assertFalse(fs().writeFile(tooLong, "content"),
            "Path longer than 4096 chars must cause writeFile to return false")
    }

    // -------------------------------------------------------------------------
    // Path-traversal containment
    // -------------------------------------------------------------------------

    @Test
    fun `readFile rejects path traversal outside homeDir`() {
        // "~/../../etc/passwd" canonicalises to "/etc/passwd" which is outside Documents
        val traversal = "$homeDir/../../etc/passwd"
        assertNull(fs().readFile(traversal),
            "Path traversal outside homeDir must cause readFile to return null")
    }

    @Test
    fun `writeFile rejects path traversal outside homeDir`() {
        val traversal = "$homeDir/../../etc/cron.d/evil"
        assertFalse(fs().writeFile(traversal, "content"),
            "Path traversal outside homeDir must cause writeFile to return false")
    }

    @Test
    fun `readFile rejects absolute path outside homeDir`() {
        assertNull(fs().readFile("/etc/passwd"),
            "Absolute path outside homeDir must cause readFile to return null")
    }

    @Test
    fun `writeFile rejects absolute path outside homeDir`() {
        assertFalse(fs().writeFile("/etc/cron.d/evil", "content"),
            "Absolute path outside homeDir must cause writeFile to return false")
    }

    // -------------------------------------------------------------------------
    // Boundary: path exactly at homeDir root is valid (if the dir exists)
    // -------------------------------------------------------------------------

    @Test
    fun `readFile returns null (not crash) for homeDir itself when not a file`() {
        // homeDir is a directory, not a file — readFile must return null gracefully
        assertNull(fs().readFile(homeDir),
            "homeDir is a directory, not a file — readFile must return null without crashing")
    }
}
