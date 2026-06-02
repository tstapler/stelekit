package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

@OptIn(DirectRepositoryWrite::class)
class DatalogReferenceRepository : ReferenceRepository {

    private val references = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val blocks = MutableStateFlow<Map<String, Block>>(emptyMap())

    private val outgoingIndex = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val incomingIndex = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    fun setBlocks(blocksMap: Map<String, Block>) {
        blocks.value = blocksMap
    }

    override fun getOutgoingReferences(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>> {
        return outgoingIndex.map { index ->
            val referencedUuids = index[blockUuid.value] ?: emptySet()
            val allBlocks = blocks.value
            val referencedBlocks = referencedUuids.mapNotNull { allBlocks[it] }
            referencedBlocks.right()
        }
    }

    override fun getIncomingReferences(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>> {
        return incomingIndex.map { index ->
            val referencingUuids = index[blockUuid.value] ?: emptySet()
            val allBlocks = blocks.value
            val referencingBlocks = referencingUuids.mapNotNull { allBlocks[it] }
            referencingBlocks.right()
        }
    }

    override fun getAllReferences(blockUuid: BlockUuid): Flow<Either<DomainError, BlockReferences>> {
        return references.map { refMap ->
            val outgoingUuids = refMap[blockUuid.value] ?: emptySet()
            val incomingUuids = refMap.entries
                .filter { it.value.contains(blockUuid.value) }
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

    override suspend fun addReference(fromBlockUuid: BlockUuid, toBlockUuid: BlockUuid): Either<DomainError, Unit> {
        return try {
            val current = references.value.toMutableMap()
            val existing = current[fromBlockUuid.value]?.toMutableSet() ?: mutableSetOf()
            existing.add(toBlockUuid.value)
            current[fromBlockUuid.value] = existing
            references.value = current

            updateIndexes(fromBlockUuid.value, toBlockUuid.value, add = true)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun removeReference(fromBlockUuid: BlockUuid, toBlockUuid: BlockUuid): Either<DomainError, Unit> {
        return try {
            val current = references.value.toMutableMap()
            val existing = current[fromBlockUuid.value]?.toMutableSet() ?: return Unit.right()
            existing.remove(toBlockUuid.value)
            if (existing.isEmpty()) {
                current.remove(fromBlockUuid.value)
            } else {
                current[fromBlockUuid.value] = existing
            }
            references.value = current

            updateIndexes(fromBlockUuid.value, toBlockUuid.value, add = false)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun getOrphanedBlocks(): Flow<Either<DomainError, List<Block>>> {
        return references.map { refMap ->
            val allReferenced = refMap.values.flatten().toSet()
            val orphaned = blocks.value.values.filter { it.uuid.value !in allReferenced }
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
