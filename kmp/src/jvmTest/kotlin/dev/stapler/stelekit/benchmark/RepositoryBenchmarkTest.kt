package dev.stapler.stelekit.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RepositoryBenchmarkTest {

    // For now, we'll create a simple test without actual database setup
    // since the repositories are currently stubbed

    @Test
    fun testBenchmarkFrameworkStructure() = runBlocking {
        // For now, we'll test the framework without actual repositories
        // since the implementations are stubbed

        println("Testing benchmark framework structure...")

        // Test that we can create benchmark result data classes
        val result = RepositoryBenchmark.BenchmarkResult(
            operation = "test-operation",
            duration = kotlin.time.Duration.ZERO,
            success = true,
            metadata = mapOf("test" to "value")
        )

        assertEquals("test-operation", result.operation)
        assertTrue(result.success)
        assertEquals(0L, result.duration.inWholeMilliseconds)
        assertEquals("value", result.metadata["test"])

        // Test summary data class
        val summary = RepositoryBenchmark.BenchmarkSummary(
            totalOperations = 1,
            successfulOperations = 1,
            failedOperations = 0,
            totalDuration = 100,
            averageDuration = 100,
            results = listOf(result)
        )

        assertEquals(1, summary.totalOperations)
        assertEquals(1, summary.successfulOperations)
        assertEquals(0, summary.failedOperations)

        println("Benchmark framework structure test passed")
    }

    @Test
    fun testBenchmarkDataClasses() {
        // Test all data classes work correctly
        val result = RepositoryBenchmark.BenchmarkResult(
            operation = "test-operation",
            duration = kotlin.time.Duration.ZERO,
            success = true,
            metadata = mapOf("test" to "value")
        )

        assertEquals("test-operation", result.operation)
        assertTrue(result.success)
        assertEquals("value", result.metadata["test"])

        val summary = RepositoryBenchmark.BenchmarkSummary(
            totalOperations = 1,
            successfulOperations = 1,
            failedOperations = 0,
            totalDuration = 150,
            averageDuration = 150,
            results = listOf(result)
        )

        assertEquals(1, summary.totalOperations)
        assertEquals(1, summary.successfulOperations)
        assertEquals(0, summary.failedOperations)
        assertEquals(150L, summary.totalDuration)
        assertEquals(150L, summary.averageDuration)
    }
}