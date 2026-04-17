package dev.stapler.stelekit.browser

import dev.stapler.stelekit.logging.Logger

/**
 * Browser entry point for Logseq Kotlin Multiplatform Web application.
 * 
 * This provides a minimal web UI demonstrating the multiplatform setup.
 */
@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun main() {
    val logger = Logger("BrowserMain")

    // Use plain JavaScript interop for browser DOM access
    val document = js("document")
    
    logger.info("Logseq KMP Web initialized")
    
    // Get the root element
    val root = document.getElementById("compose-root")
    
    if (root != null) {
        // Create and append content using template strings
        root.innerHTML = """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 24px; max-width: 800px; margin: 0 auto;">
                <h1 style="font-size: 28px; font-weight: bold; margin-bottom: 8px;">Logseq</h1>
                <p style="color: #666; margin-bottom: 24px;">Privacy-first knowledge management platform</p>
                <hr style="border: none; border-top: 1px solid #e0e0e0; margin: 16px 0;">
                <div style="background: #fff; border-radius: 8px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); margin-bottom: 16px;">
                    <h2 style="font-size: 16px; font-weight: bold; margin-bottom: 12px;">Getting Started</h2>
                    <ul style="list-style: none; padding: 0;">
                        <li style="padding: 4px 0; font-size: 14px; color: #444;">✓ Kotlin Multiplatform Project Compiled</li>
                        <li style="padding: 4px 0; font-size: 14px; color: #444;">✓ Cross-platform PlatformFileSystem</li>
                        <li style="padding: 4px 0; font-size: 14px; color: #444;">✓ Coroutine Dispatchers</li>
                        <li style="padding: 4px 0; font-size: 14px; color: #444;">✓ Repository Layer</li>
                    </ul>
                </div>
                <div style="padding: 16px; background: #e3f2fd; border-radius: 8px; margin-bottom: 16px;">
                    <p style="font-size: 14px; color: #1565c0;"><strong>Note:</strong> This is a basic web demo. For full Compose UI, add Compose for Web HTML library.</p>
                </div>
                <div>
                    <h3 style="font-size: 14px; font-weight: bold; color: #666; margin-bottom: 8px;">Project Targets</h3>
                    <div style="display: flex; gap: 8px; flex-wrap: wrap;">
                        <span style="background: #4caf50; color: white; padding: 4px 12px; border-radius: 4px; font-size: 12px;">JVM Desktop ✓</span>
                        <span style="background: #ff9800; color: white; padding: 4px 12px; border-radius: 4px; font-size: 12px;">JS Web ✓</span>
                        <span style="background: #2196f3; color: white; padding: 4px 12px; border-radius: 4px; font-size: 12px;">Android</span>
                        <span style="background: #9c27b0; color: white; padding: 4px 12px; border-radius: 4px; font-size: 12px;">iOS</span>
                    </div>
                </div>
            </div>
        """.trimIndent()
    }
}

/**
 * Data class for pages (shared across platforms)
 */
data class Page(
    val id: Long,
    val uuid: String,
    val name: String,
    val namespace: String?,
    val filePath: String?,
    val createdAt: kotlin.time.Instant,
    val updatedAt: kotlin.time.Instant
)
