package dev.stapler.stelekit.platform

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Removes crash leftovers once per directory; a temp file younger than [MAX_AGE] may belong to a writer still running.
 * [nowMillis] is injectable so tests never wait on the wall clock.
 */
internal class StaleTempSweeper(
    private val fs: FileSystem,
    private val suffix: String,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val swept = HashSet<String>()

    fun sweepOnce(dir: String) {
        if (!swept.add(dir)) return
        val now = nowMillis()
        for (name in fs.listFiles(dir)) {
            if (!name.endsWith(suffix)) continue
            val file = "$dir/$name"
            val modified = fs.getLastModifiedTime(file) ?: uuidV7Millis(name) ?: continue
            if (now - modified > MAX_AGE.inWholeMilliseconds) runCatching { fs.deleteFile(file) }
        }
    }

    /**
     * Sweeps [root] and every directory below it, ignoring the once-per-directory memo of [sweepOnce]
     * (a wasm preload runs this once per preload, under the sweep lock, never under the writer lease).
     */
    fun sweepTree(root: String) {
        swept.remove(root)
        sweepOnce(root)
        for (dir in fs.listDirectories(root)) sweepTree("$root/$dir")
    }

    /**
     * An OPFS-only file has no modification time, so the age comes from the UUIDv7 in `<name>.<uuidv7>$suffix`
     * (its first 48 bits are epoch milliseconds). Null for a name that does not end in a UUIDv7.
     */
    private fun uuidV7Millis(name: String): Long? {
        val id = name.removeSuffix(suffix).substringAfterLast('.')
        if (!UUID_V7.matches(id)) return null
        return id.substring(0, 13).replace("-", "").toLong(16)
    }

    companion object {
        val MAX_AGE = 10.minutes
        private val UUID_V7 = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}
