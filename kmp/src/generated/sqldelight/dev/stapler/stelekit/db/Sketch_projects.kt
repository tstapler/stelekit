package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Sketch_projects(
  public val uuid: String,
  public val slug: String,
  public val name: String,
  public val created_at: Long,
  public val settings_json: String,
  public val fusion_results_json: String,
  public val source_hash: String,
  public val last_digest_hash: String,
  public val model_revision: Long,
  public val base_revision: Long,
  public val last_synced_sidecar_hash: String?,
)
