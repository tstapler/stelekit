package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Blocks(
  public val id: Long,
  public val uuid: String,
  public val page_uuid: String,
  public val parent_uuid: String?,
  public val left_uuid: String?,
  public val content: String,
  public val level: Long,
  public val position: Long,
  public val created_at: Long,
  public val updated_at: Long,
  public val properties: String?,
  public val version: Long,
  public val content_hash: String?,
  public val block_type: String,
)
