package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Spans(
  public val id: Long,
  public val trace_id: String,
  public val span_id: String,
  public val parent_span_id: String,
  public val name: String,
  public val start_epoch_ms: Long,
  public val end_epoch_ms: Long,
  public val duration_ms: Long,
  public val attributes_json: String,
  public val status_code: String,
)
