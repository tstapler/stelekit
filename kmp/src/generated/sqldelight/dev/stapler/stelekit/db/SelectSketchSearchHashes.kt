package dev.stapler.stelekit.db

import kotlin.String

public data class SelectSketchSearchHashes(
  public val sheet_uuid: String,
  public val body_hash: String,
)
