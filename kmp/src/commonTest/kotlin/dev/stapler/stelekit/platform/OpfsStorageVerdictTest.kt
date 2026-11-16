package dev.stapler.stelekit.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpfsStorageVerdictTest {
    private class Row(val facts: OpfsProbeFacts, val expected: OpfsStorageVerdict)

    private fun row(root: Boolean, create: Boolean, move: Boolean, copy: Boolean, slice: Boolean, expected: OpfsStorageVerdict) =
        Row(OpfsProbeFacts(root, create, move, copy, slice), expected)

    // Written out by hand, one row per combination of (root, createWritable, move, copyVerify, slice).
    private val truthTable = listOf(
        row(false, false, false, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, false, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, false, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, false, true, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, true, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, true, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, true, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, false, true, true, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, false, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, false, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, false, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, false, true, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, true, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, true, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, true, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(false, true, true, true, true, OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)),
        row(true, false, false, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, false, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, false, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, false, true, true, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, true, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, true, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, true, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, false, true, true, true, OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)),
        row(true, true, false, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoAtomicReplace)),
        row(true, true, false, false, true, OpfsStorageVerdict.Unavailable(StorageReason.NoAtomicReplace)),
        row(true, true, false, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoSliceRead)),
        row(true, true, false, true, true, OpfsStorageVerdict.Available(ReplacePath.CopyVerify)),
        row(true, true, true, false, false, OpfsStorageVerdict.Unavailable(StorageReason.NoSliceRead)),
        row(true, true, true, false, true, OpfsStorageVerdict.Available(ReplacePath.Move)),
        row(true, true, true, true, false, OpfsStorageVerdict.Unavailable(StorageReason.NoSliceRead)),
        row(true, true, true, true, true, OpfsStorageVerdict.Available(ReplacePath.Move)),
    )

    @Test
    fun decide_should_MatchTheTruthTable_ForAll32Combinations() {
        assertEquals(32, truthTable.size)
        assertEquals(32, truthTable.map { it.facts }.toSet().size)
        for (r in truthTable) assertEquals(r.expected, OpfsStorageVerdict.decide(r.facts), "facts=${r.facts}")
    }

    @Test
    fun truthTable_should_HaveTheDocumentedRowCounts() {
        val byKind = truthTable.groupingBy { it.expected }.eachCount()
        assertEquals(16, byKind[OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs)])
        assertEquals(8, byKind[OpfsStorageVerdict.Unavailable(StorageReason.NoCreateWritable)])
        assertEquals(2, byKind[OpfsStorageVerdict.Unavailable(StorageReason.NoAtomicReplace)])
        assertEquals(3, byKind[OpfsStorageVerdict.Unavailable(StorageReason.NoSliceRead)])
        assertEquals(2, byKind[OpfsStorageVerdict.Available(ReplacePath.Move)])
        assertEquals(1, byKind[OpfsStorageVerdict.Available(ReplacePath.CopyVerify)])
    }

    @Test
    fun decide_should_BeNotProbed_When_NoProbeRan() {
        assertEquals(OpfsStorageVerdict.NotProbed, OpfsStorageVerdict.decide(null))
    }

    @Test
    fun decide_should_PreferMove_When_BothReplacePathsAreVerified() {
        val v = OpfsStorageVerdict.decide(OpfsProbeFacts(true, true, true, true, true))
        assertIs<OpfsStorageVerdict.Available>(v)
        assertEquals(ReplacePath.Move, v.replacePath)
    }

    private val available = OpfsStorageVerdict.Available(ReplacePath.Move)

    @Test
    fun status_should_BeChecking_While_VerdictIsNotProbed() {
        assertEquals(V2StorageStatus.Checking, V2StorageStatusReducer.reduce(OpfsStorageVerdict.NotProbed, PreloadState.Ok(), 0))
        assertEquals(V2StorageStatus.Checking, V2StorageStatusReducer.reduce(OpfsStorageVerdict.NotProbed, PreloadState.NotStarted, 9_999))
    }

    @Test
    fun status_should_BeChecking_While_PreloadIsPending() {
        for (p in listOf(PreloadState.NotStarted, PreloadState.Loading)) {
            assertEquals(V2StorageStatus.Checking, V2StorageStatusReducer.reduce(available, p, 9_999))
        }
    }

    @Test
    fun status_should_BeCheckTimedOut_After10Seconds() {
        val timedOut = V2StorageStatus.Unavailable(StorageReason.CheckTimedOut)
        assertEquals(timedOut, V2StorageStatusReducer.reduce(available, PreloadState.Loading, 10_000))
        assertEquals(timedOut, V2StorageStatusReducer.reduce(OpfsStorageVerdict.NotProbed, PreloadState.NotStarted, 10_000))
    }

    @Test
    fun status_should_BeReady_OnlyForAvailableVerdictAndOkPreload() {
        assertEquals(
            V2StorageStatus.Ready(sessionOnly = true, privateSuspected = true),
            V2StorageStatusReducer.reduce(available, PreloadState.Ok(100), 200, sessionOnly = true, privateSuspected = true),
        )
    }

    @Test
    fun status_should_BeUnavailableWithTheVerdictReason_Immediately() {
        assertEquals(
            V2StorageStatus.Unavailable(StorageReason.NoOpfs),
            V2StorageStatusReducer.reduce(OpfsStorageVerdict.Unavailable(StorageReason.NoOpfs), PreloadState.Loading, 0),
        )
    }

    @Test
    fun status_should_ReportAFailedPreloadReason() {
        assertEquals(
            V2StorageStatus.Unavailable(StorageReason.PreloadFailed),
            V2StorageStatusReducer.reduce(available, PreloadState.Failed(StorageReason.PreloadFailed, 50), 50),
        )
        assertEquals(
            V2StorageStatus.Unavailable(StorageReason.PreloadTooLarge),
            V2StorageStatusReducer.reduce(available, PreloadState.Failed(StorageReason.PreloadTooLarge, 50), 50),
        )
    }

    @Test
    fun status_should_RecoverOnce_When_OkArrivesWithinSixtySecondsAfterTheTimeout() {
        assertEquals(
            V2StorageStatus.Ready(false, false),
            V2StorageStatusReducer.reduce(available, PreloadState.Ok(70_000), 70_000),
        )
    }

    @Test
    fun status_should_StayTimedOut_When_OkArrivesLaterThanSixtySecondsAfterTheTimeout() {
        assertEquals(
            V2StorageStatus.Unavailable(StorageReason.CheckTimedOut),
            V2StorageStatusReducer.reduce(available, PreloadState.Ok(70_001), 80_000),
        )
    }

    @Test
    fun status_should_IgnoreAFailedPreloadThatFinishesAfterTheTimeout() {
        assertEquals(
            V2StorageStatus.Unavailable(StorageReason.CheckTimedOut),
            V2StorageStatusReducer.reduce(available, PreloadState.Failed(StorageReason.PreloadFailed, 20_000), 30_000),
        )
    }
}
