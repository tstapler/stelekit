package dev.stapler.stelekit.db

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Resolves the on-disk storage path for an image file inside a graph.
 *
 * Path convention: `<graphPath>/assets/images/<yyyy-MM-dd>-<uuid-prefix>.jpg`
 *
 * - Date is the calendar date at the moment of resolution (local time zone).
 * - UUID prefix is the first 8 characters of [uuid] (enough to be human-readable;
 *   collisions within a single day are astronomically unlikely).
 * - The `.jpg` extension is used even if the source is a PNG — images are always
 *   re-encoded to JPEG before storage to control file size.
 */
object ImageStoragePathResolver {

    /**
     * Compute the full on-disk path for an image with the given [uuid] stored in [graphPath].
     *
     * Example: `/home/user/my-graph/assets/images/2026-05-16-a3f8b2c1.jpg`
     */
    fun resolvePath(graphPath: String, uuid: String): String {
        val now = Clock.System.now()
        val date = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        val dateStr = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
        val uuidPrefix = uuid.replace("-", "").take(8)
        return "$graphPath/assets/images/$dateStr-$uuidPrefix.jpg"
    }

    /** Directory under which all image assets are stored. */
    fun assetsImagesDir(graphPath: String): String =
        "$graphPath/assets/images"
}
