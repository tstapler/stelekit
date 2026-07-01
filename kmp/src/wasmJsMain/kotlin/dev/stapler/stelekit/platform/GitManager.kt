package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError

actual object GitManagerFactory {
    actual fun create(): GitManager = JsGitManager()
}

// ponytail: all ops fail visibly; full impl requires isomorphic-git (Epic-level)
private const val NOT_SUPPORTED = "Git sync is not available on the web platform"

class JsGitManager : GitManager {
    override suspend fun commit(message: String): GitResult<String> =
        GitResult.Error(NOT_SUPPORTED)

    override suspend fun push(): Either<DomainError, Unit> =
        DomainError.NetworkError.HttpError(501, NOT_SUPPORTED).left()

    override suspend fun pull(): Either<DomainError, Unit> =
        DomainError.NetworkError.HttpError(501, NOT_SUPPORTED).left()

    override suspend fun status(): GitResult<String> =
        GitResult.Error(NOT_SUPPORTED)

    override suspend fun isDirty(): GitResult<Boolean> =
        GitResult.Error(NOT_SUPPORTED)
}
