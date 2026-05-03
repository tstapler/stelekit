@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Clean benchmark framework for testing repository performance.
 * Focuses on measuring basic CRUD operations and query performance.
 */
class RepositoryBenchmark(
    private val blockRepo: BlockRepository?,
    private val pageRepo: PageRepository?
) {

    data class BenchmarkResult(
        val operation: String,
        val duration: Duration,
        val success: Boolean,
        val metadata: Map<String, Any> = emptyMap()
    )

    data class BenchmarkSummary(
        val totalOperations: Int,
        val successfulOperations: Int,
        val failedOperations: Int,
        val totalDuration: Long,
        val averageDuration: Long,
        val results: List<BenchmarkResult>
    )

    private val results = mutableListOf<BenchmarkResult>()

    /**
     * Run all benchmark tests
     */
    suspend fun runAllBenchmarks(): BenchmarkSummary {
        results.clear()

        if (blockRepo != null && pageRepo != null) {
            runBlockBenchmarks()
            runPageBenchmarks()
            runQueryBenchmarks()
        } else {
            // Add placeholder results for structure testing
            results.add(BenchmarkResult("Block operations", Duration.ZERO, true, mapOf("note" to "Skipped - no repositories")))
            results.add(BenchmarkResult("Hierarchy operations", Duration.ZERO, true, mapOf("note" to "Skipped - no repositories")))
            results.add(BenchmarkResult("Reference operations", Duration.ZERO, true, mapOf("note" to "Skipped - no repositories")))
            results.add(BenchmarkResult("Page operations", Duration.ZERO, true, mapOf("note" to "Skipped - no repositories")))
        }

        return createSummary()
    }

    private suspend fun runBlockBenchmarks() {
        println("\n📝 Block Operations Benchmarks:")

        // Test block creation (currently stubbed to return success)
        benchmarkOperation("Create 3 blocks") {
            if (blockRepo != null) {
                val testBlocks = (1..3).map { i ->
                    dev.stapler.stelekit.model.Block(
                        uuid = "test-block-$i",
                        pageUuid = "test-page-1",
                        parentUuid = if (i > 1) "test-block-${i - 1}" else null,
                        leftUuid = null,
                        content = "Test block content $i",
                        level = if (i > 1) 1 else 0,
                        position = i,
                        createdAt = kotlin.time.Clock.System.now(),
                        updatedAt = kotlin.time.Clock.System.now(),
                        properties = mapOf("test" to "value$i")
                    )
                }
                testBlocks.forEach { block ->
                    blockRepo.saveBlock(block)
                }
            }
        }

        // Test block retrieval (currently stubbed)
        benchmarkOperation("Retrieve single block by UUID") {
            blockRepo?.getBlockByUuid("test-block-1")
        }

        // Test block children (current API)
        benchmarkOperation("Get Block Children") {
            blockRepo?.getBlockChildren("test-uuid")
        }

        // Test block hierarchy (current API)
        benchmarkOperation("Get Block Hierarchy") {
            blockRepo?.getBlockHierarchy("test-block-1")
        }
    }

    private suspend fun runPageBenchmarks() {
        println("\n📄 Page Operations Benchmarks:")

        // Test page creation (currently stubbed)
        benchmarkOperation("Create 2 pages") {
            if (pageRepo != null) {
                val testPages = (1..2).map { i ->
                    dev.stapler.stelekit.model.Page(
                        uuid = "test-page-$i",
                        name = "test-page-$i",
                        namespace = if (i % 2 == 0) "test" else null,
                        filePath = "/test/page$i.md",
                        createdAt = kotlin.time.Clock.System.now(),
                        updatedAt = kotlin.time.Clock.System.now(),
                        properties = mapOf("type" to "test")
                    )
                }
                testPages.forEach { page ->
                    pageRepo.savePage(page)
                }
            }
        }

        benchmarkOperation("Retrieve page by UUID") {
            pageRepo?.getPageByUuid("test-page-1")
        }

        benchmarkOperation("Retrieve page by name") {
            pageRepo?.getPageByName("test-page-1")
        }
    }

    private suspend fun runQueryBenchmarks() {
        println("\n🔍 Query Performance Benchmarks:")

        // Test page filtering by name (current API)
        benchmarkOperation("Find Page by Name") {
            pageRepo?.getPageByName("test-page-1")
        }

        // Test pages in namespace (current API)
        benchmarkOperation("Find Pages in Namespace") {
            pageRepo?.getPagesInNamespace("test")
        }

        // Test recent pages (current API)
        benchmarkOperation("Get Recent Pages") {
            pageRepo?.getRecentPages(10)
        }

        // Test block children (current API)
        benchmarkOperation("Get Block Children") {
            blockRepo?.getBlockChildren("test-block-1")
        }
    }

    private suspend fun benchmarkOperation(operationName: String, block: suspend () -> Any?) {
        try {
            val duration = measureTime {
                val result = block()
                if (result is kotlinx.coroutines.flow.Flow<*>) {
                    (result as kotlinx.coroutines.flow.Flow<Any>).first()
                }
            }

            val result = BenchmarkResult(
                operation = operationName,
                duration = duration,
                success = true,
                metadata = mapOf("durationMs" to duration.inWholeMilliseconds)
            )

            results.add(result)
            println("  ✅ $operationName: ${duration.inWholeMilliseconds}ms")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = BenchmarkResult(
                operation = operationName,
                duration = Duration.ZERO,
                success = false,
                metadata = mapOf("error" to (e.message ?: "Unknown error"))
            )

            results.add(result)
            println("  ❌ $operationName: FAILED - ${e.message}")
        }
    }

    private fun createSummary(): BenchmarkSummary {
        val successful = results.filter { it.success }
        val failed = results.filter { !it.success }

        val summary = BenchmarkSummary(
            totalOperations = results.size,
            successfulOperations = successful.size,
            failedOperations = failed.size,
            totalDuration = successful.sumOf { it.duration.inWholeMilliseconds },
            averageDuration = if (successful.isNotEmpty()) {
                successful.sumOf { it.duration.inWholeMilliseconds } / successful.size
            } else 0,
            results = results.toList()
        )

        printSummary(summary)
        return summary
    }

    private fun printSummary(summary: BenchmarkSummary) {
        println("\n" + "=" * 50)
        println("📊 BENCHMARK RESULTS SUMMARY")
        println("=" * 50)
        println("Total Operations: ${summary.totalOperations}")
        println("Successful: ${summary.successfulOperations}")
        println("Failed: ${summary.failedOperations}")
        println("Total Time: ${summary.totalDuration}ms")
        println("Average Time: ${summary.averageDuration}ms")

        if (summary.failedOperations > 0) {
            println("\n❌ Failed Operations:")
            summary.results.filter { !it.success }.forEach { result ->
                println("  - ${result.operation}: ${result.metadata["error"]}")
            }
        }

        println("\n✅ Benchmark Complete!")
    }

    private operator fun String.times(count: Int): String {
        return this.repeat(count)
    }
}