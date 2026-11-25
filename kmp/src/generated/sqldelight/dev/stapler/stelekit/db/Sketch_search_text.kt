package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Sketch_search_text(
  public val project_uuid: String,
  public val sheet_uuid: String,
  public val body: String,
  public val body_hash: String,
  public val updated_at: Long,
)
