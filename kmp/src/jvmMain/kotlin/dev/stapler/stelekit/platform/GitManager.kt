package dev.stapler.stelekit.platform

import dev.stapler.stelekit.logging.Logger

/**
 * JVM implementation of GitManagerFactory.
 */
actual object GitManagerFactory {
    actual fun create(): GitManager = JvmGitManager()
}

/**
 * JVM implementation of GitManager using simple placeholders or CLI execution.
 */
class JvmGitManager : GitManager {
    private val logger = Logger("GitManager")

    override suspend fun commit(message: String): GitResult<String> {
        // Placeholder: Implement actual git logic here (e.g. ProcessBuilder("git", "commit", ...))
        logger.info("JVM Git commit: $message")
        return GitResult.Success("Commit hash placeholder")
    }

    override suspend fun push(): GitResult<Unit> {
        logger.info("JVM Git push")
        return GitResult.Success(Unit)
    }

    override suspend fun pull(): GitResult<Unit> {
        logger.info("JVM Git pull")
        return GitResult.Success(Unit)
    }

    override suspend fun status(): GitResult<String> {
        logger.debug("JVM Git status")
        return GitResult.Success("On branch main\nNothing to commit")
    }

    override suspend fun isDirty(): GitResult<Boolean> {
        return GitResult.Success(false)
    }
}
