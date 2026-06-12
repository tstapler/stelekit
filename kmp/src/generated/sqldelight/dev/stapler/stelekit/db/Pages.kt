package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Pages(
  public val uuid: String,
  public val name: String,
  public val namespace: String?,
  public val file_path: String?,
  public val created_at: Long,
  public val updated_at: Long,
  public val properties: String?,
  public val version: Long,
  public val is_favorite: Long?,
  public val is_journal: Long?,
  public val journal_date: String?,
  public val is_content_loaded: Long,
  public val backlink_count: Long,
)
