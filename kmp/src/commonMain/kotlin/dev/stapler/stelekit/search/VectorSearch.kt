package dev.stapler.stelekit.search

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.BlockWithDepth
import kotlinx.coroutines.flow.firstOrNull
import kotlin.math.sqrt

/**
 * Interface for text embedding and inference operations.
 * Can be implemented by a web worker (JS) or a local library (JVM/Native).
 */
interface InferenceWorker {
    /**
     * Get embedding for a single text string.
     */
    suspend fun getEmbedding(text: String): FloatArray

    /**
     * Get embeddings for multiple text strings in batch.
     */
    suspend fun getEmbeddings(texts: List<String>): List<FloatArray>
}

/**
 * Bridge between vector search logic and the block repository.
 * Handles semantic search using text embeddings.
 */
class VectorSearch(
    private val blockRepository: BlockRepository,
    private val inferenceWorker: InferenceWorker
) {
    /**
     * Search for blocks semantically similar to the query.
     * 
     * @param query The search query string
     * @param blocks The candidate blocks to search through
     * @param limit Maximum number of results to return
     * @return List of blocks with their similarity scores, sorted by similarity descending
     */
    suspend fun search(
        query: String,
        blocks: List<Block>,
        limit: Int = 10
    ): List<Pair<Block, Double>> {
        if (blocks.isEmpty()) return emptyList()

        val queryEmbedding = inferenceWorker.getEmbedding(query)
        val blockTexts = blocks.map { it.content }
        val blockEmbeddings = inferenceWorker.getEmbeddings(blockTexts)

        return blocks.zip(blockEmbeddings)
            .map { (block, embedding) ->
                block to cosineSimilarity(queryEmbedding, embedding)
            }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * Search for blocks semantically similar to the query within a specific hierarchy.
     * 
     * @param query The search query string
     * @param rootUuid The UUID of the root block of the hierarchy
     * @param limit Maximum number of results to return
     * @return List of blocks with their similarity scores
     */
    suspend fun searchInHierarchy(
        query: String,
        rootUuid: String,
        limit: Int = 10
    ): List<Pair<Block, Double>> {
        val hierarchyResult = blockRepository.getBlockHierarchy(rootUuid).firstOrNull()
        val blocks = hierarchyResult?.getOrNull()?.map { it.block } ?: return emptyList()
        return search(query, blocks, limit)
    }

    /**
     * Calculate cosine similarity between two vectors.
     * 
     * @param v1 First vector
     * @param v2 Second vector
     * @return Cosine similarity score between -1.0 and 1.0
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Double {
        if (v1.size != v2.size || v1.isEmpty()) return 0.0
        
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        for (i in v1.indices) {
            val x = v1[i].toDouble()
            val y = v2[i].toDouble()
            dotProduct += x * y
            norm1 += x * x
            norm2 += y * y
        }
        
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0.0
    }
}

/**
 * A placeholder implementation of InferenceWorker that returns deterministic 
 * pseudo-random embeddings based on the input text.
 * Useful for testing and as a fallback for local implementation.
 */
class PlaceholderInferenceWorker(private val dimension: Int = 384) : InferenceWorker {
    override suspend fun getEmbedding(text: String): FloatArray {
        // Deterministic pseudo-random vector based on text hash
        val seed = text.hashCode().toLong()
        val random = kotlin.random.Random(seed)
        return FloatArray(dimension) { random.nextFloat() * 2 - 1 }
    }

    override suspend fun getEmbeddings(texts: List<String>): List<FloatArray> {
        return texts.map { getEmbedding(it) }
    }
}

/**
 * An InferenceWorker that communicates with a platform-specific worker.
 * This is a bridge that can be implemented using platform-specific delegates.
 */
class RemoteInferenceWorker(
    private val bridge: InferenceBridge
) : InferenceWorker {
    override suspend fun getEmbedding(text: String): FloatArray {
        return bridge.requestEmbedding(text)
    }

    override suspend fun getEmbeddings(texts: List<String>): List<FloatArray> {
        return bridge.requestEmbeddings(texts)
    }
}

/**
 * Interface for the platform-specific bridge (e.g., Web Worker, native library).
 */
interface InferenceBridge {
    suspend fun requestEmbedding(text: String): FloatArray
    suspend fun requestEmbeddings(texts: List<String>): List<FloatArray>
}
