// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SheetUuid

/**
 * The one rule mapping (sheet uuid, schema) to a sidecar file path: schema 1 is `sheets/<uuid>.json`, schema 2 is
 * `sheets/<uuid>.v2.json`. A model's `sheetRef.file` must equal it, so a git-synced model cannot name another path.
 */
object SheetPaths {
    const val SHEETS_DIR = "sheets"
    const val SCHEMA_V1 = 1L
    const val SCHEMA_V2 = 2L

    fun pathFor(sheet: SheetUuid, schema: Long): String = when (schema) {
        SCHEMA_V1 -> "$SHEETS_DIR/${sheet.value}.json"
        SCHEMA_V2 -> "$SHEETS_DIR/${sheet.value}.v2.json"
        else -> throw IllegalArgumentException("no sheet path rule for schema $schema")
    }

    fun isSupportedSchema(schema: Long) = schema == SCHEMA_V1 || schema == SCHEMA_V2
}
