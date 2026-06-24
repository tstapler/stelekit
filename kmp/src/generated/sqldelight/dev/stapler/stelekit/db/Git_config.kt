package dev.stapler.stelekit.db

import kotlin.Long
import kotlin.String

public data class Git_config(
  public val graph_id: String,
  public val repo_root: String,
  public val wiki_subdir: String,
  public val remote_name: String,
  public val remote_branch: String,
  public val auth_type: String,
  public val ssh_key_path: String?,
  public val ssh_key_passphrase_key: String?,
  public val https_token_key: String?,
  public val oauth_token_key: String?,
  public val poll_interval_minutes: Long,
  public val auto_commit: Long,
  public val commit_message_template: String,
)
