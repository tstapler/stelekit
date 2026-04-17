package dev.stapler.stelekit.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimestampParserTest {

    @Test
    fun `test parse scheduled`() {
        val input = "Buy milk SCHEDULED: <2023-10-27 Fri>"
        val result = TimestampParser.parse(input)
        
        assertEquals("Buy milk", result.content)
        assertEquals("2023-10-27 Fri", result.scheduled)
        assertNull(result.deadline)
    }

    @Test
    fun `test parse deadline`() {
        val input = "Submit report DEADLINE: <2023-10-31 Tue>"
        val result = TimestampParser.parse(input)
        
        assertEquals("Submit report", result.content)
        assertEquals("2023-10-31 Tue", result.deadline)
        assertNull(result.scheduled)
    }

    @Test
    fun `test parse both`() {
        val input = "Project task SCHEDULED: <2023-10-27 Fri> DEADLINE: <2023-10-31 Tue>"
        val result = TimestampParser.parse(input)
        
        assertEquals("Project task", result.content)
        assertEquals("2023-10-27 Fri", result.scheduled)
        assertEquals("2023-10-31 Tue", result.deadline)
    }

    @Test
    fun `test parse reversed order`() {
        val input = "Project task DEADLINE: <2023-10-31 Tue> SCHEDULED: <2023-10-27 Fri>"
        val result = TimestampParser.parse(input)
        
        assertEquals("Project task", result.content)
        assertEquals("2023-10-27 Fri", result.scheduled)
        assertEquals("2023-10-31 Tue", result.deadline)
    }

    @Test
    fun `test parse with time`() {
        val input = "Meeting SCHEDULED: <2023-10-27 Fri 10:00>"
        val result = TimestampParser.parse(input)
        
        assertEquals("Meeting", result.content)
        assertEquals("2023-10-27 Fri 10:00", result.scheduled)
    }

    @Test
    fun `test no timestamps`() {
        val input = "Just a normal task"
        val result = TimestampParser.parse(input)
        
        assertEquals("Just a normal task", result.content)
        assertNull(result.scheduled)
        assertNull(result.deadline)
    }
}
