// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode

/**
 * True for the `.gitattributes` reads the web LFS guard makes before every commit/push (GitHub raw URLs and
 * GitLab repository-file URLs alike).
 */
internal fun isGitattributesRead(request: HttpRequestData): Boolean = request.url.encodedPath.contains(".gitattributes")

/**
 * A [MockEngine] for tests written before the LFS guard existed: they script only the commit/push calls, so
 * the guard's `.gitattributes` reads are answered with "no such file" (a repo without LFS rules) and everything
 * else goes to [handler] unchanged.
 */
internal fun lfsTolerantMockEngine(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): MockEngine = MockEngine { request ->
    if (isGitattributesRead(request)) respond(content = "", status = HttpStatusCode.NotFound) else handler(request)
}
