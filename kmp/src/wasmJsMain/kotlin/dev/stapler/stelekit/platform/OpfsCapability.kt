package dev.stapler.stelekit.platform

import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** One file the probe wrote: its path and size, so a test can hold the probe to [OpfsProbeDirectory.MAX_FILES] x [OpfsProbeDirectory.MAX_FILE_BYTES]. */
class ProbeFile(val path: String, val bytes: Int)

/** What one startup probe found: the five facts, the quota estimate, and where it worked. */
class OpfsProbeResult(
    val facts: OpfsProbeFacts,
    val quotaBytes: Long?,
    val directory: String,
    val files: List<ProbeFile>,
) {
    val verdict: OpfsStorageVerdict get() = OpfsStorageVerdict.decide(facts)
    val privateSuspected: Boolean get() = PrivateWindowHint.from(quotaBytes)
}

/**
 * The startup OPFS probe (Task 1.4.7e, D-125, D-137). It is a class, not a `WiringAudit` seam. It fills the five facts
 * of [OpfsProbeFacts] by exercising the real primitives in `stelekit-probe-<uuidv7>` under the OPFS root: create and
 * read back, `move()` over an existing file, copy-with-verify over an existing file, and a 16-byte slice read. The
 * directory is deleted in a `finally`, and probe directories of a crashed earlier page load are removed once stale.
 *
 * [beforeStep] is a test seam called with each step name before the step runs.
 */
class OpfsCapability(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = UuidGenerator::generateV7,
    private val beforeStep: suspend (String) -> Unit = {},
) {
    suspend fun probe(): OpfsProbeResult {
        val name = OpfsProbeDirectory.nameFor(newId())
        val dir = "/$name"
        val written = mutableListOf<ProbeFile>()
        var facts = OpfsProbeFacts(rootPresent = false, createWritable = false, moveVerified = false, copyVerifyVerified = false, sliceRead = false)
        var quota: Long? = null
        if (!opfsApiPresent()) return OpfsProbeResult(facts, null, dir, written)
        try {
            facts = facts.copy(rootPresent = rootReachable())
            if (!facts.rootPresent) return OpfsProbeResult(facts, null, dir, written)
            removeStaleProbeDirectories()
            quota = opfsQuotaBytes()
            facts = run(dir, written, facts)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // A step that threw leaves its fact false; the verdict then says Unavailable, never Available.
        } finally {
            withContext(NonCancellable) { opfsDeleteDirectoryRecursive(dir) }
        }
        return OpfsProbeResult(facts, quota, dir, written)
    }

    private suspend fun rootReachable(): Boolean = try {
        getOpfsRoot()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        false
    }

    private suspend fun run(dir: String, written: MutableList<ProbeFile>, start: OpfsProbeFacts): OpfsProbeFacts {
        var facts = start
        val pathA = "$dir/a"
        val pathB = "$dir/b"
        val pathC = "$dir/c"
        val a = payload(1)
        val b = payload(2)
        val c = payload(3)
        suspend fun put(path: String, bytes: ByteArray) {
            opfsWriteFileBytes(path, bytes.toJsUint8Array())
            written += ProbeFile(path, bytes.size)
        }
        facts = facts.copy(createWritable = step("create") {
            put(pathA, a)
            contentEquals(pathA, a)
        })
        if (!facts.createWritable) return facts
        facts = facts.copy(moveVerified = step("move") {
            put(pathB, b)
            opfsMoveFile(pathA, pathB).isRight() && contentEquals(pathB, a) && !exists(pathA)
        })
        facts = facts.copy(copyVerifyVerified = step("copyVerify") {
            put(pathC, c)
            opfsCopyVerify(pathC, pathB).isRight() && contentEquals(pathB, c) && !exists(pathC)
        })
        facts = facts.copy(sliceRead = step("slice") {
            opfsReadSlice(pathB, SLICE_BYTES)?.contentEquals(c.copyOf(SLICE_BYTES)) == true
        })
        return facts
    }

    private suspend fun step(name: String, body: suspend () -> Boolean): Boolean = try {
        beforeStep(name)
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Throwable) {
        false
    }

    private suspend fun removeStaleProbeDirectories() {
        val now = nowMillis()
        val stale = listOpfsEntries(getOpfsRoot()).filter { isDirectoryEntry(it) }.map { getEntryName(it) }
            .filter { OpfsProbeDirectory.isStale(it, now) }
        for (name in stale) opfsDeleteDirectoryRecursive("/$name")
    }

    private suspend fun contentEquals(path: String, expected: ByteArray): Boolean =
        opfsReadSlice(path, expected.size + 1)?.contentEquals(expected) == true

    private suspend fun exists(path: String): Boolean = opfsReadSlice(path, 1) != null

    private fun payload(seed: Int): ByteArray = ByteArray(OpfsProbeDirectory.MAX_FILE_BYTES) { ((it * PAYLOAD_STRIDE + seed) and BYTE_MASK).toByte() }

    private companion object {
        const val SLICE_BYTES = 16
        const val PAYLOAD_STRIDE = 31
        const val BYTE_MASK = 0xFF
    }
}
