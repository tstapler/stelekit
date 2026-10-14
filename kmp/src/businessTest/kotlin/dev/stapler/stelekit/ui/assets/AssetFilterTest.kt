package dev.stapler.stelekit.ui.assets

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetFilterTest {
    // Class-init order is per-JVM, so this only fails pre-fix when it runs before anything else
    // touches AssetFilter; AssetBrowserScreenTest and AssetBrowserE2ETest cover the crash itself.
    @Test fun `all lists every static variant in order and none is null`() {
        val expected = listOf(
            AssetFilter.ALL, AssetFilter.IMAGES, AssetFilter.PDFS, AssetFilter.AUDIO,
            AssetFilter.VIDEO, AssetFilter.DOCUMENTS, AssetFilter.FILES, AssetFilter.ORPHANED,
        )
        assertEquals(expected, AssetFilter.all)
        AssetFilter.all.forEach { it.displayName() }
    }
}
