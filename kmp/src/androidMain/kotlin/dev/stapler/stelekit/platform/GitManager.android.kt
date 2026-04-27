package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

actual object GitManagerFactory {
    actual fun create(): GitManager = AndroidGitManager()
}

class AndroidGitManager : GitManager {
    override suspend fun commit(message: String): GitResult<String> {
        return GitResult.Error("Git not implemented on Android yet")
    }

    override suspend fun push(): Either<DomainError, Unit> {
        return DomainError.DatabaseError.WriteFailed("Git not implemented on Android yet").left()
    }

    override suspend fun pull(): Either<DomainError, Unit> {
        return DomainError.DatabaseError.WriteFailed("Git not implemented on Android yet").left()
    }

    override suspend fun status(): GitResult<String> {
        return GitResult.Error("Git not implemented on Android yet")
    }

    override suspend fun isDirty(): GitResult<Boolean> {
        return GitResult.Success(false)
    }
}
