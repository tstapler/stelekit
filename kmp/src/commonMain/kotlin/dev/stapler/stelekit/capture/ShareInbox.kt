package dev.stapler.stelekit.capture

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.PageFileResolver
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/** Where a queued share lives: a graph's directory, or the pre-first-graph holding slot (ADR-004). */
sealed interface InboxSlot {
    val dirName: String

    data class Graph(val id: GraphId) : InboxSlot {
        override val dirName: String get() = id.value
    }

    data object Unassigned : InboxSlot {
        override val dirName: String = ShareInbox.UNASSIGNED_DIR
    }
}

/** A share to queue. [image] is copied to app-private storage at enqueue time. */
class ShareContent(val text: String, val image: ByteArray? = null, val imageMimeType: String? = null)

enum class EnqueueOutcome { Queued, AlreadyQueued }

sealed interface InboxItemStatus {
    data object Ready : InboxItemStatus

    /** Written by a newer app version; kept untouched, never drained or deleted. */
    data class NeedsNewerApp(val version: Int) : InboxItemStatus
}

/** One queued share as the UI sees it. [text] is null only when it cannot be recovered. */
data class InboxItem(
    val slot: InboxSlot,
    val captureId: String,
    val createdAtEpochMs: Long,
    val text: String?,
    val status: InboxItemStatus,
    val hasImage: Boolean,
    val lastError: String? = null,
)

/** A file moved to `_quarantine/` because it failed its checksum or could not be parsed. */
data class QuarantinedShare(val fileName: String, val recoverableText: String?)

data class ShareInboxState(
    val items: List<InboxItem> = emptyList(),
    val quarantined: List<QuarantinedShare> = emptyList(),
) {
    fun pendingCount(graph: GraphId): Int = items.count { it.slot == InboxSlot.Graph(graph) }
    val unassignedCount: Int get() = items.count { it.slot == InboxSlot.Unassigned }
    val needsNewerAppCount: Int get() = items.count { it.status is InboxItemStatus.NeedsNewerApp }
    val quarantinedCount: Int get() = quarantined.size
    val pendingByGraph: Map<GraphId, Int>
        get() = items.mapNotNull { (it.slot as? InboxSlot.Graph)?.id }.groupingBy { it }.eachCount()
}

@Serializable
private data class ImageRef(val sha256: String, val size: Int, val mime: String? = null)

@Serializable
private data class PayloadDto(val text: String, val image: ImageRef? = null)

/**
 * Durable, crash-safe queue of failed shares: one file per item, `<root>/<graphKey>/<captureId>.json`
 * (plan Task 4.4.1a, ADR-004). Writes go tmp-in-same-dir then rename; a bad item is quarantined,
 * never deleted. Items leave only via [remove] (after a successful append) or [discard] (user action).
 *
 * Rename caveat: uses [FileSystem.renameFile], which does not overwrite. Every final name is written
 * once (first write wins), so no overwriting rename is needed.
 *
 * @param root path of the `share-inbox` directory (app-private)
 */
@Suppress("TooManyFunctions") // one cohesive file store; splitting would scatter the crash-safety invariants
class ShareInbox(
    private val fs: FileSystem,
    private val root: String,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ioDispatcher: CoroutineDispatcher = PlatformDispatcher.IO,
) {
    private val logger = Logger("ShareInbox")
    private val mutex = Mutex()
    private val attemptErrors = mutableMapOf<Pair<String, String>, String>()
    private val _state = MutableStateFlow(ShareInboxState())
    val state: StateFlow<ShareInboxState> = _state.asStateFlow()

    suspend fun enqueue(slot: InboxSlot, content: ShareContent, captureId: String): Either<DomainError, EnqueueOutcome> =
        guarded {
            requireToken(slot, captureId)?.let { return@guarded it.left() }
            val dir = dirPath(slot)
            val jsonPath = "$dir/$captureId$JSON"
            if (fs.fileExists(jsonPath)) {
                when (readItem(slot, captureId)) {
                    is Read.Corrupt -> quarantine(slot, "$captureId$JSON")
                    else -> return@guarded EnqueueOutcome.AlreadyQueued.right()
                }
            }
            fs.createDirectory(root)
            fs.createDirectory(dir)
            var imageRef: ImageRef? = null
            content.image?.let { bytes ->
                val ref = ImageRef(ContentHasher.sha256(bytes), bytes.size, content.imageMimeType)
                writeAtomically("$dir/$captureId$IMG") { fs.writeFileBytes(it, bytes) }
                    ?: return@guarded writeFailed("$dir/$captureId$IMG", "image write failed").left()
                imageRef = ref
            }
            val payload = jsonCodec.encodeToJsonElement(PayloadDto.serializer(), PayloadDto(content.text, imageRef))
            val envelope = JsonObject(
                mapOf(
                    "v" to JsonPrimitive(VERSION),
                    "captureId" to JsonPrimitive(captureId),
                    "graphKey" to JsonPrimitive(slot.dirName),
                    "createdAtEpochMs" to JsonPrimitive(clock()),
                    "payload" to payload,
                    "sha256" to JsonPrimitive(ContentHasher.sha256(payload.toString())),
                ),
            )
            writeAtomically(jsonPath) { fs.writeFile(it, envelope.toString()) }
                ?: return@guarded writeFailed(jsonPath, "item write failed").left()
            refreshLocked()
            EnqueueOutcome.Queued.right()
        }

    /** Startup recovery: sweeps stale `.tmp` files (older than 1 h, never `.json`) and rebuilds [state]. */
    suspend fun recover(): Either<DomainError, Unit> = guarded {
        val cutoff = clock() - TMP_MAX_AGE_MS
        for (dir in fs.listDirectories(root).map(::leaf)) {
            val dirPath = "$root/$dir"
            if (!PageFileResolver.isWithin(root, dirPath)) continue
            for (name in fs.listFiles(dirPath).map(::leaf).filter { it.endsWith(TMP) }) {
                val modified = fs.getLastModifiedTime("$dirPath/$name") ?: continue
                if (modified < cutoff) fs.deleteFile("$dirPath/$name")
            }
        }
        refreshLocked()
        Unit.right()
    }

    suspend fun refresh(): Either<DomainError, Unit> = guarded { refreshLocked(); Unit.right() }

    /** Full payload for appending. Left when the item is unreadable, future-versioned, or its image is damaged. */
    suspend fun readContent(slot: InboxSlot, captureId: String): Either<DomainError, ShareContent> = guarded {
        requireToken(slot, captureId)?.let { return@guarded it.left() }
        when (val r = readItem(slot, captureId)) {
            is Read.Ok -> {
                val ref = r.payload.image
                if (ref == null) return@guarded ShareContent(r.payload.text).right()
                val imgPath = "${dirPath(slot)}/$captureId$IMG"
                val bytes = fs.readFileBytes(imgPath)
                if (bytes == null || ContentHasher.sha256(bytes) != ref.sha256) {
                    return@guarded DomainError.FileSystemError.ReadFailed(imgPath, "image missing or damaged").left()
                }
                ShareContent(r.payload.text, bytes, ref.mime).right()
            }
            is Read.Future -> DomainError.ValidationError.ConstraintViolation("needs a newer app version").left()
            is Read.Corrupt -> DomainError.FileSystemError.ReadFailed(dirPath(slot), r.reason).left()
            Read.Missing -> DomainError.FileSystemError.NotFound("${dirPath(slot)}/$captureId$JSON").left()
            Read.Unavailable -> DomainError.FileSystemError.ReadFailed(dirPath(slot), "unreadable right now").left()
        }
    }

    /** Rescue: the text to put on the clipboard. Works for any item, including damaged ones, and without the graph. */
    suspend fun copyText(slot: InboxSlot, captureId: String): String? = guarded {
        requireToken(slot, captureId)?.let { return@guarded it.left() }
        val text = when (val r = readItem(slot, captureId)) {
            is Read.Ok -> r.payload.text
            else -> fs.readFile("${dirPath(slot)}/$captureId$JSON")?.let(::salvageText)
        }
        text.right()
    }.getOrNull()

    /** Rescue: user-initiated delete (the UI confirms first). */
    suspend fun discard(slot: InboxSlot, captureId: String): Either<DomainError, Unit> = remove(slot, captureId)

    /** Removes an item after a successful append. The `.json` goes first so no reference outlives its image. */
    suspend fun remove(slot: InboxSlot, captureId: String): Either<DomainError, Unit> = guarded {
        requireToken(slot, captureId)?.let { return@guarded it.left() }
        val dir = dirPath(slot)
        fs.deleteFile("$dir/$captureId$JSON")
        fs.deleteFile("$dir/$captureId$IMG")
        attemptErrors.remove(slot.dirName to captureId)
        refreshLocked()
        Unit.right()
    }

    suspend fun quarantinedText(fileName: String): String? = guarded {
        quarantinePath(fileName)?.let { path -> fs.readFile(path)?.let(::salvageText).right() }
            ?: validation("bad quarantine file name").left()
    }.getOrNull()

    suspend fun discardQuarantined(fileName: String): Either<DomainError, Unit> = guarded {
        val path = quarantinePath(fileName) ?: return@guarded validation("bad quarantine file name").left()
        fs.deleteFile(path)
        refreshLocked()
        Unit.right()
    }

    /** In-memory last drain failure for the UI row; cleared with null. */
    suspend fun recordAttempt(slot: InboxSlot, captureId: String, error: String?) {
        mutex.withLock {
            if (error == null) attemptErrors.remove(slot.dirName to captureId)
            else attemptErrors[slot.dirName to captureId] = error
        }
        refresh()
    }

    /**
     * Moves every unassigned item to [target] (ADR-004). A whole-directory rename when [target] has no
     * directory yet, else per-file moves (images first); both are safe to repeat after a crash.
     * Returns the number of items that were waiting.
     */
    suspend fun rekeyUnassigned(target: GraphId): Either<DomainError, Int> = guarded {
        val to = InboxSlot.Graph(target)
        requireToken(to, "x")?.let { return@guarded it.left() }
        val src = dirPath(InboxSlot.Unassigned)
        val names = fs.listFiles(src).map(::leaf).filter { !it.endsWith(TMP) }
        if (names.isEmpty()) return@guarded 0.right()
        val dst = dirPath(to)
        val count = names.count { it.endsWith(JSON) }
        if (!fs.directoryExists(dst) && fs.renameFile(src, dst)) {
            refreshLocked()
            return@guarded count.right()
        }
        fs.createDirectory(root)
        fs.createDirectory(dst)
        for (name in names.sortedBy { if (it.endsWith(IMG)) 0 else 1 }) {
            val from = "$src/$name"
            val dest = "$dst/$name"
            if (!fs.renameFile(from, dest)) return@guarded writeFailed(dest, "re-key move failed").left()
            // renameFile leaves the source when the destination exists: same bytes is a duplicate, else keep it.
            if (fs.fileExists(from) && fs.fileExists(dest)) {
                if (sameBytes(from, dest)) fs.deleteFile(from) else quarantine(InboxSlot.Unassigned, name)
            }
        }
        refreshLocked()
        count.right()
    }

    private sealed interface Read {
        data class Ok(val item: InboxItem, val payload: PayloadDto) : Read
        data class Future(val item: InboxItem) : Read
        data class Corrupt(val reason: String) : Read
        data object Missing : Read

        /** The file exists but could not be read just now; says nothing about its content, so it is never quarantined. */
        data object Unavailable : Read
    }

    private fun readItem(slot: InboxSlot, captureId: String): Read {
        val path = "${dirPath(slot)}/$captureId$JSON"
        val raw = fs.readFile(path) ?: fs.readFile(path)
            ?: return if (fs.fileExists(path)) Read.Unavailable else Read.Missing
        val obj = try {
            jsonCodec.parseToJsonElement(raw).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Read.Corrupt("unparseable (${e::class.simpleName})") // e.message embeds the input
        }
        val version = obj["v"]?.jsonPrimitive?.intOrNull ?: return Read.Corrupt("missing version")
        val createdAt = obj["createdAtEpochMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        if (version > VERSION) {
            return Read.Future(
                InboxItem(slot, captureId, createdAt, null, InboxItemStatus.NeedsNewerApp(version), hasImage = false),
            )
        }
        val payload: JsonElement = obj["payload"] ?: return Read.Corrupt("missing payload")
        if (obj["sha256"]?.jsonPrimitive?.content != ContentHasher.sha256(payload.toString())) {
            return Read.Corrupt("checksum mismatch")
        }
        if (obj["captureId"]?.jsonPrimitive?.content != captureId) return Read.Corrupt("captureId mismatch")
        val dto = try {
            jsonCodec.decodeFromJsonElement(PayloadDto.serializer(), payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Read.Corrupt("bad payload (${e::class.simpleName})")
        }
        val item = InboxItem(
            slot, captureId, createdAt, dto.text, InboxItemStatus.Ready, hasImage = dto.image != null,
            lastError = attemptErrors[slot.dirName to captureId],
        )
        return Read.Ok(item, dto)
    }

    private fun refreshLocked() {
        val items = mutableListOf<InboxItem>()
        for (dir in fs.listDirectories(root).map(::leaf)) {
            val slot = slotOf(dir) ?: continue
            val dirPath = dirPath(slot)
            if (!PageFileResolver.isWithin(root, dirPath)) continue
            for (name in fs.listFiles(dirPath).map(::leaf).filter { it.endsWith(JSON) }) {
                val captureId = name.removeSuffix(JSON)
                if (!isToken(captureId)) continue
                when (val r = readItem(slot, captureId)) {
                    is Read.Ok -> items += r.item
                    is Read.Future -> items += r.item
                    is Read.Corrupt -> {
                        logger.warn("quarantining $dir/$name: ${r.reason}")
                        quarantine(slot, name)
                    }
                    Read.Missing, Read.Unavailable -> Unit
                }
            }
        }
        val quarantined = fs.listFiles("$root/$QUARANTINE_DIR").map(::leaf).sorted().map { name ->
            QuarantinedShare(name, fs.readFile("$root/$QUARANTINE_DIR/$name")?.let(::salvageText))
        }
        _state.value = ShareInboxState(items.sortedWith(compareBy({ it.createdAtEpochMs }, { it.captureId })), quarantined)
    }

    /** Moves a bad file aside under a unique name; on rename failure the file stays where it is. */
    private fun quarantine(slot: InboxSlot, fileName: String) {
        fs.createDirectory(root)
        fs.createDirectory("$root/$QUARANTINE_DIR")
        val from = "${dirPath(slot)}/$fileName"
        var n = 0
        while (true) {
            val dest = "$root/$QUARANTINE_DIR/${slot.dirName}__$fileName" + if (n == 0) "" else ".$n"
            if (!fs.fileExists(dest)) {
                if (!fs.renameFile(from, dest)) logger.warn("could not quarantine $from")
                return
            }
            n++
        }
    }

    /** Writes [path] via `<path>.tmp` in the same directory then renames; null on failure (tmp cleaned up). */
    private inline fun writeAtomically(path: String, write: (String) -> Boolean): Unit? {
        val tmp = "$path$TMP"
        if (!write(tmp) || !fs.renameFile(tmp, path)) {
            fs.deleteFile(tmp)
            return null
        }
        if (fs.fileExists(tmp)) fs.deleteFile(tmp) // destination already existed: first write wins
        return Unit
    }

    private fun sameBytes(a: String, b: String): Boolean {
        val x = fs.readFileBytes(a)
        val y = fs.readFileBytes(b)
        return x != null && y != null && x.contentEquals(y)
    }

    private fun slotOf(dir: String): InboxSlot? = when {
        dir == UNASSIGNED_DIR -> InboxSlot.Unassigned
        isToken(dir) -> InboxSlot.Graph(GraphId(dir))
        else -> null
    }

    private fun dirPath(slot: InboxSlot) = "$root/${slot.dirName}"

    private fun quarantinePath(fileName: String): String? {
        if (!QUARANTINE_NAME.matches(fileName)) return null
        val path = "$root/$QUARANTINE_DIR/$fileName"
        return path.takeIf { PageFileResolver.isWithin(root, it) }
    }

    private fun requireToken(slot: InboxSlot, captureId: String): DomainError? {
        if (slot is InboxSlot.Graph && !isToken(slot.dirName)) return validation("invalid graph key")
        if (!isToken(captureId)) return validation("invalid captureId")
        val path = "${dirPath(slot)}/$captureId$JSON"
        return if (PageFileResolver.isWithin(root, path)) null else validation("path escapes inbox root")
    }

    private fun validation(msg: String) = DomainError.ValidationError.ConstraintViolation(msg)

    private fun writeFailed(path: String, msg: String) = DomainError.FileSystemError.WriteFailed(path, msg)

    private suspend fun <T> guarded(block: suspend () -> Either<DomainError, T>): Either<DomainError, T> =
        mutex.withLock {
            withContext(ioDispatcher) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("share inbox operation failed: ${e::class.simpleName}")
                    writeFailed(root, e.message ?: "unknown").left()
                }
            }
        }

    companion object {
        const val VERSION = 1
        const val UNASSIGNED_DIR = "_unassigned"
        const val QUARANTINE_DIR = "_quarantine"
        private const val JSON = ".json"
        private const val IMG = ".img"
        private const val TMP = ".tmp"
        private const val TMP_MAX_AGE_MS = 60L * 60 * 1000
        private val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val QUARANTINE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,300}")
        private val jsonCodec = Json { ignoreUnknownKeys = true }

        /** Plain tokens only: no separators, no leading `_` (reserved for internal slots) or `.`. */
        fun isToken(s: String): Boolean = TOKEN.matches(s) && !s.contains("..")

        private fun leaf(p: String) = p.substringAfterLast('/').substringAfterLast('\\')

        /** Best-effort extraction of the `"text"` string from damaged or future-versioned JSON. */
        internal fun salvageText(raw: String): String? {
            runCatching {
                val payload = jsonCodec.parseToJsonElement(raw).jsonObject["payload"]?.jsonObject
                payload?.get("text")?.jsonPrimitive?.content?.let { return it }
            }
            val key = Regex("\"text\"\\s*:\\s*\"").find(raw) ?: return null
            val start = key.range.last + 1
            var i = start
            while (i < raw.length) {
                when (raw[i]) {
                    '\\' -> i++
                    '"' -> break
                }
                i++
            }
            val body = raw.substring(start, minOf(i, raw.length)).trimEnd('\\')
            return runCatching { jsonCodec.decodeFromString<String>("\"$body\"") }.getOrNull() ?: body.ifEmpty { null }
        }
    }
}
