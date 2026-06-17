package dev.stapler.stelekit.performance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class QueryPlanRow(val id: Int, val parentId: Int, val detail: String)

@Serializable
data class QueryPlanEntry(
    val sql: String,
    val rows: List<QueryPlanRow>,
)

class QueryPlanRepository(private val driver: SqlDriver) {

    fun explain(sql: String): List<QueryPlanRow> {
        // Replace ? placeholders with NULL so EXPLAIN QUERY PLAN accepts the SQL without binding
        val normalized = sql.trim().replace(Regex("\\?"), "NULL")
        return try {
            driver.executeQuery(
                identifier = null,
                sql = "EXPLAIN QUERY PLAN $normalized",
                mapper = { cursor ->
                    QueryResult.Value(buildList {
                        while (cursor.next().value) {
                            add(QueryPlanRow(
                                id = cursor.getLong(0)?.toInt() ?: 0,
                                parentId = cursor.getLong(1)?.toInt() ?: 0,
                                detail = cursor.getString(3) ?: "",
                            ))
                        }
                    })
                },
                parameters = 0,
            ).value
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Explains all entries in [sqlSamples] (key = "table:operation", value = sample SQL).
     * Returns [QueryPlanEntry] so the UI and export can show the SQL alongside its plan.
     */
    fun explainAll(sqlSamples: Map<String, String>): Map<String, QueryPlanEntry> =
        sqlSamples.mapValues { (_, sql) -> QueryPlanEntry(sql = sql, rows = explain(sql)) }
}
