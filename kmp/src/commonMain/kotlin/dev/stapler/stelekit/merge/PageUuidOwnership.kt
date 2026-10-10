package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal fun explicitUuids(blocks: List<MergeBlock>): List<String> =
    blocks.flatMap { b -> listOfNotNull(b.uuid) + explicitUuids(b.children) }

/** A page file that is itself a link resolves elsewhere than its directory entry; the swap would sever it. */
internal fun isSymlinkFile(path: String, canonicalize: (String) -> String): Boolean {
    val dir = path.substringBeforeLast('/')
    return canonicalize(path) != canonicalize(dir).trimEnd('/') + "/" + path.substringAfterLast('/')
}

/**
 * Which page file owns each `id::` in an off-graph target. A uuid already on another page file would be moved by the
 * loader's INSERT OR REPLACE, taking its children with it. One scan of `pages/` and `journals/` is kept for [TTL];
 * a file that cannot be read fails the check closed.
 */
internal class PageUuidOwnership(
    private val fs: FileSystem,
    targetPath: String,
    private val readText: (String) -> Either<DomainError, String?>,
) {
    private val root = targetPath.trimEnd('/')
    private var index: MutableMap<String, String>? = null
    private var builtAt: TimeMark? = null

    /** Records [uuids] as owned by [path] after a successful write, so the cached scan stays current. */
    fun note(path: String, uuids: List<String>) {
        index?.let { known -> uuids.forEach { known[it.lowercase()] = path } }
    }

    /** A collision error when any of [uuids] already belongs to a page file other than [ownPath]. */
    fun findCollision(uuids: List<String>, ownPath: String): Either<DomainError, DomainError?> {
        if (uuids.isEmpty()) return null.right()
        val known = scan().fold({ return it.left() }, { it })
        val hit = uuids.firstOrNull { known[it.lowercase()]?.let { owner -> owner != ownPath } == true } ?: return null.right()
        return DomainError.DatabaseError.WriteFailed("uuid collision: block $hit already exists on another page").right()
    }

    private fun scan(): Either<DomainError, Map<String, String>> {
        index?.takeIf { builtAt?.let { it.elapsedNow() < TTL } == true }?.let { return it.right() }
        val built = HashMap<String, String>()
        for (dir in listOf("$root/pages", "$root/journals")) {
            for (name in fs.listFiles(dir)) {
                if (!name.endsWith(".md")) continue
                val file = "$dir/$name"
                val text = readText(file).fold({ return it.left() }, { it }) ?: continue
                ID_LINE.findAll(text).forEach { built.putIfAbsent(it.groupValues[1].lowercase(), file) }
            }
        }
        index = built
        builtAt = TimeSource.Monotonic.markNow()
        return built.right()
    }

    private companion object {
        val TTL = 30.seconds
        val ID_LINE = Regex("^[ \\t]*id::[ \\t]*(\\S+)", RegexOption.MULTILINE)
    }
}

/** Removes crash leftovers once per directory; a temp file younger than [MAX_AGE] may belong to a writer still running. */
internal class StaleTempSweeper(private val fs: FileSystem, private val suffix: String) {
    private val swept = HashSet<String>()

    fun sweepOnce(dir: String) {
        if (!swept.add(dir)) return
        val now = Clock.System.now().toEpochMilliseconds()
        for (name in fs.listFiles(dir)) {
            if (!name.endsWith(suffix)) continue
            val file = "$dir/$name"
            val modified = fs.getLastModifiedTime(file) ?: continue
            if (now - modified > MAX_AGE.inWholeMilliseconds) runCatching { fs.deleteFile(file) }
        }
    }

    private companion object {
        val MAX_AGE = 10.minutes
    }
}
