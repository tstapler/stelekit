package dev.stapler.stelekit.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMediaClassTest {
    @Test
    fun membershipTable_followsLD11() {
        assertEquals(LibraryMediaClass.IMAGES, LibraryMediaClass.of("IMAGE"))
        assertEquals(LibraryMediaClass.PDFS, LibraryMediaClass.of("PDF"))
        assertEquals(LibraryMediaClass.AUDIO, LibraryMediaClass.of("AUDIO"))
        assertEquals(LibraryMediaClass.VIDEO, LibraryMediaClass.of("VIDEO"))
        assertEquals(LibraryMediaClass.DOCS, LibraryMediaClass.of("DOCUMENT"))
        assertEquals(LibraryMediaClass.SKETCHES, LibraryMediaClass.of("DRAWING"))
    }

    @Test
    fun fileAndUnrecognisedValuesAreOther() {
        assertEquals(LibraryMediaClass.OTHER, LibraryMediaClass.of("FILE"))
        assertEquals(LibraryMediaClass.OTHER, LibraryMediaClass.of("SOMETHING_NEW"))
        assertEquals(LibraryMediaClass.OTHER, LibraryMediaClass.of(""))
    }
}
