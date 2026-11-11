package dev.stapler.stelekit.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryItemActionsTest {
    private fun item(type: String, name: String = "a b.png", rel: String = "../assets/a b.png") = LibraryItem(
        id = "x", filePath = "/g/assets/$name", fileName = name, mediaType = type, sizeBytes = 1, importedAtMs = 1,
        isOrphan = false, pageUuids = emptyList(), annotated = false, measurementCount = 0, lastAnnotatedAtMs = null,
        annotationUuid = null, relativePath = rel,
    )

    @Test
    fun imagesAndDrawingsEmbed_otherTypesLink() {
        assertEquals("![a b.png](../assets/a b.png)", LibraryItemActions.markdownLink(item("IMAGE")))
        assertEquals("![a b.png](../assets/a b.png)", LibraryItemActions.markdownLink(item("DRAWING")))
        assertEquals("[a b.png](../assets/a b.png)", LibraryItemActions.markdownLink(item("PDF")))
    }

    @Test
    fun aMissingRelativePathFallsBackToTheFileName() {
        assertEquals("[doc.pdf](doc.pdf)", LibraryItemActions.markdownLink(item("PDF", "doc.pdf", rel = "")))
    }
}
