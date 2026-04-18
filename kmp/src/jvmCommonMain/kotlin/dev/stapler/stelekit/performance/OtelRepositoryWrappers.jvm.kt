package dev.stapler.stelekit.performance

import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository
import io.opentelemetry.api.trace.Tracer

actual fun wrapWithOtelIfAvailable(repo: PageRepository, tracerName: String): PageRepository {
    if (!OtelProvider.isInitialized) return repo
    val tracer = OtelProvider.getTracer(tracerName) as Tracer
    return InstrumentedPageRepository(repo, tracer)
}

actual fun wrapWithOtelIfAvailable(repo: SearchRepository, tracerName: String): SearchRepository {
    if (!OtelProvider.isInitialized) return repo
    val tracer = OtelProvider.getTracer(tracerName) as Tracer
    return InstrumentedSearchRepository(repo, tracer)
}
