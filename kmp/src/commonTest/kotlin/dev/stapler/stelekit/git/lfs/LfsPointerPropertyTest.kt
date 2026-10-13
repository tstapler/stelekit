package dev.stapler.stelekit.git.lfs

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class LfsPointerPropertyTest {
    private val version = "version https://git-lfs.github.com/spec/v1\n"

    @Test
    fun roundTripForAnyOidAndSize() = runTest {
        checkAll(LfsArbs.oid, LfsArbs.size) { oid, size ->
            val bytes = LfsPointerCodec.serialize(LfsPointer(oid, size))

            val valid = assertIs<LfsPointerParse.Valid>(LfsPointerCodec.parse(bytes))

            assertEquals(oid, valid.pointer.oid)
            assertEquals(size, valid.pointer.size)
        }
    }

    @Test
    fun canonicalBytesAreStableUnderReserialise() = runTest {
        checkAll(LfsArbs.pointerBytes) { bytes ->
            val valid = assertIs<LfsPointerParse.Valid>(LfsPointerCodec.parse(bytes))

            assertContentEquals(bytes, LfsPointerCodec.serialize(valid.pointer))
        }
    }

    @Test
    fun crlfRewriteIsCorruptNotNotAPointer() = runTest {
        checkAll(LfsArbs.pointerBytes) { bytes ->
            val crlf = bytes.decodeToString().replace("\n", "\r\n").encodeToByteArray()

            assertEquals(LfsPointerParse.Corrupt("CRLF"), LfsPointerCodec.parse(crlf))
        }
    }

    @Test
    fun everyRejectionCaseIsTypedNeverThrows() = runTest {
        checkAll(LfsArbs.oid, LfsArbs.size) { oid, size ->
            val h = oid.hex
            val rejected = mapOf(
                "missing oid" to "${version}size $size\n",
                "missing size" to "${version}oid sha256:$h\n",
                "version not first" to "oid sha256:$h\n${version}size $size\n",
                "wrong-case url" to "version HTTPS://git-lfs.github.com/spec/v1\noid sha256:$h\nsize $size\n",
                "uppercase oid" to "${version}oid sha256:${"A".repeat(LfsOid.LENGTH)}\nsize $size\n",
                "63 char oid" to "${version}oid sha256:${h.drop(1)}\nsize $size\n",
                "65 char oid" to "${version}oid sha256:${h}0\nsize $size\n",
                "non-hex oid" to "${version}oid sha256:${h.drop(1)}g\nsize $size\n",
                "sha1" to "${version}oid sha1:${h.take(40)}\nsize $size\n",
                "negative size" to "${version}oid sha256:$h\nsize -1\n",
                "overflowing size" to "${version}oid sha256:$h\nsize 99999999999999999999\n",
                "duplicate keys" to "${version}oid sha256:$h\noid sha256:$h\nsize $size\n",
                "unsorted keys" to "${version}size $size\noid sha256:$h\n",
                "no trailing newline" to "${version}oid sha256:$h\nsize $size",
                "underscore key" to "${version}oid sha256:$h\nsi_ze $size\n",
                "uppercase key" to "${version}Oid sha256:$h\nsize $size\n",
                "carriage return in value" to "${version}oid sha256:$h\nsize $size\r\n",
                "over 1023 bytes" to "${version}ext-0-x ${"a".repeat(1100)}\noid sha256:$h\nsize $size\n",
                "NUL byte" to "${version}oid sha256:$h\nsize $size\u0000\n",
            )
            for ((label, text) in rejected) {
                val parsed = LfsPointerCodec.parse(text.encodeToByteArray())

                if (label == "version not first") {
                    // Not starting with the version line means it is simply not a pointer (plan: Corrupt needs the version line).
                    assertEquals(LfsPointerParse.NotAPointer, parsed, label)
                } else {
                    assertNotEquals(LfsPointerParse.NotAPointer, parsed, "$label must be Corrupt, not NotAPointer")
                    assertIs<LfsPointerParse.Corrupt>(parsed, label)
                }
            }
        }
    }

    @Test
    fun bomPrefixedPointerIsCorrupt() = runTest {
        checkAll(LfsArbs.pointerBytes) { bytes ->
            val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes

            assertEquals(LfsPointerParse.Corrupt("BOM"), LfsPointerCodec.parse(withBom))
        }
    }

    @Test
    fun arbitraryBytesNeverThrowAndOnlyValidRoundTrips() = runTest {
        checkAll(Arb.byteArray(Arb.int(0, 200), Arb.byte())) { bytes ->
            val parsed = LfsPointerCodec.parse(bytes)

            if (parsed is LfsPointerParse.Valid) {
                assertIs<LfsPointerParse.Valid>(LfsPointerCodec.parse(LfsPointerCodec.serialize(parsed.pointer)))
            }
        }
    }
}
