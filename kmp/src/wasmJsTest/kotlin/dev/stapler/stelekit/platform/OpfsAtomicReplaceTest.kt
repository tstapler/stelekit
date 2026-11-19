@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 1.4.7e: the OPFS replace primitives and the startup probe, in headless Chromium (`wasmJsBrowserTest`).
 * Replaces the throwaway spike harness of Task 0.1.8a; the Chrome row it recorded is in `research/opfs-spike.md`.
 */
class OpfsAtomicReplaceTest {
    private fun scratch(): String = "/stelekit-replace-test/r-${Random.nextInt(0, Int.MAX_VALUE)}"

    private suspend fun rootNames(): List<String> = listOpfsEntries(getOpfsRoot()).map { getEntryName(it) }

    private suspend fun write(path: String, bytes: ByteArray) = opfsWriteFileBytes(path, bytes.toJsUint8Array())

    private fun bytes(seed: Int, size: Int = 128) = ByteArray(size) { (it * 7 + seed).toByte() }

    @Test
    fun move_should_ReplaceTheDestinationAndRemoveTheSource() = runTest {
        val dir = scratch()
        write("$dir/dest.json", bytes(1))
        write("$dir/dest.json.tmp", bytes(2))

        assertTrue(opfsMoveFile("$dir/dest.json.tmp", "$dir/dest.json").isRight())

        assertContentEquals(bytes(2), opfsReadSlice("$dir/dest.json", 1024))
        assertNull(opfsReadSlice("$dir/dest.json.tmp", 1))
        opfsDeleteDirectoryRecursive(dir)
    }

    @Test
    fun copyVerify_should_ReplaceTheDestinationAndRemoveTheSource() = runTest {
        val dir = scratch()
        write("$dir/dest.json", bytes(1))
        write("$dir/dest.json.tmp", bytes(3, 4096))

        assertTrue(opfsCopyVerify("$dir/dest.json.tmp", "$dir/dest.json").isRight())

        assertContentEquals(bytes(3, 4096), opfsReadSlice("$dir/dest.json", 1 shl 20))
        assertNull(opfsReadSlice("$dir/dest.json.tmp", 1))
        opfsDeleteDirectoryRecursive(dir)
    }

    @Test
    fun copyVerifyOfAMissingSource_should_FailAndKeepTheDestination() = runTest {
        val dir = scratch()
        write("$dir/dest.json", bytes(1))

        val result = opfsCopyVerify("$dir/absent.tmp", "$dir/dest.json")

        assertIs<DomainError.FileSystemError.WriteFailed>(result.leftOrNull())
        assertContentEquals(bytes(1), opfsReadSlice("$dir/dest.json", 1024))
        opfsDeleteDirectoryRecursive(dir)
    }

    @Test
    fun readSlice_should_ReturnExactlyTheRequestedPrefix() = runTest {
        val dir = scratch()
        write("$dir/big.json", bytes(5, 2 * 1024 * 1024))

        val head = assertNotNull(opfsReadSlice("$dir/big.json", 256))

        assertEquals(256, head.size)
        assertContentEquals(bytes(5, 256), head)
        assertNull(opfsReadSlice("$dir/missing.json", 256))
        opfsDeleteDirectoryRecursive(dir)
    }

    @Test
    fun requestPersist_should_AnswerWithoutThrowing() = runTest {
        val answer = WasmBrowserPersistence().request()

        assertNotEquals(PersistenceState.NotRequested, answer)
    }

    @Test
    fun quotaEstimate_should_BePositiveInChrome() = runTest {
        val quota = assertNotNull(opfsQuotaBytes())

        assertTrue(quota > 0)
    }

    @Test
    fun probe_should_FindChromeAvailableUsingMove_WithinTheFileBound_AndLeaveNoDirectory() = runTest {
        val result = OpfsCapability().probe()

        assertEquals(OpfsProbeFacts(rootPresent = true, createWritable = true, moveVerified = true, copyVerifyVerified = true, sliceRead = true), result.facts)
        assertEquals(OpfsStorageVerdict.Available(ReplacePath.Move), result.verdict)
        assertTrue(result.files.size <= OpfsProbeDirectory.MAX_FILES, "wrote ${result.files.size} files")
        assertTrue(result.files.all { it.bytes <= OpfsProbeDirectory.MAX_FILE_BYTES })
        assertTrue(result.directory.removePrefix("/").startsWith(OpfsProbeDirectory.PREFIX))
        assertFalse(result.directory.removePrefix("/") in rootNames())
    }

    @Test
    fun probe_should_CleanUpAfterAFailedStep_AndNeverSayAvailable() = runTest {
        val capability = OpfsCapability(beforeStep = { if (it == "move") error("simulated failure") })

        val result = capability.probe()

        assertFalse(result.facts.moveVerified)
        assertTrue(result.facts.copyVerifyVerified)
        assertEquals(OpfsStorageVerdict.Available(ReplacePath.CopyVerify), result.verdict)
        assertFalse(result.directory.removePrefix("/") in rootNames())
    }

    @Test
    fun probe_should_FailTheWholeVerdictWhenBothReplacePathsFail() = runTest {
        val capability = OpfsCapability(beforeStep = { if (it == "move" || it == "copyVerify") error("simulated failure") })

        val result = capability.probe()

        assertEquals(OpfsStorageVerdict.Unavailable(StorageReason.NoAtomicReplace), result.verdict)
        assertFalse(result.directory.removePrefix("/") in rootNames())
    }

    @Test
    fun twoConcurrentProbes_should_UseTwoDirectoriesAndBothSucceed() = runTest {
        val results = listOf(async { OpfsCapability().probe() }, async { OpfsCapability().probe() }).awaitAll()

        assertNotEquals(results[0].directory, results[1].directory)
        assertTrue(results.all { it.verdict == OpfsStorageVerdict.Available(ReplacePath.Move) })
    }

    @Test
    fun probe_should_RemoveAStaleProbeDirectory_AndKeepAFreshOne() = runTest {
        val staleName = OpfsProbeDirectory.nameFor(uuidAt(System_millis() - 2 * OpfsProbeDirectory.STALE_AFTER_MS))
        val freshName = OpfsProbeDirectory.nameFor(uuidAt(System_millis()))
        write("/$staleName/x", bytes(1, 4))
        write("/$freshName/x", bytes(1, 4))

        OpfsCapability().probe()

        val names = rootNames()
        assertFalse(staleName in names, "a probe directory older than a day must be removed")
        assertTrue(freshName in names, "a fresh probe directory belongs to another tab and must stay")
        opfsDeleteDirectoryRecursive("/$freshName")
    }

    private fun System_millis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** A UUIDv7 whose 48-bit stamp is [millis]; the other bits are fixed. */
    private fun uuidAt(millis: Long): String {
        val hex = millis.toString(16).padStart(12, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8)}-7000-8000-000000000000"
    }

    @Test
    fun generatedProbeNames_should_NeverBeStale() {
        val name = OpfsProbeDirectory.nameFor(UuidGenerator.generateV7())

        assertFalse(OpfsProbeDirectory.isStale(name, System_millis()))
    }
}
