package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropZoneInteropTest {

    @Test
    fun isImageFileName_returnsTrueForEachSupportedExtensionCaseInsensitive() {
        val supported = listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp")
        for (ext in supported) {
            assertTrue("photo.$ext".isImageFileName(), "expected .$ext to be recognized as an image")
            assertTrue("PHOTO.${ext.uppercase()}".isImageFileName(), "expected uppercase .$ext to be recognized")
        }
    }

    @Test
    fun isImageFileName_returnsFalseForNonImageExtension() {
        assertFalse("notes.txt".isImageFileName())
        assertFalse("archive.zip".isImageFileName())
        assertFalse("no-extension".isImageFileName())
    }

    @Test
    fun installBodyDropListener_preventsDefaultBrowserNavigation_onSyntheticDropEvent() {
        installBodyDropListener { }

        val defaultPrevented = dispatchSyntheticDropAndCheckDefaultPrevented()

        assertTrue(defaultPrevented, "drop handler must call preventDefault() so the browser never navigates away")
    }
}

private fun dispatchSyntheticDropAndCheckDefaultPrevented(): Boolean = js("""
    (function() {
        var dt = new DataTransfer();
        var event = new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt });
        document.body.dispatchEvent(event);
        return event.defaultPrevented;
    })()
""")
