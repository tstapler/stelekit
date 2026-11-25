package dev.stapler.stelekit.table

/** Clipboard port for table copy and paste (ADR-008); no Compose types so `table/` stays platform-free. */
interface TableClipboard {
    /** Writes [plainTsv] as plain text, and [html] as rich text where the platform supports it. */
    suspend fun copy(plainTsv: String, html: String?)

    /** Plain text from the system clipboard, or null when empty or unreadable. */
    suspend fun paste(): String?
}
