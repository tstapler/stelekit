package dev.stapler.stelekit.git.lfs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory probe that counts the bytes the guard asks to read. */
class FakeProbe(private val files: Map<String, ByteArray>) : LfsContentProbe {
    var bytesRequested = 0
        private set

    override suspend fun size(path: String): Long? = files[path]?.size?.toLong()

    override suspend fun readHead(path: String, maxBytes: Int): ByteArray? {
        bytesRequested += maxBytes
        return files[path]?.let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
    }
}

class LfsGuardTest {
    private val oid = LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393")
    private val pointer = LfsPointerCodec.serialize(LfsPointer(oid, 3_000_000))
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(2_000)

    private fun policy(rules: String = "assets/** filter=lfs") =
        LfsPathPolicy(AttributeRuleSet(listOf(GitAttributesParser.parse(rules))))

    @Test
    fun evaluate_should_HoldRawBinaryAndAllowTheRest_When_OnlyAssetsAreTracked() = runTest {
        val probe = FakeProbe(mapOf("pages/a.md" to "# hi".encodeToByteArray(), "assets/photo.png" to png))

        val verdict = LfsGuard.evaluate(listOf("pages/a.md", "assets/photo.png"), policy(), probe)

        assertEquals(listOf("assets/photo.png"), verdict.held.map { it.path })
        assertEquals(LfsViolationReason.RawBinary, verdict.held.single().reason)
        assertEquals(".gitattributes:1", verdict.held.single().ruleSource)
        assertEquals(listOf("pages/a.md"), verdict.allowed)
    }

    @Test
    fun evaluate_should_AllowAValidPointer_When_PathIsTracked() = runTest {
        val verdict = LfsGuard.evaluate(listOf("assets/photo.png"), policy(), FakeProbe(mapOf("assets/photo.png" to pointer)))

        assertTrue(verdict.isClean)
        assertEquals(listOf("assets/photo.png"), verdict.allowed)
    }

    @Test
    fun evaluate_should_AllowAnEmptyFile_When_PathIsTracked() = runTest {
        val verdict = LfsGuard.evaluate(listOf("assets/empty.bin"), policy(), FakeProbe(mapOf("assets/empty.bin" to ByteArray(0))))

        assertTrue(verdict.isClean)
    }

    @Test
    fun evaluate_should_NotJudgeGitattributesItself_When_EveryPathIsTracked() = runTest {
        val probe = FakeProbe(mapOf(".gitattributes" to "* filter=lfs\n".encodeToByteArray()))

        assertTrue(LfsGuard.evaluate(listOf(".gitattributes"), policy("* filter=lfs"), probe).isClean)
    }

    @Test
    fun evaluate_should_ReportCorruptPointer_When_PointerHasCrlf() = runTest {
        val crlf = pointer.decodeToString().replace("\n", "\r\n").encodeToByteArray()

        val verdict = LfsGuard.evaluate(listOf("assets/a.png"), policy(), FakeProbe(mapOf("assets/a.png" to crlf)))

        assertEquals(LfsViolationReason.CorruptPointer, verdict.held.single().reason)
    }

    @Test
    fun evaluate_should_ReportConflictMarkers_When_PointerFileWasLeftMidMerge() = runTest {
        val merged = "<<<<<<< HEAD\n${pointer.decodeToString()}=======\n${pointer.decodeToString()}>>>>>>> origin/main\n"

        val verdict = LfsGuard.evaluate(listOf("assets/a.png"), policy(), FakeProbe(mapOf("assets/a.png" to merged.encodeToByteArray())))

        assertEquals(LfsViolationReason.ConflictMarkers, verdict.held.single().reason)
    }

    @Test
    fun evaluate_should_FailClosed_When_TheFileCannotBeRead() = runTest {
        val verdict = LfsGuard.evaluate(listOf("assets/gone.png"), policy(), FakeProbe(emptyMap()))

        assertEquals(LfsViolationReason.RawBinary, verdict.held.single().reason)
    }

    @Test
    fun evaluate_should_EvaluateAnUncommittedGitattributesEdit_When_RulesComeFromTheWorkingTree() = runTest {
        // The loader reads the working tree, so a .gitattributes edit made this minute already applies.
        val probe = FakeProbe(mapOf("x.png" to png))

        assertTrue(LfsGuard.evaluate(listOf("x.png"), policy("# nothing yet"), probe).isClean)
        assertEquals(1, LfsGuard.evaluate(listOf("x.png"), policy("*.png filter=lfs"), probe).held.size)
    }

    @Test
    fun evaluate_should_ReadNothing_When_NoRuleTracksAnyPath() = runTest {
        val probe = FakeProbe(mapOf("a.png" to png))

        val verdict = LfsGuard.evaluate(listOf("a.png"), policy("*.txt -text"), probe)

        assertTrue(verdict.isClean)
        assertEquals(0, probe.bytesRequested)
    }
}
