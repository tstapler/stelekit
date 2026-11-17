// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.session

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.sketch.v2.runtime.convert.ProjectAcknowledgements

/** How far this device's user has confirmed the whole-project warning in Settings (D-3): none, New sketch projects only, or On. */
enum class AckLevel {
    None,
    NewProjectsOnly,
    On,
    ;

    /** True when a confirmation at this level already covers choosing [mode]; Off is always covered. */
    fun covers(mode: V2Mode): Boolean = when (mode) {
        V2Mode.Off -> true
        V2Mode.NewProjectsOnly -> this >= NewProjectsOnly
        V2Mode.On -> this == On
    }

    companion object {
        fun parse(raw: String): AckLevel = entries.firstOrNull { it.name == raw } ?: None

        /** The level a confirmed [mode] raises the device to. */
        fun of(mode: V2Mode): AckLevel = when (mode) {
            V2Mode.Off -> None
            V2Mode.NewProjectsOnly -> NewProjectsOnly
            V2Mode.On -> On
        }
    }
}

/**
 * The device-local (never synced) consent records of the migration UI, over `Settings` (Story 1.4.6, D-16, D-96):
 * the per-project acknowledgement (device, graph, project slug), the per-device [AckLevel], the day the consent sheet
 * last opened per project and the per-sheet `Declined` mark. Per-graph records live in ONE key each, so a graph removal
 * is one write ([removeGraph]) and a project that no longer exists is dropped on the next write ([pruneProjects]).
 * [graphId] is the READY graph at call time; with none ready nothing is read or written.
 */
class ProjectAckStore(
    private val settings: Settings,
    private val graphId: () -> String?,
) : ProjectAcknowledgements {
    override suspend fun isAcknowledged(slug: String): Boolean = withGraph { slug in lines(projectsKey(it)) } ?: false

    /** Records that this device's user confirmed the whole-project consequence for [slug]. */
    fun acknowledge(slug: String) {
        withGraph { id -> write(projectsKey(id), (lines(projectsKey(id)) + slug).distinct()) }
    }

    var level: AckLevel
        get() = AckLevel.parse(settings.getString(LEVEL_KEY, ""))
        private set(value) = settings.putString(LEVEL_KEY, value.name)

    /** Raises the level to [mode]'s; a lower or equal mode never lowers it. */
    fun raiseLevel(mode: V2Mode) {
        val target = AckLevel.of(mode)
        if (target > level) level = target
    }

    /** The epoch day the consent sheet last opened for [slug], or null. */
    fun consentSheetDay(slug: String): Long? = withGraph { id -> keyed(sheetDaysKey(id))[slug]?.toLongOrNull() }

    fun recordConsentSheetDay(slug: String, epochDay: Long) {
        withGraph { id -> writeKeyed(sheetDaysKey(id), keyed(sheetDaysKey(id)) + (slug to epochDay.toString())) }
    }

    fun isDeclined(sheetUuid: String): Boolean = withGraph { sheetUuid in lines(declinedKey(it)) } ?: false

    /** "Keep this sketch classic": the chip is not offered for this sheet again until [clearDecline]. */
    fun decline(sheetUuid: String) {
        withGraph { id -> write(declinedKey(id), (lines(declinedKey(id)) + sheetUuid).distinct()) }
    }

    /** The sheet menu entry "Upgrade to the new editor" lifts the mark. */
    fun clearDecline(sheetUuid: String) {
        withGraph { id -> write(declinedKey(id), lines(declinedKey(id)) - sheetUuid) }
    }

    /** True once the post-conversion banner has been raised for [sheetUuid] (shown once per sheet, never again after a dismissal or a reopen). */
    fun postConversionShown(sheetUuid: String): Boolean = withGraph { sheetUuid in lines(postConversionKey(it)) } ?: false

    fun markPostConversionShown(sheetUuid: String) {
        withGraph { id -> write(postConversionKey(id), (lines(postConversionKey(id)) + sheetUuid).distinct()) }
    }

    /** Drops the per-project records of every slug not in [existingSlugs] (a deleted project). */
    fun pruneProjects(existingSlugs: Set<String>) {
        withGraph { id ->
            write(projectsKey(id), lines(projectsKey(id)).filter { it in existingSlugs })
            writeKeyed(sheetDaysKey(id), keyed(sheetDaysKey(id)).filterKeys { it in existingSlugs })
        }
    }

    /** Removes every per-graph record of [removedGraphId]; the device level stays. */
    fun removeGraph(removedGraphId: String) {
        listOf(projectsKey(removedGraphId), sheetDaysKey(removedGraphId), declinedKey(removedGraphId), postConversionKey(removedGraphId)).forEach { settings.putString(it, "") }
    }

    private fun <T> withGraph(block: (String) -> T): T? = graphId()?.let(block)

    private fun lines(key: String): List<String> = settings.getString(key, "").split(LINE).filter { it.isNotEmpty() }

    private fun write(key: String, values: List<String>) = settings.putString(key, values.joinToString(LINE.toString()))

    private fun keyed(key: String): Map<String, String> =
        lines(key).mapNotNull { line -> line.indexOf(SEP).takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()

    private fun writeKeyed(key: String, values: Map<String, String>) = write(key, values.map { (k, v) -> "$k$SEP$v" })

    private companion object {
        const val LEVEL_KEY = "sketch.ack.level"
        const val LINE = '\n'
        const val SEP = '|'

        fun projectsKey(graphId: String) = "sketch.ack.projects.$graphId"

        fun sheetDaysKey(graphId: String) = "sketch.ack.sheetDays.$graphId"

        fun declinedKey(graphId: String) = "sketch.ack.declined.$graphId"

        fun postConversionKey(graphId: String) = "sketch.ack.postConversion.$graphId"
    }
}

/**
 * Which "Created as classic." toasts this device has already shown (D-96): one per-graph `Settings` key
 * `sketch.classicNotices.<graphId>` of at most [MAX_ENTRIES] `slug|reason` entries, the oldest evicted first (an evicted
 * entry only lets the toast show once more). A [reason] is the id of the closed `ClassicNoticeReason` set. Device-local.
 */
class ClassicNoticeLedger(private val settings: Settings) {
    fun hasShown(graphId: String, slug: String, reason: String): Boolean = entry(slug, reason) in entries(graphId)

    /**
     * Records the toast. [existingSlugs], when known, drops entries of projects that no longer exist (null leaves them: a
     * project list that cannot be read must not erase the record).
     */
    fun record(graphId: String, slug: String, reason: String, existingSlugs: Set<String>?) {
        val added = entry(slug, reason)
        val kept = entries(graphId).filter { it != added && (existingSlugs == null || it.substringBeforeLast(SEP) in existingSlugs) }
        settings.putString(key(graphId), (kept + added).takeLast(MAX_ENTRIES).joinToString(LINE.toString()))
    }

    /** The `GraphManager.onGraphRemoved` cleanup: the whole key. */
    fun removeGraph(graphId: String) = settings.putString(key(graphId), "")

    fun size(graphId: String): Int = entries(graphId).size

    private fun entries(graphId: String): List<String> = settings.getString(key(graphId), "").split(LINE).filter { it.isNotEmpty() }

    private fun key(graphId: String) = "sketch.classicNotices.$graphId"

    private fun entry(slug: String, reason: String) = "$slug$SEP$reason"

    companion object {
        const val MAX_ENTRIES = 200
        private const val LINE = '\n'
        private const val SEP = '|'
    }
}
