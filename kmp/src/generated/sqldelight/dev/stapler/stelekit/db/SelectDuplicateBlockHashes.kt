package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class SelectDuplicateBlockHashes(
  public val content_hash: String,
  public val cnt: Long,
)
