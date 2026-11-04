package dev.stapler.stelekit.asset.pipeline

import arrow.atomic.AtomicInt
import arrow.atomic.value
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Counts what leaves the device for the cloud plugin. The per-launch count lives in [session]
 * (process-wide by default, so a graph switch does not reset it); the per-day count is persisted
 * in [settings] with the local date, so a restart does not reset it and local midnight does.
 */
class EgressLedger(
    private val settings: Settings,
    private val sessionCap: Int = DEFAULT_SESSION_CAP,
    private val dailyCap: Int = DEFAULT_DAILY_CAP,
    private val perAssetMaxBytes: Long = DEFAULT_PER_ASSET_MAX_BYTES,
    private val session: AtomicInt = processSession,
    private val today: () -> String = ::localToday,
) {
    private val logger = Logger("EgressLedger")

    sealed interface Reservation {
        data object Allowed : Reservation
        data object SessionCap : Reservation
        data object DailyCap : Reservation
        data class TooLarge(val bytes: Long, val maxBytes: Long) : Reservation
    }

    val sessionCount: Int get() = session.value

    val dailyCount: Int get() = readDaily()

    val sessionLimit: Int get() = sessionCap
    val dailyLimit: Int get() = dailyCap

    /** Counts one upload against both caps when it is allowed; a refused one counts nothing. */
    fun tryReserve(sizeBytes: Long): Reservation {
        if (sizeBytes > perAssetMaxBytes) return Reservation.TooLarge(sizeBytes, perAssetMaxBytes)
        if (session.value >= sessionCap) return Reservation.SessionCap
        val daily = readDaily()
        if (daily >= dailyCap) return Reservation.DailyCap
        session.incrementAndGet()
        settings.putString(DAY_KEY, today())
        settings.putString(COUNT_KEY, (daily + 1).toString())
        return Reservation.Allowed
    }

    /** One INFO line per upload: ids and sizes only, never image content or the key. */
    fun recordUpload(assetUuid: String, bytes: Int, provider: String = CloudConsentGate.PROVIDER) {
        logger.info("cloud upload asset=$assetUuid bytes=$bytes provider=$provider")
    }

    private fun readDaily(): Int {
        if (settings.getString(DAY_KEY, "") != today()) return 0
        return settings.getString(COUNT_KEY, "0").toIntOrNull() ?: 0
    }

    companion object {
        const val DEFAULT_SESSION_CAP = 20
        const val DEFAULT_DAILY_CAP = 100
        const val DEFAULT_PER_ASSET_MAX_BYTES = 8L * 1024 * 1024
        private const val DAY_KEY = "cloud_egress_day"
        private const val COUNT_KEY = "cloud_egress_day_count"

        /** One counter per process: resets when the app starts, not on a graph switch. */
        private val processSession = AtomicInt(0)

        private fun localToday(): String =
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    }
}
