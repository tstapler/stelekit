package dev.stapler.stelekit.export

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * AWT [Transferable] that offers both HTML and plain-text clipboard flavors.
 *
 * When pasted into rich-text editors (Google Docs, Confluence, Apple Mail), the HTML
 * flavor is used. Plain-text fallbacks (terminals, code editors) receive [plain].
 *
 * Must be passed to [java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents()]
 * on the EDT. Compose Desktop's main dispatcher satisfies this requirement.
 */
class HtmlStringSelection(
    private val html: String,
    private val plain: String
) : Transferable {
    companion object {
        val HTML_FLAVOR = DataFlavor("text/html; charset=UTF-8; class=java.lang.String")
    }

    private val flavors = arrayOf(HTML_FLAVOR, DataFlavor.stringFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavors.any { it.match(flavor) }

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor.match(HTML_FLAVOR) -> html
        flavor == DataFlavor.stringFlavor -> plain
        else -> throw UnsupportedFlavorException(flavor)
    }
}
