package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.platform.diagnostics.ExitRecord
import dev.stapler.stelekit.platform.diagnostics.PostKillBannerController
import dev.stapler.stelekit.platform.diagnostics.PostKillNotice
import dev.stapler.stelekit.platform.diagnostics.PreviousSessionAssessor
import dev.stapler.stelekit.platform.diagnostics.SeenExitRecordStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PostKillBannerControllerTest {
    private class MemoryStore(var seen: String? = null) : SeenExitRecordStore {
        override fun lastSeenId() = seen
        override fun markSeen(id: String) { seen = id }
    }

    private class BrokenStore(private val failRead: Boolean, private val failWrite: Boolean) : SeenExitRecordStore {
        override fun lastSeenId(): String? = if (failRead) error("read failed") else null
        override fun markSeen(id: String) { if (failWrite) error("write failed") }
    }

    private fun record(reason: Int = 9, ts: Long = 1_000) =
        ExitRecord(reason, 400, 0, 100_000, 120_000, ts, "excessive cpu")

    private val editorOpenTail = listOf("12:00 INFO x", "12:01 INFO DiagnosticsSample cpuMsDelta=5 threads=main:1")
    private val editorClosedTail = editorOpenTail + "12:02 INFO DiagnosticsSample stopped"

    @Test
    fun banner_should_ShowOnceForRecordAndNotRepeat_When_ExitRecordPresent() {
        val store = MemoryStore()
        val notice = PostKillNotice(record().id)

        val first = PostKillBannerController(store).also { it.offer(notice) }
        assertEquals(notice, first.notice.value)

        first.dismiss()
        assertNull(first.notice.value)

        val relaunch = PostKillBannerController(store).also { it.offer(notice) }
        assertNull(relaunch.notice.value)
    }

    @Test
    fun banner_should_ShowAgain_When_NewRecordId() {
        val store = MemoryStore(seen = record(ts = 1).id)

        val controller = PostKillBannerController(store).also { it.offer(PostKillNotice(record(ts = 2).id)) }

        assertNotNull(controller.notice.value)
    }

    @Test
    fun banner_should_NotShowAndNotThrow_When_StoreReadFails() {
        val controller = PostKillBannerController(BrokenStore(failRead = true, failWrite = false))

        controller.offer(PostKillNotice("x"))

        assertNull(controller.notice.value)
    }

    @Test
    fun banner_should_StillShow_When_PersistingSeenIdFails() {
        val controller = PostKillBannerController(BrokenStore(failRead = false, failWrite = true))

        controller.offer(PostKillNotice("x"))

        assertNotNull(controller.notice.value)
    }

    @Test
    fun assess_should_NoticeAbnormalExit_When_EditorWasOpen() {
        assertEquals(PostKillNotice(record().id), PreviousSessionAssessor.assess(listOf(record()), editorOpenTail, null))
    }

    @Test
    fun assess_should_BeNull_When_EditorWasClosedBeforeExit() {
        assertNull(PreviousSessionAssessor.assess(listOf(record()), editorClosedTail, null))
    }

    @Test
    fun assess_should_BeNull_When_NoSamplesWereEverWritten() {
        assertNull(PreviousSessionAssessor.assess(listOf(record()), listOf("12:00 INFO x"), null))
    }

    @Test
    fun assess_should_BeNull_When_ExitWasUserRequested() {
        assertNull(PreviousSessionAssessor.assess(listOf(record(reason = 10)), editorOpenTail, null))
    }

    @Test
    fun assess_should_UseCrashFile_When_NoExitRecordsBelowApi30() {
        assertEquals(PostKillNotice("crash:7"), PreviousSessionAssessor.assess(emptyList(), editorOpenTail, "crash:7"))
        assertNull(PreviousSessionAssessor.assess(emptyList(), editorOpenTail, null))
    }
}
