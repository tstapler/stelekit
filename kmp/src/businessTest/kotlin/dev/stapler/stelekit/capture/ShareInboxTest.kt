package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [FileSystem] with directories, JVM-like non-overwriting rename, and crash injection. */
internal class InboxFakeFileSystem(private val now: () -> Long) : FileSystem {
    class SimulatedCrash : RuntimeException("simulated crash")

    private val files = mutableMapOf<String, ByteArray>()
    private val mtimes = mutableMapOf<String, Long>()
    private val dirs = mutableSetOf<String>()

    /** Called after each mutating op completes; return true to throw [SimulatedCrash]. */
    var crashAfter: ((op: String, path: String) -> Boolean)? = null

    private fun done(op: String, path: String) {
        if (crashAfter?.invoke(op, path) == true) throw SimulatedCrash()
    }

    fun allFiles(): Set<String> = files.keys.toSet()
    fun bytes(path: String): ByteArray? = files[path]
    fun put(path: String, text: String, modified: Long = now()) {
        files[path] = text.encodeToByteArray(); mtimes[path] = modified
    }

    override fun getDefaultGraphPath() = "/g"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = files[path]?.decodeToString()
    override fun readFileBytes(path: String): ByteArray? = files[path]
    override fun writeFile(path: String, content: String): Boolean {
        put(path, content); done("write", path); return true
    }
    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        files[path] = data; mtimes[path] = now(); done("write", path); return true
    }
    override fun listFiles(path: String): List<String> =
        files.keys.filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
            .map { it.removePrefix("$path/") }.sorted()
    override fun listDirectories(path: String): List<String> =
        (dirs + files.keys.map { it.substringBeforeLast('/') }).filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }.distinct().sorted()
    override fun fileExists(path: String) = path in files
    override fun directoryExists(path: String) = path in dirs || files.keys.any { it.startsWith("$path/") }
    override fun createDirectory(path: String): Boolean { dirs += path; return true }
    override fun deleteFile(path: String): Boolean {
        files.remove(path); mtimes.remove(path); done("delete", path); return true
    }
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = mtimes[path]
    override fun renameFile(from: String, to: String): Boolean {
        if (directoryExists(from) && from !in files) {
            if (directoryExists(to)) return true
            for (k in files.keys.filter { it.startsWith("$from/") }) {
                val nk = to + k.removePrefix(from)
                files[nk] = files.remove(k)!!; mtimes[nk] = mtimes.remove(k) ?: now()
            }
            dirs.remove(from); dirs += to
            done("rename", to); return true
        }
        if (from !in files) return false
        if (to in files) return true
        files[to] = files.remove(from)!!; mtimes[to] = mtimes.remove(from) ?: now()
        done("rename", to); return true
    }
}

class ShareInboxTest {
    private val root = "/data/share-inbox"
    private val work = GraphId("workgraph0000001")
    private val slot = InboxSlot.Graph(work)
    private var time = 1_000_000_000L
    private val fs = InboxFakeFileSystem { time }

    private fun inbox() = ShareInbox(fs, root, { time }, Dispatchers.Unconfined)

    private fun drainWith(
        inbox: ShareInbox,
        ready: MutableStateFlow<GraphId?>,
        appends: MutableList<Pair<String, String>>,
        migration: suspend () -> Unit = {},
        result: () -> DrainAppendResult = { DrainAppendResult.Appended },
        registered: MutableStateFlow<List<GraphId>>? = null,
    ) = ShareInboxDrain(
        inbox, ready, migration, currentReadyId = { ready.value },
        appender = InboxAppender { g, c, id -> appends += g.value to "$id:${c.text}"; result() },
        registeredGraphs = registered, dispatcher = Dispatchers.Default,
    )

    private fun <T> waitFor(block: suspend () -> T): T = runBlocking {
        withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
    }

    private fun ShareInbox.awaitState(cond: (ShareInboxState) -> Boolean) = waitFor { state.first(cond) }

    private fun newItemPath(id: String) = "$root/${work.value}/$id.json"

    private fun enqueue(i: ShareInbox, text: String, id: String, image: ByteArray? = null) =
        runBlocking { i.enqueue(slot, ShareContent(text, image), id) }

    @Test
    fun `enqueue survives restart and drains exactly once, twice-ready does not duplicate`() {
        enqueue(inbox(), "x", "c9").getOrNullOrFail()
        val restarted = inbox()
        val ready = MutableStateFlow<GraphId?>(null)
        val appends = mutableListOf<Pair<String, String>>()
        val drain = drainWith(restarted, ready, appends)
        drain.start()
        restarted.awaitState { it.items.size == 1 }
        assertEquals(1, restarted.state.value.pendingCount(work))
        ready.value = work
        restarted.awaitState { it.items.isEmpty() }
        ready.value = null
        ready.value = work
        Thread.sleep(200)
        drain.close()
        assertEquals(listOf(work.value to "c9:x"), appends)
        assertFalse(fs.allFiles().any { it.endsWith(".json") })
    }

    @Test
    fun `AlreadyPresent counts as drained`() {
        enqueue(inbox(), "x", "c1")
        val i = inbox()
        val ready = MutableStateFlow<GraphId?>(work)
        val drain = drainWith(i, ready, mutableListOf(), result = { DrainAppendResult.AlreadyPresent })
        drain.start()
        i.awaitState { it.items.isEmpty() }
        drain.close()
    }

    @Test
    fun `drain waits for migration and ignores other graphs`() {
        val other = GraphId("othergraph000001")
        enqueue(inbox(), "w", "c1")
        runBlocking { inbox().enqueue(InboxSlot.Graph(other), ShareContent("o"), "c2") }
        val i = inbox()
        val gate = CompletableDeferred<Unit>()
        val ready = MutableStateFlow<GraphId?>(work)
        val appends = mutableListOf<Pair<String, String>>()
        val drain = drainWith(i, ready, appends, migration = { gate.await() })
        drain.start()
        i.awaitState { it.items.size == 2 }
        Thread.sleep(150)
        assertTrue(appends.isEmpty(), "must not append before migration returns")
        gate.complete(Unit)
        i.awaitState { it.items.size == 1 }
        drain.close()
        assertEquals(listOf(work.value to "c1:w"), appends)
        assertEquals(1, i.state.value.pendingCount(other))
    }

    @Test
    fun `retry result keeps item and exposes last error, retryNow drains it`() {
        enqueue(inbox(), "x", "c1")
        val i = inbox()
        val ready = MutableStateFlow<GraphId?>(work)
        var res: DrainAppendResult = DrainAppendResult.Retry("locked")
        val appends = mutableListOf<Pair<String, String>>()
        val drain = drainWith(i, ready, appends, result = { res })
        drain.start()
        i.awaitState { it.items.singleOrNull()?.lastError == "locked" }
        res = DrainAppendResult.Appended
        assertEquals(RetryResult.Drained, waitFor { drain.retryNow(slot, "c1") })
        drain.close()
        assertTrue(i.state.value.items.isEmpty())
        assertEquals(RetryResult.NotFound, waitFor { drain.retryNow(slot, "zz") })
    }

    @Test
    fun `crash after tmp write leaves no item and redelivery recovers the text once`() {
        fs.crashAfter = { op, p -> op == "write" && p.endsWith(".json.tmp") }
        assertTrue(enqueue(inbox(), "hello", "c1").isLeft())
        fs.crashAfter = null
        val restarted = inbox()
        runBlocking { restarted.recover() }
        assertTrue(restarted.state.value.items.isEmpty())
        assertEquals(EnqueueOutcome.Queued, enqueue(restarted, "hello", "c1").getOrNullOrFail())
        assertDrainsOnce(restarted, "c1:hello")
    }

    @Test
    fun `crash after rename keeps the item and redelivery is AlreadyQueued`() {
        fs.crashAfter = { op, p -> op == "rename" && p.endsWith(".json") }
        assertTrue(enqueue(inbox(), "hello", "c1").isLeft())
        fs.crashAfter = null
        val restarted = inbox()
        runBlocking { restarted.recover() }
        assertEquals("hello", restarted.state.value.items.single().text)
        assertEquals(EnqueueOutcome.AlreadyQueued, enqueue(restarted, "hello", "c1").getOrNullOrFail())
        assertDrainsOnce(restarted, "c1:hello")
    }

    @Test
    fun `crash mid-image leaves an orphan image never a dangling reference`() {
        val img = byteArrayOf(1, 2, 3, 4)
        for (stage in listOf(".img.tmp" to "write", ".img" to "rename")) {
            val f = InboxFakeFileSystem { time }
            val i = ShareInbox(f, root, { time }, Dispatchers.Unconfined)
            f.crashAfter = { op, p -> op == stage.second && p.endsWith(stage.first) }
            assertTrue(runBlocking { i.enqueue(slot, ShareContent("pic", img), "c1") }.isLeft())
            f.crashAfter = null
            assertFalse(f.allFiles().any { it.endsWith(".json") }, "json must not exist before its image is in place")
            val r = ShareInbox(f, root, { time }, Dispatchers.Unconfined)
            runBlocking { r.recover() }
            assertEquals(EnqueueOutcome.Queued, runBlocking { r.enqueue(slot, ShareContent("pic", img), "c1") }.getOrNullOrFail())
            val content = runBlocking { r.readContent(slot, "c1") }.getOrNullOrFail()
            assertEquals("pic", content.text)
            assertTrue(img.contentEquals(content.image))
        }
    }

    @Test
    fun `damaged image fails readContent but text stays copyable`() {
        enqueue(inbox(), "pic", "c1", byteArrayOf(9, 9))
        fs.put("$root/${work.value}/c1.img", "tampered")
        val i = inbox()
        assertTrue(runBlocking { i.readContent(slot, "c1") }.isLeft())
        assertEquals("pic", runBlocking { i.copyText(slot, "c1") })
    }

    @Test
    fun `corrupted checksum is quarantined not deleted and text is recoverable`() {
        enqueue(inbox(), "precious", "c1")
        val path = newItemPath("c1")
        fs.put(path, fs.readFile(path)!!.replace("precious", "precioux"))
        val i = inbox()
        runBlocking { i.recover() }
        assertTrue(i.state.value.items.isEmpty())
        val q = i.state.value.quarantined.single()
        assertEquals("precioux", q.recoverableText)
        assertTrue(fs.fileExists("$root/_quarantine/${q.fileName}"))
        assertFalse(fs.fileExists(path))
        assertEquals("precioux", runBlocking { i.quarantinedText(q.fileName) })
    }

    @Test
    fun `truncated JSON is quarantined and text salvaged`() {
        enqueue(inbox(), "line one\nwith \"quotes\"", "c1")
        val path = newItemPath("c1")
        val full = fs.readFile(path)!!
        fs.put(path, full.substring(0, full.indexOf("\"sha256\"") - 1))
        val i = inbox()
        runBlocking { i.recover() }
        val q = i.state.value.quarantined.single()
        assertEquals("line one\nwith \"quotes\"", q.recoverableText)
        assertEquals(0, i.state.value.items.size)
        runBlocking { i.discardQuarantined(q.fileName) }
        assertEquals(0, i.state.value.quarantinedCount)
    }

    @Test
    fun `future version is kept untouched and never drained`() {
        val path = newItemPath("c1")
        val body = """{"v":2,"captureId":"c1","graphKey":"x","createdAtEpochMs":5,"payload":{"body":"new schema"},"sha256":"zz"}"""
        fs.put(path, body)
        val i = inbox()
        val appends = mutableListOf<Pair<String, String>>()
        val drain = drainWith(i, MutableStateFlow(work), appends)
        drain.start()
        i.awaitState { it.items.size == 1 }
        Thread.sleep(150)
        drain.close()
        assertEquals(1, i.state.value.needsNewerAppCount)
        assertEquals(InboxItemStatus.NeedsNewerApp(2), i.state.value.items.single().status)
        assertTrue(appends.isEmpty())
        assertEquals(body, fs.readFile(path))
        assertEquals(0, i.state.value.quarantinedCount)
    }

    @Test
    fun `rescue discard removes the item and copyText works for a vanished graph`() {
        enqueue(inbox(), "keep me", "c1")
        val i = inbox()
        runBlocking { i.recover() }
        assertEquals("keep me", runBlocking { i.copyText(slot, "c1") })
        assertTrue(runBlocking { i.discard(slot, "c1") }.isRight())
        assertTrue(i.state.value.items.isEmpty())
        assertNull(runBlocking { i.copyText(slot, "c1") })
    }

    @Test
    fun `tmp sweep removes only tmp files older than one hour`() {
        val dir = "$root/${work.value}"
        fs.put("$dir/old.json.tmp", "x", modified = time - 2 * 3_600_000L)
        fs.put("$dir/new.json.tmp", "x", modified = time - 60_000L)
        fs.put("$dir/old.json", "{}", modified = time - 9 * 3_600_000L)
        runBlocking { inbox().recover() }
        assertFalse(fs.fileExists("$dir/old.json.tmp"))
        assertTrue(fs.fileExists("$dir/new.json.tmp"))
        assertTrue(fs.fileExists("$dir/old.json") || fs.fileExists("$root/_quarantine/${work.value}__old.json"))
    }

    @Test
    fun `non-token graph key and captureId are rejected`() {
        val i = inbox()
        assertTrue(runBlocking { i.enqueue(slot, ShareContent("x"), "../evil") }.isLeft())
        assertTrue(runBlocking { i.enqueue(slot, ShareContent("x"), "a/b") }.isLeft())
        assertTrue(runBlocking { i.enqueue(InboxSlot.Graph(GraphId("../x")), ShareContent("x"), "c1") }.isLeft())
        assertTrue(runBlocking { i.enqueue(InboxSlot.Graph(GraphId("_unassigned")), ShareContent("x"), "c1") }.isLeft())
        assertTrue(fs.allFiles().isEmpty())
    }

    @Test
    fun `unassigned items are re-keyed to the first graph once and drain once`() {
        runBlocking { inbox().enqueue(InboxSlot.Unassigned, ShareContent("early", byteArrayOf(7)), "c1") }
        assertTrue(fs.fileExists("$root/_unassigned/c1.json"))
        val i = inbox()
        val registered = MutableStateFlow<List<GraphId>>(emptyList())
        val ready = MutableStateFlow<GraphId?>(null)
        val appends = mutableListOf<Pair<String, String>>()
        val drain = drainWith(i, ready, appends, registered = registered)
        drain.start()
        i.awaitState { it.unassignedCount == 1 }
        registered.value = listOf(work)
        i.awaitState { it.pendingCount(work) == 1 && it.unassignedCount == 0 }
        ready.value = work
        i.awaitState { it.items.isEmpty() }
        drain.close()
        assertEquals(listOf(work.value to "c1:early"), appends)
    }

    @Test
    fun `re-key crash mid per-file move completes on rerun without duplicates`() {
        val unassigned = InboxSlot.Unassigned
        runBlocking {
            inbox().enqueue(unassigned, ShareContent("a", byteArrayOf(1)), "c1")
            inbox().enqueue(slot, ShareContent("existing"), "c0")
        }
        fs.crashAfter = { op, p -> op == "rename" && p.endsWith("c1.img") }
        assertTrue(runBlocking { inbox().rekeyUnassigned(work) }.isLeft())
        fs.crashAfter = null
        assertFalse(fs.fileExists("$root/${work.value}/c1.json"))
        val r = inbox()
        assertEquals(1, runBlocking { r.rekeyUnassigned(work) }.getOrNullOrFail())
        assertEquals(0, runBlocking { r.rekeyUnassigned(work) }.getOrNullOrFail())
        assertEquals(setOf("c0", "c1"), r.state.value.items.map { it.captureId }.toSet())
        assertEquals(0, r.state.value.unassignedCount)
        assertEquals("a", runBlocking { r.readContent(slot, "c1") }.getOrNullOrFail().text)
    }

    @Test
    fun `re-key crash after directory rename loses nothing`() {
        runBlocking { inbox().enqueue(InboxSlot.Unassigned, ShareContent("a"), "c1") }
        fs.crashAfter = { op, _ -> op == "rename" }
        assertTrue(runBlocking { inbox().rekeyUnassigned(work) }.isLeft())
        fs.crashAfter = null
        val r = inbox()
        assertEquals(0, runBlocking { r.rekeyUnassigned(work) }.getOrNullOrFail())
        runBlocking { r.recover() }
        assertEquals("a", r.state.value.items.single().text)
        assertDrainsOnce(r, "c1:a")
    }

    private fun assertDrainsOnce(i: ShareInbox, expected: String) {
        val ready = MutableStateFlow<GraphId?>(work)
        val appends = mutableListOf<Pair<String, String>>()
        val drain = drainWith(i, ready, appends)
        drain.start()
        i.awaitState { it.items.isEmpty() }
        ready.value = null
        ready.value = work
        Thread.sleep(150)
        drain.close()
        assertEquals(listOf(work.value to expected), appends)
    }

    private fun <A, B> arrow.core.Either<A, B>.getOrNullOrFail(): B =
        fold({ throw AssertionError("expected Right but was Left($it)") }, { it })
}
