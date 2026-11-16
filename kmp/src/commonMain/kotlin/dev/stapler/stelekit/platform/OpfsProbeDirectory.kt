package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

/**
 * Where and how the startup OPFS probe works (D-137): one directory per probe directly under the OPFS root, at most
 * [MAX_FILES] files of at most [MAX_FILE_BYTES] bytes, deleted in a `finally`. A crashed probe's directory is removed
 * by a later page load once its UUIDv7 is more than [STALE_AFTER_MS] old. Pure so every target tests it.
 */
object OpfsProbeDirectory {
    const val PREFIX = "stelekit-probe-"
    const val MAX_FILES = 3
    const val MAX_FILE_BYTES = 64
    const val STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000

    private const val UUID_LENGTH = 36
    private const val TIMESTAMP_HEX_END = 13
    private const val HEX_RADIX = 16
    private val UUID_V7 = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

    fun nameFor(uuidV7: String): String = PREFIX + uuidV7

    /** True for a probe directory whose UUIDv7 millisecond stamp is older than [STALE_AFTER_MS]; an unparsable name is never stale. */
    fun isStale(name: String, nowMillis: Long): Boolean {
        if (!name.startsWith(PREFIX)) return false
        val id = name.removePrefix(PREFIX)
        if (id.length != UUID_LENGTH || !UUID_V7.matches(id)) return false
        val created = id.substring(0, TIMESTAMP_HEX_END).replace("-", "").toLong(HEX_RADIX)
        return nowMillis - created > STALE_AFTER_MS
    }
}

/** Maps the `name` of a rejected OPFS promise (a DOMException) to the typed error the saver understands (D-119). */
object OpfsErrors {
    const val QUOTA_EXCEEDED_NAME = "QuotaExceededError"

    fun write(path: String, errorName: String): Either<DomainError, Nothing> = when (errorName) {
        QUOTA_EXCEEDED_NAME -> DomainError.FileSystemError.StorageQuotaExceeded(path).left()
        else -> DomainError.FileSystemError.WriteFailed(path, errorName).left()
    }
}
