// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

sealed class ArchiveResult {
    data class Success(val archiveUrl: String) : ArchiveResult()
    data class AlreadyArchived(val archiveUrl: String) : ArchiveResult()
}

interface WaybackMachineService {
    suspend fun archiveUrl(url: String): Either<DomainError, ArchiveResult>
}

class KtorWaybackMachineService(private val httpClient: HttpClient) : WaybackMachineService {

    override suspend fun archiveUrl(url: String): Either<DomainError, ArchiveResult> {
        return try {
            val encodedUrl = URLBuilder("https://web.archive.org/save/")
                .appendPathSegments(url)
                .buildString()
            val response: HttpResponse = httpClient.post {
                url(encodedUrl)
                headers {
                    append(HttpHeaders.Accept, "application/json")
                    append("Prefer", "respond-async")
                }
            }
            if (!response.status.isSuccess()) {
                return DomainError.NetworkError.HttpError(
                    response.status.value,
                    "Wayback Machine returned ${response.status.value}"
                ).left()
            }
            val contentLocation = response.headers[HttpHeaders.ContentLocation]
            if (contentLocation != null) {
                val archiveUrl = "https://web.archive.org$contentLocation"
                ArchiveResult.Success(archiveUrl).right()
            } else {
                // Location header missing — fall back to constructing from known URL format
                ArchiveResult.Success("https://web.archive.org/web/*/$url").right()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isTimeout = e is io.ktor.client.plugins.HttpRequestTimeoutException ||
                e.message?.contains("timeout", ignoreCase = true) == true
            if (isTimeout) {
                DomainError.NetworkError.Timeout("Archive request timed out: ${e.message}").left()
            } else {
                DomainError.NetworkError.RequestFailed(e.message ?: "unknown error").left()
            }
        }
    }
}
