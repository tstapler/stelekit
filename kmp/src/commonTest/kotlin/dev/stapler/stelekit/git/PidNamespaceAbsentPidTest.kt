package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals

class PidNamespaceAbsentPidTest {
    private val ns = "pid:[4026531836]"
    private val self = testRecord(instance = "self", pid = 1, pidNs = ns, bootId = "boot-1")

    /** Adapter stand-in: the policy applied to an absent pid, with this process's namespace as [selfNs]. */
    private fun absentPidVerdict(recordNs: String?, selfNs: String?): LockVerdict =
        LockRecovery.decide(
            RecordRead(testRecord(pidNs = recordNs, bootId = "boot-1").encode(), 1),
            RecordingLockFiles(),
            { _, ticks, pidNs, _ -> LivenessPolicy.judge(StartStampBasis.ProcTicks, ticks, pidNs, selfNs, ProbeResult.Absent) },
            self,
        )

    @Test
    fun decide_should_ReturnOwnerUnknown_When_PidAbsentButPidNamespaceDiffersOrUnreadable() {
        val unknown = LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown)
        assertEquals(unknown, absentPidVerdict(recordNs = "pid:[999]", selfNs = ns))
        assertEquals(unknown, absentPidVerdict(recordNs = null, selfNs = ns))
        assertEquals(unknown, absentPidVerdict(recordNs = ns, selfNs = null))
        assertEquals(unknown, absentPidVerdict(recordNs = null, selfNs = null))
    }

    @Test
    fun judge_should_ReturnDead_When_PidAbsentOnAPlatformWithoutPidNamespaces() {
        // macOS/Windows: neither side has a namespace, and decide() has already required the same host.
        assertEquals(Liveness.Dead, LivenessPolicy.judge(StartStampBasis.InstantMs, 5L, null, null, ProbeResult.Absent))
        assertEquals(Liveness.Dead, LivenessPolicy.judge(StartStampBasis.InstantMs, 5L, null, null, ProbeResult.Present(999_999L)))
        assertEquals(Liveness.Alive, LivenessPolicy.judge(StartStampBasis.InstantMs, 5_000L, null, null, ProbeResult.Present(5_500L)))
        // Linux with unreadable namespaces still proves nothing.
        assertEquals(Liveness.Unknown, LivenessPolicy.judge(StartStampBasis.ProcTicks, 5L, null, null, ProbeResult.Absent))
    }

    @Test
    fun decide_should_ReturnClearedDeadOwner_When_PidAbsentInSameNonNullNamespace() {
        assertEquals(LockVerdict.ClearedDeadOwner, absentPidVerdict(recordNs = ns, selfNs = ns))
    }
}
