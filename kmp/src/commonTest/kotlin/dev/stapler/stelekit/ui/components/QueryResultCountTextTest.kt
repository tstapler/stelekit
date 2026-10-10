package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.query.QueryExecutor
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryResultCountTextTest {
    @Test
    fun `below the ceiling shows the exact count`() {
        assertEquals("0 results", queryResultCountText(0))
        assertEquals("199 results", queryResultCountText(QueryExecutor.DEFAULT_LIMIT - 1))
    }

    @Test
    fun `at the ceiling shows a plus label`() {
        assertEquals("200+ results", queryResultCountText(QueryExecutor.DEFAULT_LIMIT))
    }
}
