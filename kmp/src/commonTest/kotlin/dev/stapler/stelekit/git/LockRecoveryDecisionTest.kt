package dev.stapler.stelekit.git

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records every mutating call so tests can assert [LockRecovery.decide] stays read-only. */
internal class RecordingLockFiles(
    var locks: List<LockFileEntry> = emptyList(),
    var record: RecordRead? = null,
) : LockFiles {
    val mutations = mutableListOf<String>()

    override fun listAllowListedLocks(): List<LockFileEntry> = locks
    override fun readRecord(): RecordRead? = record
    override fun createRecordExclusive(content: String): Boolean { mutations += "create"; return false }
    override fun touchRecord(): Boolean { mutations += "touch"; return false }
    override fun deleteRecord() { mutations += "deleteRecord" }
    override fun delete(path: String) { mutations += "delete:$path" }
    override fun tryLockRecovery(): RecoveryLockHandle? { mutations += "lock"; return null }
}

internal fun testRecord(
    instance: String = "other",
    pid: Long = 4242,
    ticks: Long? = 1000,
    pidNs: String? = "pid:[1]",
    host: String = "host-a",
    bootId: String? = "boot-1",
    startedAtMs: Long = 1_000,
) = LockOwnerRecord(InstanceId(instance), pid, ticks, pidNs, host, bootId, "sync", "UiPress", startedAtMs)

class LockRecoveryDecisionTest {
    private val self = testRecord(instance = "self", pid = 1)
    private val lock = LockFileEntry("index.lock", 5_000)

    private fun decide(
        record: LockOwnerRecord?,
        liveness: Liveness = Liveness.Unknown,
        locks: List<LockFileEntry> = listOf(lock),
        selfRecord: LockOwnerRecord = self,
    ): LockVerdict = LockRecovery.decide(
        record?.let { RecordRead(it.encode(), 9_000) },
        RecordingLockFiles(locks),
        { _, _, _, _ -> liveness },
        selfRecord,
    )

    @Test
    fun decide_should_NeverDeleteUnlessOwnOrDeadAndAllowListed_When_AnyInputCombination() = runTest {
        val paths = listOf(
            "index.lock", "shallow.lock", "HEAD.lock", "packed-refs.lock", "config.lock",
            "refs/heads/main.lock", "objects/pack/tmp_pack_x.lock", "foo.lock", "../index.lock", "refs/.lock",
        )
        val recordKinds = listOf("absent", "torn", "self", "otherSameHost", "otherDiffHost")
        checkAll(
            Arb.element(recordKinds),
            Arb.enum<Liveness>(),
            Arb.list(Arb.element(paths), 0..5),
            Arb.element(listOf("boot-1", "boot-2", null)),
            Arb.boolean(),
        ) { kind, liveness, lockPaths, otherBoot, sameBootSelf ->
            val selfRecord = self.copy(bootId = if (sameBootSelf) "boot-1" else null)
            val files = RecordingLockFiles(lockPaths.map { LockFileEntry(it, 5_000) })
            val read = when (kind) {
                "absent" -> null
                "torn" -> RecordRead("{\"instanceId", 1)
                "self" -> RecordRead(testRecord(instance = "self").encode(), 1)
                "otherSameHost" -> RecordRead(testRecord(bootId = otherBoot).encode(), 1)
                else -> RecordRead(testRecord(host = "host-b").encode(), 1)
            }

            val verdict = LockRecovery.decide(read, files, { _, _, _, _ -> liveness }, selfRecord)

            assertTrue(files.mutations.isEmpty(), "decide must be read-only, did ${files.mutations}")
            val deletable = LockRecovery.deletableLocks(verdict, files)
            if (!verdict.deletes) assertTrue(deletable.isEmpty(), "$verdict must delete nothing")
            assertTrue(deletable.all { LockAllowList.isAllowListed(it.path) }, "only allow-listed locks, got $deletable")
            assertTrue(deletable.none { it.path.startsWith("objects/") })
        }
    }

    @Test
    fun decide_should_ReturnClearedDeadOwner_When_PidDeadOrStartTicksDiffer() {
        assertEquals(LockVerdict.ClearedDeadOwner, decide(testRecord(), Liveness.Dead))
        assertEquals(LockVerdict.OwnerAlive, decide(testRecord(), Liveness.Alive))
    }

    @Test
    fun decide_should_ReturnClearedDeadOwner_When_SameHostBootIdDiffers() {
        val verdict = decide(testRecord(bootId = "boot-1"), Liveness.Alive, selfRecord = self.copy(bootId = "boot-2"))
        assertEquals(LockVerdict.ClearedDeadOwner, verdict, "a record from another boot is dead even if the pid looks alive")
    }

    @Test
    fun decide_should_ReturnOwnerUnknown_When_LivenessUnknownOrDifferentHostOrBootIdNull() {
        assertEquals(LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown), decide(testRecord(), Liveness.Unknown))
        assertEquals(
            LockVerdict.OwnerUnknown(UnknownReason.DifferentHost),
            decide(testRecord(host = "host-b", bootId = "boot-9"), Liveness.Dead, selfRecord = self.copy(bootId = "boot-2")),
        )
        // A null boot id on either side falls through to the probe, which here says Unknown.
        assertEquals(
            LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown),
            decide(testRecord(bootId = null), Liveness.Unknown, selfRecord = self.copy(bootId = "boot-2")),
        )
        assertEquals(
            LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown),
            decide(testRecord(bootId = "boot-1"), Liveness.Unknown, selfRecord = self.copy(bootId = null)),
        )
    }

    @Test
    fun decide_should_ReturnNoRecordAndDeleteNothing_When_LockHasNoRecord() {
        val files = RecordingLockFiles(listOf(lock))
        val verdict = LockRecovery.decide(null, files, { _, _, _, _ -> Liveness.Dead }, self)
        assertEquals(LockVerdict.NoRecord, verdict)
        assertTrue(LockRecovery.deletableLocks(verdict, files).isEmpty())
        assertTrue(files.mutations.isEmpty())
    }

    @Test
    fun decide_should_ReturnNoLock_When_NoRecordAndNoAllowListedLock() {
        assertEquals(LockVerdict.NoLock, decide(null, locks = emptyList()))
        assertEquals(LockVerdict.NoLock, decide(null, locks = listOf(LockFileEntry("objects/pack/tmp_pack_x.lock", 1))))
    }

    @Test
    fun decide_should_ReturnClearedOwn_When_RecordBelongsToThisInstance() {
        assertEquals(LockVerdict.ClearedOwn, decide(testRecord(instance = "self"), Liveness.Alive))
    }

    @Test
    fun allowList_should_AcceptRefsLocksOnly_And_RejectObjectsAndTraversal() {
        assertTrue(LockAllowList.isAllowListed("refs/heads/main.lock"))
        assertTrue(LockAllowList.isAllowListed("refs/remotes/origin/main.lock"))
        assertTrue(!LockAllowList.isAllowListed("objects/pack/tmp_pack_x.lock"))
        assertTrue(!LockAllowList.isAllowListed("refs/heads/main"))
        assertTrue(!LockAllowList.isAllowListed("refs/../objects/x.lock"))
        assertTrue(!LockAllowList.isAllowListed("/index.lock"))
        assertTrue(!LockAllowList.isAllowListed("modules/x/index.lock"))
    }
}
