package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InboxFallbackAppenderTest {
    private val graph = GraphId("bbbbbbbbbbbbbbbb")
    private val fs = InboxFakeFileSystem { 1_000L }
    private val inbox = ShareInbox(fs, "/private/share-inbox", { 1_000L }, Dispatchers.Unconfined)

    private class Scripted(var next: AppendOutcome) : OffGraphContentRoute {
        val seen = mutableListOf<Pair<ShareContent, String?>>()
        override suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?): AppendOutcome {
            seen += content to captureId
            return next
        }
    }

    @Test
    fun appendContent_should_EnqueueAndReturnQueued_When_DelegateDefers() = runTest {
        val delegate = Scripted(AppendOutcome.Deferred("permission"))
        val image = byteArrayOf(9, 8, 7)

        val outcome = InboxFallbackAppender(delegate, inbox).appendContent(graph, ShareContent("hi", image, "image/png"), "c-1")

        assertEquals(AppendOutcome.Queued("permission"), outcome)
        val queued = inbox.readContent(InboxSlot.Graph(graph), "c-1").getOrNull()
        assertEquals("hi", queued?.text)
        assertTrue(image.contentEquals(queued?.image), "image copied to app-private storage at enqueue time")
    }

    @Test
    fun appendContent_should_PassThroughWithoutQueuing_When_DelegateWrites() = runTest {
        val delegate = Scripted(AppendOutcome.AlreadyPresent)
        val appender = InboxFallbackAppender(delegate, inbox)

        assertEquals(AppendOutcome.AlreadyPresent, appender.appendContent(graph, ShareContent("hi"), "c-1"))
        delegate.next = AppendOutcome.Failed("Nothing to save")
        assertIs<AppendOutcome.Failed>(appender.appendContent(graph, ShareContent("hi"), "c-2"))

        assertEquals(0, inbox.state.value.pendingCount(graph))
    }

    @Test
    fun appendContent_should_GenerateOneCaptureId_ForDelegateAndQueue_When_NoneGiven() = runTest {
        val delegate = Scripted(AppendOutcome.Deferred("target-busy"))

        InboxFallbackAppender(delegate, inbox).appendContent(graph, ShareContent("hi"), null)

        val id = delegate.seen.single().second
        assertTrue(id != null && inbox.readContent(InboxSlot.Graph(graph), id).getOrNull() != null)
    }

    @Test
    fun appendContent_should_ReturnFailed_When_EnqueueFails() = runTest {
        val delegate = Scripted(AppendOutcome.Deferred("permission"))

        val outcome = InboxFallbackAppender(delegate, inbox).appendContent(graph, ShareContent("hi"), "../bad")

        assertIs<AppendOutcome.Failed>(outcome)
    }
}
