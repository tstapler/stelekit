package dev.stapler.stelekit.git.merge

import arrow.core.Either

sealed class LlmError {
    data object TokenLimitExceeded : LlmError()
    data class NetworkError(val message: String) : LlmError()
    data class ApiError(val status: Int, val body: String) : LlmError()
    data object Disabled : LlmError()
}

interface LlmEnhancementClient {
    suspend fun resolveConflictMarkers(textWithMarkers: String): Either<LlmError, String>
}
