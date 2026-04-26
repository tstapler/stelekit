package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

@OptIn(DirectRepositoryWrite::class)
class DatascriptReferenceRepository : ReferenceRepository {

    private val references = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val blocks = MutableStateFlow<Map<String, Block>>(emptyMap())

    private val outgoingIndex = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val incomingIndex = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    fun setBlocks(blocksMap: Map<String, Block>) {
        blocks.value = blocksMap
    }

    override fun getOutgoingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return outgoingIndex.map { index ->
            val referencedUuids = index[blockUuid] ?: emptySet()
            val allBlocks = blocks.value
            val referencedBlocks = referencedUuids.mapNotNull { allBlocks[it] }
            referencedBlocks.right()
        }
    }

    override fun getIncomingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>> {
        return incomingIndex.map { index ->
            val referencingUuids = index[blockUuid] ?: emptySet()
            val allBlocks = blocks.value
            val referencingBlocks = referencingUuids.mapNotNull { allBlocks[it] }
            referencingBlocks.right()
        }
    }

    override fun getAllReferences(blockUuid: String): Flow<Either<DomainError, BlockReferences>> {
        return references.map { refMap ->
            val outgoingUuids = refMap[blockUuid] ?: emptySet()
            val incomingUuids = refMap.entries
                .filter { it.value.contains(blockUuid) }
                .map { it.key }
                .toSet()
            val allBlocks = blocks.value
            val outgoing = outgoingUuids.mapNotNull { allBlocks[it] }
            val incoming = incomingUuids.mapNotNull { allBlocks[it] }
            BlockReferences(outgoing, incoming).right()
        }
    }

    private fun updateIndexes(fromBlockUuid: String, toBlockUuid: String, add: Boolean) {
        val currentOutgoing = outgoingIndex.value.toMutableMap()
        val currentIncoming = incomingIndex.value.toMutableMap()

        if (add) {
            val outgoingSet = currentOutgoing[fromBlockUuid]?.toMutableSet() ?: mutableSetOf()
            outgoingSet.add(toBlockUuid)
            currentOutgoing[fromBlockUuid] = outgoingSet

            val incomingSet = currentIncoming[toBlockUuid]?.toMutableSet() ?: mutableSetOf()
            incomingSet.add(fromBlockUuid)
            currentIncoming[toBlockUuid] = incomingSet
        } else {
            currentOutgoing[fromBlockUuid]?.let { set ->
                val newSet = set.toMutableSet()
                newSet.remove(toBlockUuid)
                if (newSet.isEmpty()) {
                    currentOutgoing.remove(fromBlockUuid)
                } else {
                    currentOutgoing[fromBlockUuid] = newSet
                }
            }

            currentIncoming[toBlockUuid]?.let { set ->
                val newSet = set.toMutableSet()
                newSet.remove(fromBlockUuid)
                if (newSet.isEmpty()) {
                    currentIncoming.remove(toBlockUuid)
                } else {
                    currentIncoming[toBlockUuid] = newSet
                }
            }
        }

        outgoingIndex.value = currentOutgoing
        incomingIndex.value = currentIncoming
    }

    override suspend fun addReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit> {
        return try {
            val current = references.value.toMutableMap()
            val existing = current[fromBlockUuid]?.toMutableSet() ?: mutableSetOf()
            existing.add(toBlockUuid)
            current[fromBlockUuid] = existing
            references.value = current

            updateIndexes(fromBlockUuid, toBlockUuid, add = true)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun removeReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit> {
        return try {
            val current = references.value.toMutableMap()
            val existing = current[fromBlockUuid]?.toMutableSet() ?: return Unit.right()
            existing.remove(toBlockUuid)
            if (existing.isEmpty()) {
                current.remove(fromBlockUuid)
            } else {
                current[fromBlockUuid] = existing
            }
            references.value = current

            updateIndexes(fromBlockUuid, toBlockUuid, add = false)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun getOrphanedBlocks(): Flow<Either<DomainError, List<Block>>> {
        return references.map { refMap ->
            val allReferenced = refMap.values.flatten().toSet()
            val orphaned = blocks.value.values.filter { it.uuid !in allReferenced }
            orphaned.right()
        }
    }

    override fun getMostConnectedBlocks(limit: Int): Flow<Either<DomainError, List<BlockWithReferenceCount>>> {
        return references.map { refMap ->
            val referenceCounts = mutableMapOf<String, Int>()
            refMap.forEach { (from, toSet) ->
                referenceCounts[from] = (referenceCounts[from] ?: 0) + toSet.size
                toSet.forEach { to ->
                    referenceCounts[to] = (referenceCounts[to] ?: 0) + 1
                }
            }
            val allBlocks = blocks.value
            val sorted = referenceCounts.entries
                .mapNotNull { (uuid, count) -> allBlocks[uuid]?.let { BlockWithReferenceCount(it, count) } }
                .sortedByDescending { it.referenceCount }
                .take(limit)
            sorted.right()
        }
    }
}
