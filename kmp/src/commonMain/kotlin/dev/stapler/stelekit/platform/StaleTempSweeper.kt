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
            val modified = fs.getLastModifiedTime(file) ?: continue
            if (now - modified > MAX_AGE.inWholeMilliseconds) runCatching { fs.deleteFile(file) }
        }
    }

    companion object {
        val MAX_AGE = 10.minutes
    }
}
