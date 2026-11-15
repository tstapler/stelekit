package dev.stapler.stelekit.git

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockStepStartTimeTest {
    private val ns = "pid:[4026531836]"

    @Test
    fun liveness_should_NotReportDead_When_StartInstantDiffersOnlyByWallClockStep() = runTest {
        // Same process, same raw ticks; each JVM derived its instant from a different boot-time estimate (NTP step).
        checkAll(Arb.long(1L..10_000_000_000L), Arb.long(-86_400_000L..86_400_000L)) { ticks, wallClockSkewMs ->
            val recordedInstant = 1_700_000_000_000L + ticks * 10
            val observedInstant = recordedInstant + wallClockSkewMs

            val byTicks = LivenessPolicy.judge(StartStampBasis.ProcTicks, ticks, ns, ns, ProbeResult.Present(ticks))
            assertEquals(Liveness.Alive, byTicks, "equal raw ticks must be Alive regardless of the wall clock")

            val byInstant = LivenessPolicy.judge(StartStampBasis.InstantMs, recordedInstant, ns, ns, ProbeResult.Present(observedInstant))
            val expected = if (kotlin.math.abs(wallClockSkewMs) <= LivenessPolicy.INSTANT_TOLERANCE_MS) Liveness.Alive else Liveness.Dead
            assertEquals(expected, byInstant)
        }
    }

    @Test
    fun procTicks_should_ReportDead_When_TicksDifferInSameNamespace() {
        val result = LivenessPolicy.judge(StartStampBasis.ProcTicks, 100, ns, ns, ProbeResult.Present(101))
        assertEquals(Liveness.Dead, result)
    }

    @Test
    fun differingTicks_should_BeUnknown_When_NamespaceDiffersOrIsNull() = runTest {
        checkAll(Arb.long(1L..1_000_000L), Arb.string(0..8)) { ticks, other ->
            val differ = LivenessPolicy.judge(StartStampBasis.ProcTicks, ticks, ns, "other-$other", ProbeResult.Present(ticks + 1))
            assertEquals(Liveness.Unknown, differ)
            val nullNs = LivenessPolicy.judge(StartStampBasis.ProcTicks, ticks, null, null, ProbeResult.Present(ticks + 1))
            assertEquals(Liveness.Unknown, nullNs)
        }
    }

    @Test
    fun absentPid_should_BeDeadOnlyInSameNonNullNamespace() {
        assertEquals(Liveness.Dead, LivenessPolicy.judge(StartStampBasis.ProcTicks, 5, ns, ns, ProbeResult.Absent))
        assertEquals(Liveness.Unknown, LivenessPolicy.judge(StartStampBasis.ProcTicks, 5, ns, "pid:[1]", ProbeResult.Absent))
        assertEquals(Liveness.Unknown, LivenessPolicy.judge(StartStampBasis.ProcTicks, 5, null, null, ProbeResult.Absent))
        assertEquals(Liveness.Unknown, LivenessPolicy.judge(StartStampBasis.ProcTicks, 5, ns, null, ProbeResult.Absent))
    }

    @Test
    fun unreadableOrUnrecordedStamp_should_BeUnknown() {
        assertEquals(Liveness.Unknown, LivenessPolicy.judge(StartStampBasis.ProcTicks, 5, ns, ns, ProbeResult.Unreadable))
        assertEquals(Liveness.Unknown, LivenessPolicy.judge(StartStampBasis.ProcTicks, null, ns, ns, ProbeResult.Present(5)))
    }

    @Test
    fun record_should_RoundTrip_And_TreatTornTextAsTorn() {
        val record = LockOwnerRecord(InstanceId("i"), 42, 777, ns, "h", null, "sync", "UiPress", 123)
        assertEquals(RecordDecode.Valid(record), LockOwnerRecord.decode(record.encode()))
        assertEquals(RecordDecode.Torn, LockOwnerRecord.decode(""))
        assertEquals(RecordDecode.Torn, LockOwnerRecord.decode("   "))
        assertEquals(RecordDecode.Torn, LockOwnerRecord.decode(record.encode().dropLast(10)))
        assertEquals(RecordDecode.Torn, LockOwnerRecord.decode("not json"))
        val withExtra = record.encode().dropLast(1) + ",\"futureField\":1}"
        assertEquals(RecordDecode.Valid(record), LockOwnerRecord.decode(withExtra))
    }
}
