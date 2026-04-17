package dev.stapler.stelekit.platform

/**
 * Result of a Git operation.
 */
sealed class GitResult<out T> {
    data class Success<out T>(val data: T) : GitResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : GitResult<Nothing>()
}

/**
 * Foundation for Git operations in a multi-platform environment.
 * Platform-specific implementations (e.g., CLI for Desktop, Isomorphic-Git for Mobile)
 * should implement this interface.
 */
interface GitManager {
    /**
     * Commits changes with the given message.
     */
    suspend fun commit(message: String): GitResult<String>

    /**
     * Pushes local changes to the remote repository.
     */
    suspend fun push(): GitResult<Unit>

    /**
     * Pulls changes from the remote repository.
     */
    suspend fun pull(): GitResult<Unit>

    /**
     * Gets the current status of the repository.
     */
    suspend fun status(): GitResult<String>

    /**
     * Checks if the repository has uncommitted changes.
     */
    suspend fun isDirty(): GitResult<Boolean>
}

/**
 * Placeholder for platform-specific GitManager provider.
 */
expect object GitManagerFactory {
    fun create(): GitManager
}
