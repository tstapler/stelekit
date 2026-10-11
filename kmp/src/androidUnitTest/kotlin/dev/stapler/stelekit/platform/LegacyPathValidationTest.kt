package dev.stapler.stelekit.platform

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // -------------------------------------------------------------------------
    // context.filesDir as a second allowed root (Story 2.2.1/2.2.2 "App storage"): before this
    // fix, validateLegacyPath only ever allowed homeDir (the public Documents dir), so a graph
    // rooted at PlatformFileSystem.newAppOwnedGraphPath() — under context.filesDir — could not
    // read or write a single page. See validateLegacyPath's own comment for the root cause.
    // -------------------------------------------------------------------------

    @Test
    fun `writeFile and readFile round-trip for a path under context filesDir`() {
        val fs = fs()
        val path = context.filesDir.resolve("graphs/g1/pages/test.md").absolutePath

        assertTrue(fs.writeFile(path, "hello"), "writeFile must succeed for a filesDir-rooted AppOwned path")
        assertEquals("hello", fs.readFile(path))
    }

    @Test
    fun `newAppOwnedGraphPath produces a path that is both filesDir-rooted and writable`() {
        val fs = fs()
        val path = fs.newAppOwnedGraphPath()

        assertTrue(
            path.startsWith(context.filesDir.canonicalPath),
            "newAppOwnedGraphPath() must be rooted under context.filesDir, was: $path",
        )
        assertTrue(fs.writeFile("$path/pages/test.md", "hello"))
    }

    // -------------------------------------------------------------------------
    // Containment boundary regression: a prefix-collision string match must not substitute for
    // an actual directory-boundary check, and the filesDir root must be scoped to graphs/, not
    // all of filesDir (databases, prefs, cache). See validateLegacyPath's own comment.
    // -------------------------------------------------------------------------

    @Test
    fun `writeFile rejects a sibling directory whose name merely prefix-collides with filesDir`() {
        val fs = fs()
        // "filesDir" + "Evil" starts with the same characters as filesDir's canonical path but is
        // a completely different, sibling directory — startsWith(root) alone would wrongly accept it.
        val collidingPath = "${context.filesDir.canonicalPath}Evil/graphs/g1/pages/test.md"

        assertFalse(
            fs.writeFile(collidingPath, "malicious"),
            "A sibling directory that merely string-prefixes filesDir must be rejected, not treated as contained",
        )
    }

    @Test
    fun `readFile rejects a sibling directory whose name merely prefix-collides with homeDir`() {
        val fs = fs()
        val collidingPath = "${homeDir}Evil/notes.md"

        assertNull(
            fs.readFile(collidingPath),
            "A sibling directory that merely string-prefixes homeDir must be rejected, not treated as contained",
        )
    }

    @Test
    fun `writeFile and readFile round-trip for a genuine path under filesDir graphs`() {
        val fs = fs()
        val path = context.filesDir.resolve("graphs/g2/pages/real.md").absolutePath

        assertTrue(fs.writeFile(path, "genuine"), "A real path under filesDir/graphs must be accepted")
        assertEquals("genuine", fs.readFile(path))
    }

    @Test
    fun `writeFile rejects a path under filesDir but outside the graphs subdirectory`() {
        val fs = fs()
        // e.g. an app database file living directly under filesDir, outside graphs/ — the
        // pre-fix code allowed the whole of filesDir, which is a far wider blast radius than
        // AppOwned graph content actually needs.
        val dbPath = context.filesDir.resolve("databases/stelekit.db").absolutePath

        assertFalse(
            fs.writeFile(dbPath, "corrupt"),
            "Paths under filesDir but outside filesDir/graphs must be rejected now that scope is narrowed",
        )
    }

    @Test
    fun `readFile rejects a path under filesDir but outside the graphs subdirectory`() {
        val fs = fs()
        val prefsPath = context.filesDir.resolve("shared_prefs/settings.xml").absolutePath

        assertNull(
            fs.readFile(prefsPath),
            "Paths under filesDir but outside filesDir/graphs must be rejected now that scope is narrowed",
        )
    }

    // -------------------------------------------------------------------------
    // legacyReadFileBytes (readFileBytes for non-saf:// paths) has its own, separate containment
    // check — not routed through validateLegacyPath — so it needs the same boundary-bug regression
    // coverage independently. See legacyReadFileBytes's own comment for the allowed-root rationale.
    // -------------------------------------------------------------------------

    @Test
    fun `readFileBytes rejects a sibling directory whose name merely prefix-collides with filesDir`() {
        val fs = fs()
        val collidingPath = "${context.filesDir.canonicalPath}Evil/graphs/g1/pages/test.md"

        assertNull(
            fs.readFileBytes(collidingPath),
            "A sibling directory that merely string-prefixes filesDir must be rejected, not treated as contained",
        )
    }

    @Test
    fun `readFileBytes rejects a sibling directory whose name merely prefix-collides with cacheDir`() {
        val fs = fs()
        val collidingPath = "${context.cacheDir.canonicalPath}Evil/photo.jpg"

        assertNull(
            fs.readFileBytes(collidingPath),
            "A sibling directory that merely string-prefixes cacheDir must be rejected, not treated as contained",
        )
    }

    @Test
    fun `readFileBytes rejects a path under filesDir but outside the graphs subdirectory`() {
        val fs = fs()
        val dbPath = context.filesDir.resolve("databases/stelekit.db").absolutePath

        assertNull(
            fs.readFileBytes(dbPath),
            "Paths under filesDir but outside filesDir/graphs must be rejected — legacyReadFileBytes" +
                " must not be usable to read the app's own databases",
        )
    }

    @Test
    fun `readFileBytes round-trips for a genuine path under filesDir graphs`() {
        val fs = fs()
        val path = context.filesDir.resolve("graphs/g3/assets/real.png").absolutePath

        assertTrue(fs.writeFileBytes(path, byteArrayOf(1, 2, 3)), "writeFileBytes must succeed under filesDir/graphs")
        assertTrue(fs.readFileBytes(path)?.contentEquals(byteArrayOf(1, 2, 3)) == true)
    }

    @Test
    fun `readFileBytes round-trips for a genuine path under cacheDir`() {
        // Mirrors legitimate legacy attachment sources (camera captures, photo picker, voice
        // recorder), which write bytes under cacheDir via raw java.io.File APIs (not
        // FileSystem.writeFileBytes — cacheDir is not a validateLegacyPath-allowed write root)
        // before ImageImportService copies them into a graph via readFileBytes.
        val fs = fs()
        val file = context.cacheDir.resolve("captures/photo.jpg")
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(4, 5, 6))

        assertTrue(fs.readFileBytes(file.absolutePath)?.contentEquals(byteArrayOf(4, 5, 6)) == true)
    }
}
