// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * iOS implementation of [UrlFetcher] using Ktor Darwin engine and Ksoup for HTML parsing.
 *
 * The constructor accepts an [HttpClient] so tests can inject a [MockEngine] without
 * touching the network.
 */
class UrlFetcherIos(
    private val client: HttpClient = createDefaultClient()
) : UrlFetcher {

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024 // 2 MB

        fun createDefaultClient(): HttpClient = HttpClient(Darwin) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
            }
        }
    }

    override suspend fun fetch(url: String): FetchResult {
        // Security: only allow http/https schemes
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return FetchResult.Failure.HttpError(0)
        }

        return try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (compatible; SteleKit/1.0)")
            }

            if (!response.status.isRight()()) {
                return FetchResult.Failure.HttpError(response.status.value)
            }

            val bytes = response.readRawBytes()
            if (bytes.size > MAX_BYTES) {
                return FetchResult.Failure.TooLarge
            }

            val htmlString = bytes.decodeToString()
            val doc = Ksoup.parse(htmlString)
            val text = doc.body().text()
            val pageTitle = doc.title().takeIf { it.isNotBlank() }

            FetchResult.Success(text = text, pageTitle = pageTitle)
        } catch (e: HttpRequestTimeoutException) {
            FetchResult.Failure.Timeout
        } catch (e: Exception) {
            FetchResult.Failure.NetworkUnavailable
        }
    }
}
