package dev.stapler.stelekit.git.lfs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LfsPointerTest {
    private val oidHex = "4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"
    private val canonical =
        "version https://git-lfs.github.com/spec/v1\noid sha256:$oidHex\nsize 12345\n"

    @Test
    fun serialize_should_ProduceCanonicalBytes_When_GivenOidAndSize() {
        val bytes = LfsPointerCodec.serialize(LfsPointer(LfsOid(oidHex), 12345))

        assertEquals(canonical, bytes.decodeToString())
    }

    @Test
    fun parse_should_RoundTripByteForByte_When_PointerIsCanonical() {
        val parsed = LfsPointerCodec.parse(canonical.encodeToByteArray())

        val valid = assertIs<LfsPointerParse.Valid>(parsed)
        assertEquals(LfsOid(oidHex), valid.pointer.oid)
        assertEquals(12345L, valid.pointer.size)
        assertContentEquals(canonical.encodeToByteArray(), LfsPointerCodec.serialize(valid.pointer))
    }

    @Test
    fun parse_should_ReturnCorruptCrlf_When_EveryNewlineIsRewrittenToCrlf() {
        val parsed = LfsPointerCodec.parse(canonical.replace("\n", "\r\n").encodeToByteArray())

        assertEquals(LfsPointerParse.Corrupt("CRLF"), parsed)
    }

    @Test
    fun parse_should_ReturnNotAPointer_When_TextDoesNotStartWithTheVersionLine() {
        assertEquals(LfsPointerParse.NotAPointer, LfsPointerCodec.parse("# My notes\n".encodeToByteArray()))
        assertEquals(LfsPointerParse.NotAPointer, LfsPointerCodec.parse(ByteArray(0)))
    }

    @Test
    fun parse_should_AcceptLegacyVersionUrls_When_Reading() {
        // INFERRED from git-lfs pointer.go: old hawser / git-media version URLs are still readable.
        for (legacy in listOf("https://hawser.github.com/spec/v1", "http://git-media.io/v/2")) {
            val text = "version $legacy\noid sha256:$oidHex\nsize 7\n"
            assertIs<LfsPointerParse.Valid>(LfsPointerCodec.parse(text.encodeToByteArray()), legacy)
        }
    }

    @Test
    fun serialize_should_AlwaysEmitTheCanonicalVersion_When_PointerCameFromALegacyUrl() {
        val legacy = "version https://hawser.github.com/spec/v1\noid sha256:$oidHex\nsize 7\n"
        val valid = assertIs<LfsPointerParse.Valid>(LfsPointerCodec.parse(legacy.encodeToByteArray()))

        assertTrue(LfsPointerCodec.serialize(valid.pointer).decodeToString().startsWith("version https://git-lfs.github.com/spec/v1\n"))
    }

    @Test
    fun parse_should_HandleSizeZeroAndNineteenDigitSizes() {
        val zero = LfsPointerCodec.parse("version https://git-lfs.github.com/spec/v1\noid sha256:$oidHex\nsize 0\n".encodeToByteArray())
        assertEquals(0L, assertIs<LfsPointerParse.Valid>(zero).pointer.size)

        val big = Long.MAX_VALUE.toString()
        assertEquals(19, big.length)
        val parsed = LfsPointerCodec.parse("version https://git-lfs.github.com/spec/v1\noid sha256:$oidHex\nsize $big\n".encodeToByteArray())
        assertEquals(Long.MAX_VALUE, assertIs<LfsPointerParse.Valid>(parsed).pointer.size)
    }

    @Test
    fun parse_should_KeepExtensionLinesBeforeOid_When_PointerHasExtensions() {
        val text = "version https://git-lfs.github.com/spec/v1\next-0-foo sha256:$oidHex\noid sha256:$oidHex\nsize 5\n"

        val valid = assertIs<LfsPointerParse.Valid>(LfsPointerCodec.parse(text.encodeToByteArray()))

        assertEquals(listOf("ext-0-foo" to "sha256:$oidHex"), valid.pointer.extensions)
        assertEquals(text, LfsPointerCodec.serialize(valid.pointer).decodeToString())
    }

    @Test
    fun parse_should_ReturnCorruptUnsortedKeys_When_SizeComesBeforeOid() {
        val text = "version https://git-lfs.github.com/spec/v1\nsize 5\noid sha256:$oidHex\n"

        assertEquals(LfsPointerParse.Corrupt("UNSORTED_KEYS"), LfsPointerCodec.parse(text.encodeToByteArray()))
    }

    @Test
    fun parse_should_ReturnCorruptBom_When_PointerStartsWithAByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + canonical.encodeToByteArray()

        assertEquals(LfsPointerParse.Corrupt("BOM"), LfsPointerCodec.parse(bytes))
    }

    @Test
    fun sniffer_should_ReturnFalseWithoutNeedingTheHead_When_FileIsFarLargerThanAPointer() {
        assertFalse(LfsPointerSniffer.looksLikePointer(5_000_000, head = null))
        assertFalse(LfsPointerSniffer.looksLikePointer(5_000_000, canonical.encodeToByteArray()))
    }

    @Test
    fun sniffer_should_ReturnTrue_When_SmallFileStartsWithTheVersionLine() {
        val bytes = canonical.encodeToByteArray()

        assertTrue(LfsPointerSniffer.looksLikePointer(bytes.size.toLong(), bytes))
        assertFalse(LfsPointerSniffer.looksLikePointer(11, "# My notes\n".encodeToByteArray()))
    }
}
