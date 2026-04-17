// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

sealed class FetchResult {
    data class Success(val text: String, val pageTitle: String?) : FetchResult()
    sealed class Failure : FetchResult() {
        data object Timeout : Failure()
        data object NetworkUnavailable : Failure()
        data class HttpError(val code: Int) : Failure()
        data object ParseError : Failure()
        data object TooLarge : Failure()
    }
}

interface UrlFetcher {
    suspend fun fetch(url: String): FetchResult
}

// Compile placeholder — used in tests and before platform impls are wired
class NoOpUrlFetcher : UrlFetcher {
    override suspend fun fetch(url: String): FetchResult = FetchResult.Failure.NetworkUnavailable
}
