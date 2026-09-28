package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Asset_index(
  public val uuid: String,
  public val file_path: String,
  public val relative_path: String,
  public val media_type: String,
  public val subfolder: String,
  public val tags: String,
  public val auto_labels: String,
  public val ocr_text: String?,
  public val cloud_description: String?,
  public val page_uuids: String,
  public val size_bytes: Long,
  public val imported_at_ms: Long,
  public val ml_processed: Long,
  public val ml_attempted_at: Long?,
  public val ml_failed: Long,
  public val content_hash: String?,
  public val is_orphan: Long,
  public val ml_tags_source: String,
)
