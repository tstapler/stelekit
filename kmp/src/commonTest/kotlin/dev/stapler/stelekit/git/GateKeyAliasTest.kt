package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GateKeyAliasTest {
    /** Stand-in for `File.canonicalFile`: collapses `//`, `.` and `..`, trailing slashes, and follows the symlink table. */
    private class FakeCanonicalizer(private val links: Map<String, String> = emptyMap()) : PathCanonicalizer {
        override fun canonicalize(path: String): String {
            val parts = ArrayDeque<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(seg)
                }
            }
            val normal = "/" + parts.joinToString("/")
            return links[normal]?.let { canonicalize(it) } ?: normal
        }
    }

    private val real = "/home/u/graph/.git"

    @Test
    fun keyFor_should_ReturnSameKey_When_TrailingSlashSymlinkOrRelativeAliasOfOneDirectory() {
        val c = FakeCanonicalizer(mapOf("/home/u/link/.git" to real))
        val expected = RepoOpGate.keyFor(real, c)

        assertEquals(expected, RepoOpGate.keyFor("$real/", c))
        assertEquals(expected, RepoOpGate.keyFor("/home/u/graph/../graph/.git", c))
        assertEquals(expected, RepoOpGate.keyFor("/home/u/./graph//.git", c))
        assertEquals(expected, RepoOpGate.keyFor("/home/u/link/.git", c), "symlinked path")
    }

    @Test
    fun keyFor_should_GiveTheSameKeyOnASecondRegistration() {
        val c = FakeCanonicalizer()
        assertEquals(RepoOpGate.keyFor(real, c), RepoOpGate.keyFor(real, c))
    }

    @Test
    fun keyFor_should_NotCollide_When_PathsDifferOnlyByDashVersusSeparator() {
        val c = FakeCanonicalizer()
        assertNotEquals(RepoOpGate.keyFor("/a-b/.git", c), RepoOpGate.keyFor("/a/b/.git", c))
        assertNotEquals(RepoOpGate.keyFor("/a/b/.git", c), RepoOpGate.keyFor("/a/b/c/.git", c))
    }

    @Test
    fun keyForOrNull_should_ReturnNull_When_CanonicalisationFails() {
        val failing = PathCanonicalizer { throw IllegalStateException("io error") }
        assertNull(RepoOpGate.keyForOrNull(real, failing))
    }

    @Test
    fun keyFor_should_BeSixteenHexCharacters_And_NotTheRemoteUrlNormaliser() {
        val key = RepoOpGate.keyFor(real, FakeCanonicalizer())
        assertEquals(16, key.length)
        assertEquals(true, key.all { it in "0123456789abcdef" })
        assertEquals(false, key.startsWith("stele-write-"))
    }
}
