package dev.stapler.stelekit.editor

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.ui.components.EditorCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * TC-PHOTO-006: Verifies the EditorCapabilities.onAttachImage contract.
 *
 * Root cause of the silent-drop bug: onAttachImage previously accepted ((BlockUuid?) -> Unit),
 * allowing the toolbar to invoke it with null when no block was focused. The file was copied to
 * assets but never inserted; no error was shown. The fix promotes the parameter to non-nullable
 * (BlockUuid) so the toolbar cannot reach the picker without a valid target block — the wrong
 * path is a compile error, not a silent runtime no-op.
 *
 * This test locks in the success path: when onAttachImage is invoked with a non-null BlockUuid,
 * the callback receives that exact UUID.
 */
class ImageAttachCallbackContractTest {

    @Test
    fun onAttachImageCallbackReceivesExactBlockUuid() {
        var captured: BlockUuid? = null
        val capabilities = EditorCapabilities(
            onAttachImage = { uuid: BlockUuid -> captured = uuid },
        )
        val expected = BlockUuid("block-uuid-001")
        capabilities.onAttachImage?.invoke(expected)
        assertNotNull(captured, "onAttachImage callback must fire when invoked with a non-null UUID")
        assertEquals(expected, captured)
    }

    @Test
    fun onAttachImageIsNullWhenCapabilitiesOmitIt() {
        val capabilities = EditorCapabilities()
        assertEquals(null, capabilities.onAttachImage, "omitting onAttachImage must leave it null (toolbar hides the button)")
    }
}
