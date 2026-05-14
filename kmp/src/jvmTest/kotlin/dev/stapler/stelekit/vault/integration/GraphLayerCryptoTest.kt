package dev.stapler.stelekit.vault.integration

import arrow.core.Either
import dev.stapler.stelekit.vault.*
import kotlin.test.*

/**
 * Integration tests for the CryptoLayer behaviors exercised by GraphLoader.readFileDecrypted
 * and GraphWriter.savePageInternal. These tests verify the decrypt-with-fallback contract
 * that both graph IO classes rely on.
 *
 * I-GL-01 — Correct DEK decrypts successfully (happy path)
 * I-GL-02 — Wrong DEK causes AuthenticationFailed (GraphLoader returns null)
 * I-GL-03 — Plaintext file returns NotEncrypted (GraphLoader falls back to readFile)
 * I-GL-04 — Encrypt at path A, decrypt at path A succeeds; same bytes at path B fail (AAD binding)
 */
class GraphLayerCryptoTest {
    private val engine = JvmCryptoEngine()

    // I-GL-01 — GraphLoader happy path: correct DEK decrypts file content
    @Test fun `correct DEK decrypts file content successfully`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val path = "pages/Note.md.stek"
        val markdown = "# Note\n\n- block one\n- block two\n"

        val encrypted = layer.encrypt(path, markdown.encodeToByteArray())
        val result = layer.decrypt(path, encrypted)

        assertTrue(result.isRight(), "Decryption with correct DEK must succeed")
        assertEquals(markdown, result.getOrNull()!!.decodeToString())
    }

    // I-GL-02 — GraphLoader wrong-key path: AuthenticationFailed causes null return
    @Test fun `wrong DEK causes AuthenticationFailed so GraphLoader returns null`() {
        val correctDek = engine.secureRandom(32)
        val wrongDek = engine.secureRandom(32)
        val correctLayer = CryptoLayer(engine, correctDek)
        val wrongLayer = CryptoLayer(engine, wrongDek)
        val path = "pages/Sensitive.md.stek"

        val encrypted = correctLayer.encrypt(path, "secret content".encodeToByteArray())
        val result = wrongLayer.decrypt(path, encrypted)

        assertTrue(result.isLeft())
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull(),
            "Wrong DEK must return AuthenticationFailed (GraphLoader maps this to null)")
    }

    // I-GL-03 — Migration compatibility: plaintext (non-STEK) file returns NotEncrypted
    // GraphLoader.readFileDecrypted falls back to fileSystem.readFile() when NotEncrypted.
    @Test fun `plaintext file returns NotEncrypted for graceful fallback`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val legacyMarkdown = "# Legacy Note\n\n- old content\n".encodeToByteArray()

        // A plain markdown file has no STEK magic bytes
        val result = layer.decrypt("pages/Legacy.md", legacyMarkdown)

        assertTrue(result.isLeft())
        assertIs<VaultError.NotEncrypted>(result.leftOrNull(),
            "Non-STEK file must return NotEncrypted so GraphLoader can fall back to readFile()")
    }

    // I-GL-04 — AAD path-binding enforces write path = read path
    // GraphWriter encrypts with the graph-root-relative path as AAD.
    // GraphLoader decrypts with the same relative path. Moving a file without re-encrypting fails.
    @Test fun `encrypt at path A cannot be decrypted at path B`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "# Note\n- content".encodeToByteArray()

        val pathA = "pages/Original.md.stek"
        val pathB = "pages/Moved.md.stek"

        val encrypted = layer.encrypt(pathA, content)

        // Same bytes presented under a different path → AAD mismatch → AuthenticationFailed
        val result = layer.decrypt(pathB, encrypted)
        assertTrue(result.isLeft())
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull(),
            "Ciphertext encrypted at pathA must not decrypt at pathB — AAD includes the file path")

        // But same bytes at the original path → succeeds
        val okResult = layer.decrypt(pathA, encrypted)
        assertTrue(okResult.isRight())
        assertContentEquals(content, okResult.getOrNull())
    }
}
