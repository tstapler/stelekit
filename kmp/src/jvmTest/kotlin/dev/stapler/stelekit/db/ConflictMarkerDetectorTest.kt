package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictMarkerDetectorTest {

    @Test
    fun clean_content_returns_false() {
        val content = """
            # My Page
            - Some block content
            - Another block
        """.trimIndent()
        assertFalse(ConflictMarkerDetector.hasConflictMarkers(content))
    }

    @Test
    fun content_with_start_and_end_markers_returns_true() {
        val content = """
            # My Page
            <<<<<<< HEAD
            - Our version of the block
            =======
            - Their version of the block
            >>>>>>> feature-branch
        """.trimIndent()
        assertTrue(ConflictMarkerDetector.hasConflictMarkers(content))
    }

    @Test
    fun content_with_only_start_marker_returns_false() {
        val content = """
            # My Page
            <<<<<<< HEAD
            - Some block content
        """.trimIndent()
        assertFalse(ConflictMarkerDetector.hasConflictMarkers(content))
    }

    @Test
    fun content_with_equals_separator_alone_returns_false() {
        val content = """
            ---
            title: My Page
            ---
            =======
            - Some block content
        """.trimIndent()
        assertFalse(ConflictMarkerDetector.hasConflictMarkers(content))
    }

    @Test
    fun conflict_markers_inside_code_fence_still_returns_true() {
        val content = """
            # My Page
            - Here is an example:
            ```
            <<<<<<< HEAD
            int x = 1;
            =======
            int x = 2;
            >>>>>>> feature-branch
            ```
        """.trimIndent()
        assertTrue(ConflictMarkerDetector.hasConflictMarkers(content))
    }
}
