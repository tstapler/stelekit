package dev.stapler.stelekit

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the `home_sketch_data_as_resources` bundle (BUILD.bazel) / Gradle resources expose the fixture
 * directory on the test classpath. Fails under Bazel until the filegroup and java_library exist.
 */
class HomeSketchFixtureVisibilityTest {
    @Test
    fun `fixture manifest is visible on the test classpath`() {
        val stream = HomeSketchFixtureVisibilityTest::class.java.classLoader
            .getResourceAsStream("home-sketch/closet/fixture-manifest.json")
        assertTrue(stream != null, "home-sketch/closet/fixture-manifest.json not on the test classpath")
        val text = stream.bufferedReader().use { it.readText() }
        assertTrue("\"fixture\": \"closet\"" in text, "manifest is not the closet manifest")
    }
}
