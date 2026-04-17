package dev.stapler.stelekit.search

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.Result.Companion.success

/**
 * Foundation for Datalog query engine in KMP.
 * Supports basic syntax parsing and execution against repositories.
 */
data class DatalogPattern(
    val entity: String,
    val attribute: String,
    val value: String
)

class DatalogQuery(
    val find: List<String>,
    val where: List<DatalogPattern>,
    val args: Map<String, Any> = emptyMap()
) {
    companion object {
        /**
         * Basic Datalog syntax parser.
         * Supports simple :find and :where clauses with triple patterns.
         * Example: [:find ?b :where [?b :block/content "search"]]
         */
        fun parse(query: String): DatalogQuery {
            val normalized = query.trim().replace("\n", " ")
            
            val findMatch = Regex(":find\\s+(.*?)(?=:where|$)").find(normalized)
            val whereMatch = Regex(":where\\s+(.*)").find(normalized)

            val findVars = findMatch?.groupValues?.get(1)
                ?.split(Regex("\\s+"))
                ?.map { it.trim() }
                ?.filter { it.startsWith("?") } ?: emptyList()
            
            val patterns = mutableListOf<DatalogPattern>()
            whereMatch?.groupValues?.get(1)?.let { whereClause ->
                // Match patterns like [?b :block/content "value"]
                val patternRegex = Regex("\\[\\s*(\\?\\w+)\\s+(:[\\w/]+)\\s+\"?([^\"]+)\"?\\s*\\]")
                patternRegex.findAll(whereClause).forEach { match ->
                    val groups = match.groupValues
                    patterns.add(DatalogPattern(groups[1], groups[2], groups[3]))
                }
            }

            return DatalogQuery(findVars, patterns)
        }
    }
}

/**
 * Engine that executes Datalog queries against the repository layer.
 */
class DatalogEngine(
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository
) {
    /**
     * Executes a Datalog query and returns the results.
     * Currently supports basic block and page filtering by mapping to SearchRequest.
     */
    suspend fun execute(query: DatalogQuery): Result<List<Any>> {
        val results = mutableListOf<Any>()
        
        // Map Datalog patterns to repository operations
        val blockPatterns = query.where.filter { it.attribute.startsWith(":block/") }
        val pagePatterns = query.where.filter { it.attribute.startsWith(":page/") }
        val propertyPatterns = query.where.filter { !it.attribute.startsWith(":") }

        // Example: [?b :block/content "search"] -> SearchRequest(query = "search")
        val contentPattern = blockPatterns.find { it.attribute == ":block/content" }
        val pageNamePattern = pagePatterns.find { it.attribute == ":page/name" }
        
        val propertyFilters = propertyPatterns.associate { it.attribute to it.value }

        // This is where the engine would coordinate with SearchRepository
        // For now, we provide the structure for this integration
        
        return success(results)
    }

    /**
     * Placeholder for advanced query optimization
     */
    private fun optimize(query: DatalogQuery): DatalogQuery {
        return query
    }
}

/**
 * Placeholder for Visual Query Builder integration.
 * This will allow building Datalog queries using a drag-and-drop interface.
 */
class VisualQueryBuilder {
    fun buildFromSchema(schema: Any): DatalogQuery {
        // TODO: Implement visual to datalog transformation
        return DatalogQuery(emptyList(), emptyList())
    }

    fun exportToDatalog(query: DatalogQuery): String {
        val findStr = ":find " + query.find.joinToString(" ")
        val whereStr = ":where " + query.where.joinToString(" ") { 
            "[${it.entity} ${it.attribute} \"${it.value}\"]" 
        }
        return "[$findStr $whereStr]"
    }
}

/**
 * Extension for SearchRepository to support Datalog queries
 */
interface DatalogSearchSupport {
    fun searchWithDatalog(query: String): Flow<Result<List<Any>>>
}
