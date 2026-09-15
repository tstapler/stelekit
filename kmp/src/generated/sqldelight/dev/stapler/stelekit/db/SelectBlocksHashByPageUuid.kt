package dev.stapler.stelekit.db

import kotlin.String

public data class SelectBlocksHashByPageUuid(
  public val uuid: String,
  public val content_hash: String?,
)
