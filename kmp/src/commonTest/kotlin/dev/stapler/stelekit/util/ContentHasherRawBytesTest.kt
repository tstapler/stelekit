package dev.stapler.stelekit.util

import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Locks in `ContentHasher.sha256(ByteArray)` as the byte-exact verify entry point
 * `BulkCopyVerifier` (Phase 3) must use for relocate's integrity check — as opposed to
 * `sha256ForContent`, which deliberately normalizes whitespace for block-dedup purposes and
 * would silently mask a corrupted copy that only differs in trailing whitespace.
 */
class ContentHasherRawBytesTest {

    @Test
    fun sha256_should_ProduceDifferentDigests_When_ByteArraysDifferOnlyInTrailingWhitespace() {
        val bytesA = "hello".encodeToByteArray()
        val bytesB = "hello ".encodeToByteArray()

        assertNotEquals(ContentHasher.sha256(bytesA), ContentHasher.sha256(bytesB))
    }
}
