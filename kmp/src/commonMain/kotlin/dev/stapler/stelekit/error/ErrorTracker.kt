package dev.stapler.stelekit.error

/**
 * Interface for tracking and reporting errors across platforms.
 */
interface ErrorTracker {
    /**
     * Reports an error with optional message and metadata.
     */
    fun trackError(throwable: Throwable, message: String? = null, metadata: Map<String, String> = emptyMap())

    /**
     * Records a breadcrumb leading up to an error.
     */
    fun recordBreadcrumb(message: String, category: String? = null, metadata: Map<String, String> = emptyMap())

    /**
     * Flushes any pending error reports.
     */
    fun flush()
}

/**
 * Default no-op implementation of ErrorTracker.
 */
object NoOpErrorTracker : ErrorTracker {
    override fun trackError(throwable: Throwable, message: String?, metadata: Map<String, String>) {}
    override fun recordBreadcrumb(message: String, category: String?, metadata: Map<String, String>) {}
    override fun flush() {}
}
