package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.SqlCursor
import kotlinx.coroutines.CancellationException
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.Property
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Extension functions for converting SQLDelight cursors to domain models.
 * Updated to use UUID-native storage.
 */

/**
 * Convert SQL cursor to Block model
 */
fun SqlCursor.toBlock(): Block {
    return Block(
        uuid = getString(1) ?: "", // uuid
        pageUuid = getString(2) ?: "", // page_uuid
        parentUuid = getString(3), // parent_uuid
        leftUuid = getString(4), // left_uuid
        content = getString(5) ?: "", // content
        level = getLong(6)?.toInt() ?: 0, // level
        position = getLong(7)?.toInt() ?: 0, // position
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(getLong(8) ?: 0L), // created_at
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(getLong(9) ?: 0L), // updated_at
        properties = getString(10)?.let { parseJsonProperties(it) } ?: emptyMap(), // properties
        contentHash = getString(12) // content_hash (nullable)
    )
}

/**
 * Convert SQL cursor to Page model
 */
fun SqlCursor.toPage(): Page {
    return Page(
        uuid = getString(0) ?: "", // uuid
        name = getString(1) ?: "", // name
        namespace = getString(2), // namespace
        filePath = getString(3), // file_path
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(getLong(4) ?: 0L), // created_at
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(getLong(5) ?: 0L), // updated_at
        properties = getString(6)?.let { parseJsonProperties(it) } ?: emptyMap() // properties
    )
}

/**
 * Convert SQL cursor to Property model
 */
fun SqlCursor.toProperty(): Property {
    return Property(
        uuid = getString(0) ?: "", // uuid
        blockUuid = getString(1) ?: "", // block_uuid
        key = getString(2) ?: "", // key
        value = getString(3) ?: "", // value
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(getLong(4) ?: 0L) // created_at
    )
}

/**
 * Simple JSON properties parser (basic implementation)
 */
private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseJsonProperties(json: String): Map<String, String> {
    if (json.isBlank() || json == "{}") return emptyMap()
    return try {
        jsonParser.decodeFromString<Map<String, String>>(json)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * Simple JSON string conversion (basic implementation)
 */
fun Map<String, String>.toJsonString(): String {
    return jsonParser.encodeToString(this)
}
