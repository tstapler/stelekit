package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

actual object GitManagerFactory {
    actual fun create(): GitManager = JsGitManager()
}

class JsGitManager : GitManager {
    override suspend fun commit(message: String): GitResult<String> {
        return GitResult.Success("Commit not supported on Web")
    }

    override suspend fun push(): Either<DomainError, Unit> {
        return DomainError.NetworkError.HttpError(501, "Push not supported on Web").left()
    }

    override suspend fun pull(): Either<DomainError, Unit> {
        return DomainError.NetworkError.HttpError(501, "Pull not supported on Web").left()
    }

    override suspend fun status(): GitResult<String> {
        return GitResult.Success("Clean")
    }

    override suspend fun isDirty(): GitResult<Boolean> {
        return GitResult.Success(false)
    }
}
