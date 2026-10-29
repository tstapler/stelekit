package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlin.random.Random

/**
 * Crash-loop breaker for the asset pipeline (indexing walk and plugin drain).
 *
 * A run persists a marker holding this process's token. A clean stop (finish, pause, cancel,
 * switch off) clears it and resets the counter. A later process that finds a marker with a
 * different token knows the previous run died uncleanly and counts it; at [UNCLEAN_EXIT_LIMIT]
 * consecutive unclean exits it forces `asset_pipeline_enabled` off so a launch-time crash cannot
 * repeat forever (the owner could not reach Settings to turn it off). The marker lives in
 * [Settings], so backup exclusion rules must cover these keys.
 */
class PipelineRunMarker(
    private val settings: Settings,
    private val processToken: String = PROCESS_TOKEN,
) {
    private val logger = Logger("PipelineRunMarker")
    private val activeOwners = MutableStateFlow<Set<String>>(emptySet())

    /** Consecutive unclean exits seen so far. */
    val uncleanExits: Int get() = readInt(COUNT_KEY)

    /** Whether the breaker turned the pipeline off and the owner has not re-enabled it. */
    val tripped: Boolean get() = readBoolean(TRIPPED_KEY)

    /**
     * Call once per launch before reading the master flag. Returns true when the breaker is
     * tripped (this launch or earlier), in which case the flag has been set false.
     */
    fun evaluateAtLaunch(): Boolean {
        val stored = readString(MARKER_KEY)
        if (stored.isNotEmpty() && stored != processToken) {
            val count = readInt(COUNT_KEY) + 1
            write { putString(COUNT_KEY, count.toString()) }
            write { putString(MARKER_KEY, "") }
            logger.warn("Previous asset pipeline run did not stop cleanly (consecutive unclean exits: $count)")
            if (count >= UNCLEAN_EXIT_LIMIT) {
                write {
                    putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, false)
                    putBoolean(TRIPPED_KEY, true)
                }
                logger.warn("Asset pipeline turned off after $count consecutive unclean exits")
            }
        }
        return tripped
    }

    /** A run owned by [owner] started; the first owner persists the marker. */
    fun begin(owner: String) {
        val before = activeOwners.getAndUpdate { it + owner }
        if (before.isEmpty()) write { putString(MARKER_KEY, processToken) }
    }

    /** A clean stop of [owner]'s run; the last owner clears the marker and resets the counter. */
    fun end(owner: String) {
        val before = activeOwners.getAndUpdate { it - owner }
        if (owner in before && before.size == 1) {
            write {
                putString(MARKER_KEY, "")
                putString(COUNT_KEY, "0")
            }
        }
    }

    /** The owner re-enabled the pipeline: forget the crashes. */
    fun reset() {
        write {
            putString(MARKER_KEY, "")
            putString(COUNT_KEY, "0")
            putBoolean(TRIPPED_KEY, false)
        }
    }

    private fun readString(key: String): String = guarded("") { settings.getString(key, "") }
    private fun readBoolean(key: String): Boolean = guarded(false) { settings.getBoolean(key, false) }
    private fun readInt(key: String): Int = readString(key).toIntOrNull() ?: 0

    private fun write(block: Settings.() -> Unit) {
        guarded(Unit) { settings.block() }
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Pipeline run marker settings access failed: ${e.message}")
        fallback
    }

    companion object {
        const val MARKER_KEY: String = "asset_pipeline_run_marker"
        const val COUNT_KEY: String = "asset_pipeline_unclean_exits"
        const val TRIPPED_KEY: String = "asset_pipeline_breaker_tripped"
        const val UNCLEAN_EXIT_LIMIT: Int = 2

        /** Identifies this process; a marker left by any other token means that process died mid-run. */
        val PROCESS_TOKEN: String = Random.nextLong().toULong().toString(16)
    }
}
