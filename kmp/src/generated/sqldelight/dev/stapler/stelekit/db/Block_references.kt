package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Block_references(
  public val id: Long,
  public val from_block_uuid: String,
  public val to_block_uuid: String,
  public val created_at: Long,
)
