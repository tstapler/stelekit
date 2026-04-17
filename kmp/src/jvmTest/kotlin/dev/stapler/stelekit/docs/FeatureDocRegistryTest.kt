package dev.stapler.stelekit.docs

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureDocRegistryTest {

    @BeforeTest fun setUp() { FeatureDocRegistry.clearForTesting() }
    @AfterTest fun tearDown() { FeatureDocRegistry.clearForTesting() }

    private class TestDocs : MinimalFeatureDoc {
        override val howTo = HowToContent(title = "Test HowTo", steps = listOf("Step 1"))
        override val reference = ReferenceContent(title = "Test Reference", description = "desc")
    }

    @Test fun `register and retrieve docs instance`() {
        FeatureDocRegistry.register(TestDocs::class) { TestDocs() }
        val retrieved = FeatureDocRegistry.get(TestDocs::class)
        assertNotNull(retrieved)
        val howTo = (retrieved as HowToDoc).howTo
        assertEquals("Test HowTo", howTo.title)
    }

    @Test fun `get returns null for unregistered class`() {
        assertNull(FeatureDocRegistry.get(TestDocs::class))
    }

    @Test fun `registeredClasses returns all registered keys`() {
        FeatureDocRegistry.register(TestDocs::class) { TestDocs() }
        assertEquals(setOf(TestDocs::class), FeatureDocRegistry.registeredClasses())
    }
}
