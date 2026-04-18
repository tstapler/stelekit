package dev.stapler.stelekit.performance

import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository

/**
 * Wraps [repo] with OpenTelemetry instrumentation when available on the current platform.
 * On platforms where OTel is not supported (e.g. wasmJs), returns [repo] unchanged.
 */
expect fun wrapWithOtelIfAvailable(repo: PageRepository, tracerName: String): PageRepository

/**
 * Wraps [repo] with OpenTelemetry instrumentation when available on the current platform.
 * On platforms where OTel is not supported (e.g. wasmJs), returns [repo] unchanged.
 */
expect fun wrapWithOtelIfAvailable(repo: SearchRepository, tracerName: String): SearchRepository
