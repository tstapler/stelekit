package dev.stapler.stelekit.platform

actual object GitManagerFactory {
    actual fun create(): GitManager = AndroidGitManager()
}

class AndroidGitManager : GitManager {
    override suspend fun commit(message: String): GitResult<String> {
        return GitResult.Error("Git not implemented on Android yet")
    }

    override suspend fun push(): GitResult<Unit> {
        return GitResult.Error("Git not implemented on Android yet")
    }

    override suspend fun pull(): GitResult<Unit> {
        return GitResult.Error("Git not implemented on Android yet")
    }

    override suspend fun status(): GitResult<String> {
        return GitResult.Error("Git not implemented on Android yet")
    }

    override suspend fun isDirty(): GitResult<Boolean> {
        return GitResult.Success(false)
    }
}
