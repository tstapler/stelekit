package dev.stapler.stelekit.performance

import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository

actual fun wrapWithOtelIfAvailable(repo: PageRepository, tracerName: String): PageRepository = repo

actual fun wrapWithOtelIfAvailable(repo: SearchRepository, tracerName: String): SearchRepository = repo
