package dev.stapler.stelekit.performance

import kotlin.test.Test
import kotlin.test.assertEquals

class HistogramWriterTest {

    @Test
    fun `classifyBucket maps 0ms to bucket 0`() =
        assertEquals(0L, HistogramWriter.classifyBucket(0L))

    @Test
    fun `classifyBucket maps 16ms to bucket 16`() =
        assertEquals(16L, HistogramWriter.classifyBucket(16L))

    @Test
    fun `classifyBucket maps 17ms to bucket 33`() =
        assertEquals(33L, HistogramWriter.classifyBucket(17L))

    @Test
    fun `classifyBucket maps 100ms to bucket 100`() =
        assertEquals(100L, HistogramWriter.classifyBucket(100L))

    @Test
    fun `classifyBucket maps 5001ms to bucket 9999 overflow`() =
        assertEquals(9999L, HistogramWriter.classifyBucket(5001L))

    @Test
    fun `classifyBucket maps 9999ms to bucket 9999`() =
        assertEquals(9999L, HistogramWriter.classifyBucket(9999L))
}
