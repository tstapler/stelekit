package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import kotlinx.coroutines.flow.StateFlow

/** Resolves a registered graph to its [GraphInfo] without opening or switching to it. */
interface GraphLocator {
    fun locate(id: GraphId): Either<DomainError, GraphInfo>
}

/** Registry-backed [GraphLocator]; reads only the registry snapshot, never touches a driver. */
class RegistryGraphLocator(private val graphRegistry: StateFlow<GraphRegistry>) : GraphLocator {
    override fun locate(id: GraphId): Either<DomainError, GraphInfo> =
        graphRegistry.value.graphs.firstOrNull { it.id == id }?.right()
            ?: DomainError.DatabaseError.NotFound("graph", id.value).left()
}
