package dev.stapler.stelekit.git.lfs

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LfsGuardPropertyTest {
    private val tracked = LfsPathPolicy(AttributeRuleSet(listOf(GitAttributesParser.parse("assets/** filter=lfs"))))
    private val trackedPath = "assets/file.bin"
    private val untrackedPath = "pages/file.md"

    private val arbContent: Arb<ByteArray> = Arb.byteArray(Arb.int(0, 1_500), Arb.byte())

    private fun isPointerOrEmpty(bytes: ByteArray) =
        bytes.isEmpty() || LfsPointerCodec.parse(bytes) is LfsPointerParse.Valid

    @Test
    fun allowedIffPointerOrEmptyAtTrackedPath() = runTest {
        checkAll(arbContent) { content ->
            val verdict = LfsGuard.evaluate(listOf(trackedPath), tracked, FakeProbe(mapOf(trackedPath to content)))

            assertEquals(isPointerOrEmpty(content), verdict.isClean)
        }
    }

    @Test
    fun validPointersAreAlwaysAllowed() = runTest {
        checkAll(LfsArbs.pointerBytes) { pointer ->
            assertTrue(LfsGuard.evaluate(listOf(trackedPath), tracked, FakeProbe(mapOf(trackedPath to pointer))).isClean)
        }
    }

    @Test
    fun untrackedPathNeverHeld() = runTest {
        checkAll(arbContent) { content ->
            val verdict = LfsGuard.evaluate(listOf(untrackedPath), tracked, FakeProbe(mapOf(untrackedPath to content)))

            assertTrue(verdict.isClean)
            assertEquals(listOf(untrackedPath), verdict.allowed)
        }
    }

    @Test
    fun bomAndCrlfPointersAreHeld() = runTest {
        checkAll(LfsArbs.pointerBytes) { pointer ->
            val crlf = pointer.decodeToString().replace("\n", "\r\n").encodeToByteArray()
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + pointer

            for (bad in listOf(crlf, bom)) {
                val verdict = LfsGuard.evaluate(listOf(trackedPath), tracked, FakeProbe(mapOf(trackedPath to bad)))

                assertEquals(LfsViolationReason.CorruptPointer, verdict.held.single().reason)
            }
        }
    }

    @Test
    fun over1024BytesIsHeldWithoutReading() = runTest {
        checkAll(Arb.int(1_024, 5_000)) { size ->
            val probe = FakeProbe(mapOf(trackedPath to ByteArray(size)))

            val verdict = LfsGuard.evaluate(listOf(trackedPath), tracked, probe)

            assertEquals(LfsViolationReason.RawBinary, verdict.held.single().reason)
            assertEquals(0, probe.bytesRequested, "a file too big to be a pointer must not be read")
        }
    }

    @Test
    fun versionPrefixTextFileIsHeld() = runTest {
        checkAll(Arb.list(Arb.element("a", "b", "oid", "size 5", "hello"), 0..5)) { extra ->
            val text = "version https://example.com/not-lfs\n" + extra.joinToString("\n")

            val verdict = LfsGuard.evaluate(listOf(trackedPath), tracked, FakeProbe(mapOf(trackedPath to text.encodeToByteArray())))

            assertEquals(1, verdict.held.size)
        }
    }

    @Test
    fun neverReadsMoreThan1024Bytes() = runTest {
        checkAll(arbContent) { content ->
            val probe = FakeProbe(mapOf(trackedPath to content))

            LfsGuard.evaluate(listOf(trackedPath), tracked, probe)

            assertTrue(probe.bytesRequested <= LfsPointerCodec.MAX_POINTER_BYTES, "requested ${probe.bytesRequested}")
        }
    }
}
