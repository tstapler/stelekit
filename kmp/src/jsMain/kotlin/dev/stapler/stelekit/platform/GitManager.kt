package dev.stapler.stelekit.platform

actual object GitManagerFactory {
    actual fun create(): GitManager = JsGitManager()
}

class JsGitManager : GitManager {
    override suspend fun commit(message: String): GitResult<String> {
        return GitResult.Success("Commit not supported on Web")
    }

    override suspend fun push(): GitResult<Unit> {
        return GitResult.Error("Push not supported on Web")
    }

    override suspend fun pull(): GitResult<Unit> {
        return GitResult.Error("Pull not supported on Web")
    }

    override suspend fun status(): GitResult<String> {
        return GitResult.Success("Clean")
    }

    override suspend fun isDirty(): GitResult<Boolean> {
        return GitResult.Success(false)
    }
}
