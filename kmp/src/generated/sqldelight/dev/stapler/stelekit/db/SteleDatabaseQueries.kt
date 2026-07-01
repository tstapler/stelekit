package dev.stapler.stelekit.db

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Double
import kotlin.Long
import kotlin.String
import kotlin.collections.Collection

public class SteleDatabaseQueries(
  driver: SqlDriver,
) : SuspendingTransacterImpl(driver) {
  public fun <T : Any> selectBlockByUuid(uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlockByUuidQuery(uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlockByUuid(uuid: String): Query<Blocks> = selectBlockByUuid(uuid, ::Blocks)

  public fun <T : Any> selectBlocksByUuids(uuid: Collection<String>, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksByUuidsQuery(uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByUuids(uuid: Collection<String>): Query<Blocks> = selectBlocksByUuids(uuid, ::Blocks)

  public fun existsBlockByUuid(uuid: String): Query<Long> = ExistsBlockByUuidQuery(uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAllBlocks(mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = Query(1_803_430_346, arrayOf("blocks"), driver, "SteleDatabase.sq", "selectAllBlocks", "SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks ORDER BY uuid") { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectAllBlocks(): Query<Blocks> = selectAllBlocks(::Blocks)

  public fun <T : Any> selectAllBlocksPaginated(
    `value`: Long,
    value_: Long,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectAllBlocksPaginatedQuery(value, value_) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectAllBlocksPaginated(value_: Long, value__: Long): Query<Blocks> = selectAllBlocksPaginated(value_, value__, ::Blocks)

  public fun <T : Any> selectAllBlocksPaginatedAfterUuid(
    uuid: String,
    `value`: Long,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectAllBlocksPaginatedAfterUuidQuery(uuid, value) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectAllBlocksPaginatedAfterUuid(uuid: String, value_: Long): Query<Blocks> = selectAllBlocksPaginatedAfterUuid(uuid, value_, ::Blocks)

  public fun <T : Any> selectBlockChildren(
    parent_uuid: String?,
    `value`: Long,
    value_: Long,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectBlockChildrenQuery(parent_uuid, value, value_) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlockChildren(
    parent_uuid: String?,
    value_: Long,
    value__: Long,
  ): Query<Blocks> = selectBlockChildren(parent_uuid, value_, value__, ::Blocks)

  public fun countBlockChildren(parent_uuid: String?): Query<Long> = CountBlockChildrenQuery(parent_uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectBlockSiblings(
    uuid: String,
    uuid_: String,
    uuid__: String,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectBlockSiblingsQuery(uuid, uuid_, uuid__) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlockSiblings(
    uuid: String,
    uuid_: String,
    uuid__: String,
  ): Query<Blocks> = selectBlockSiblings(uuid, uuid_, uuid__, ::Blocks)

  public fun <T : Any> selectRootBlocks(
    page_uuid: String,
    `value`: Long,
    value_: Long,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectRootBlocksQuery(page_uuid, value, value_) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectRootBlocks(
    page_uuid: String,
    value_: Long,
    value__: Long,
  ): Query<Blocks> = selectRootBlocks(page_uuid, value_, value__, ::Blocks)

  public fun countRootBlocks(page_uuid: String): Query<Long> = CountRootBlocksQuery(page_uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectBlocksByPageUuid(
    page_uuid: String,
    `value`: Long,
    value_: Long,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectBlocksByPageUuidQuery(page_uuid, value, value_) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByPageUuid(
    page_uuid: String,
    value_: Long,
    value__: Long,
  ): Query<Blocks> = selectBlocksByPageUuid(page_uuid, value_, value__, ::Blocks)

  public fun <T : Any> selectBlocksByPageUuidUnpaginated(page_uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksByPageUuidUnpaginatedQuery(page_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByPageUuidUnpaginated(page_uuid: String): Query<Blocks> = selectBlocksByPageUuidUnpaginated(page_uuid, ::Blocks)

  public fun <T : Any> selectBlocksWithContentLike(content: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksWithContentLikeQuery(content) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksWithContentLike(content: String): Query<Blocks> = selectBlocksWithContentLike(content, ::Blocks)

  public fun <T : Any> selectBlocksWithContentLikePaginated(
    content: String,
    `value`: Long,
    value_: Long,
    mapper: (
      id: Long,
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      content_hash: String?,
      block_type: String,
    ) -> T,
  ): Query<T> = SelectBlocksWithContentLikePaginatedQuery(content, value, value_) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksWithContentLikePaginated(
    content: String,
    value_: Long,
    value__: Long,
  ): Query<Blocks> = selectBlocksWithContentLikePaginated(content, value_, value__, ::Blocks)

  public fun countBlocksByPageUuid(page_uuid: String): Query<Long> = CountBlocksByPageUuidQuery(page_uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectBlocksByParentUuidOrdered(parent_uuid: String?, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksByParentUuidOrderedQuery(parent_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByParentUuidOrdered(parent_uuid: String?): Query<Blocks> = selectBlocksByParentUuidOrdered(parent_uuid, ::Blocks)

  public fun <T : Any> selectBlocksByParentUuids(parent_uuid: Collection<String?>, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksByParentUuidsQuery(parent_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByParentUuids(parent_uuid: Collection<String?>): Query<Blocks> = selectBlocksByParentUuids(parent_uuid, ::Blocks)

  public fun <T : Any> selectBlockHierarchyRecursive(uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
    depth: Long,
  ) -> T): Query<T> = SelectBlockHierarchyRecursiveQuery(uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!,
      cursor.getLong(14)!!
    )
  }

  public fun selectBlockHierarchyRecursive(uuid: String): Query<SelectBlockHierarchyRecursive> = selectBlockHierarchyRecursive(uuid, ::SelectBlockHierarchyRecursive)

  public fun <T : Any> selectRootBlocksByPageUuidOrdered(page_uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectRootBlocksByPageUuidOrderedQuery(page_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectRootBlocksByPageUuidOrdered(page_uuid: String): Query<Blocks> = selectRootBlocksByPageUuidOrdered(page_uuid, ::Blocks)

  public fun <T : Any> selectBlockByLeftUuid(left_uuid: String?, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlockByLeftUuidQuery(left_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlockByLeftUuid(left_uuid: String?): Query<Blocks> = selectBlockByLeftUuid(left_uuid, ::Blocks)

  public fun <T : Any> selectLastChild(parent_uuid: String?, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectLastChildQuery(parent_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectLastChild(parent_uuid: String?): Query<Blocks> = selectLastChild(parent_uuid, ::Blocks)

  public fun countBlocks(): Query<Long> = Query(-589_645_868, arrayOf("blocks"), driver, "SteleDatabase.sq", "countBlocks", "SELECT COUNT(*) FROM blocks") { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectBlocksHashByPageUuid(page_uuid: String, mapper: (uuid: String, content_hash: String?) -> T): Query<T> = SelectBlocksHashByPageUuidQuery(page_uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)
    )
  }

  public fun selectBlocksHashByPageUuid(page_uuid: String): Query<SelectBlocksHashByPageUuid> = selectBlocksHashByPageUuid(page_uuid, ::SelectBlocksHashByPageUuid)

  public fun <T : Any> selectBlocksByContentHash(content_hash: String?, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksByContentHashQuery(content_hash) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByContentHash(content_hash: String?): Query<Blocks> = selectBlocksByContentHash(content_hash, ::Blocks)

  public fun <T : Any> selectDuplicateBlockHashes(`value`: Long, mapper: (content_hash: String, cnt: Long) -> T): Query<T> = SelectDuplicateBlockHashesQuery(value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun selectDuplicateBlockHashes(value_: Long): Query<SelectDuplicateBlockHashes> = selectDuplicateBlockHashes(value_, ::SelectDuplicateBlockHashes)

  public fun <T : Any> selectPageByUuid(uuid: String, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectPageByUuidQuery(uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPageByUuid(uuid: String): Query<Pages> = selectPageByUuid(uuid, ::Pages)

  public fun <T : Any> selectPageByName(name: String, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectPageByNameQuery(name) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPageByName(name: String): Query<Pages> = selectPageByName(name, ::Pages)

  public fun existsPageByUuid(uuid: String): Query<Long> = ExistsPageByUuidQuery(uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun existsPageByName(name: String): Query<Long> = ExistsPageByNameQuery(name) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAllPagesPaginated(
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectAllPagesPaginatedQuery(value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectAllPagesPaginated(value_: Long, value__: Long): Query<Pages> = selectAllPagesPaginated(value_, value__, ::Pages)

  public fun <T : Any> selectUnloadedPagesPaginated(
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectUnloadedPagesPaginatedQuery(value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectUnloadedPagesPaginated(value_: Long, value__: Long): Query<Pages> = selectUnloadedPagesPaginated(value_, value__, ::Pages)

  public fun <T : Any> selectUnloadedPagesBySection(
    section_id: Collection<String>,
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectUnloadedPagesBySectionQuery(section_id, value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectUnloadedPagesBySection(section_id: Collection<String>, value_: Long, value__: Long): Query<Pages> = selectUnloadedPagesBySection(section_id, value_, value__, ::Pages)

  public fun countUnloadedPages(): Query<Long> = Query(-58_485_640, arrayOf("pages"), driver, "SteleDatabase.sq", "countUnloadedPages", "SELECT COUNT(*) FROM pages WHERE is_content_loaded = 0") { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectPageNameEntries(mapper: (name: String, is_journal: Long?) -> T): Query<T> = Query(-1_279_862_151, arrayOf("pages"), driver, "SteleDatabase.sq", "selectPageNameEntries", "SELECT name, is_journal FROM pages") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)
    )
  }

  public fun selectPageNameEntries(): Query<SelectPageNameEntries> = selectPageNameEntries(::SelectPageNameEntries)

  public fun <T : Any> selectPagesByNames(name: Collection<String>, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectPagesByNamesQuery(name) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPagesByNames(name: Collection<String>): Query<Pages> = selectPagesByNames(name, ::Pages)

  public fun <T : Any> selectJournalPagesByDates(journal_date: Collection<String?>, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectJournalPagesByDatesQuery(journal_date) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectJournalPagesByDates(journal_date: Collection<String?>): Query<Pages> = selectJournalPagesByDates(journal_date, ::Pages)

  public fun <T : Any> selectFavoritePages(mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = Query(1_623_585_099, arrayOf("pages"), driver, "SteleDatabase.sq", "selectFavoritePages", "SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_favorite = 1 ORDER BY name") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectFavoritePages(): Query<Pages> = selectFavoritePages(::Pages)

  public fun <T : Any> selectPagesByNamespace(
    namespace: String?,
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectPagesByNamespaceQuery(namespace, value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPagesByNamespace(
    namespace: String?,
    value_: Long,
    value__: Long,
  ): Query<Pages> = selectPagesByNamespace(namespace, value_, value__, ::Pages)

  public fun <T : Any> selectPagesByNamespaceUnpaginated(namespace: String?, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectPagesByNamespaceUnpaginatedQuery(namespace) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPagesByNamespaceUnpaginated(namespace: String?): Query<Pages> = selectPagesByNamespaceUnpaginated(namespace, ::Pages)

  public fun countPagesByNamespace(namespace: String?): Query<Long> = CountPagesByNamespaceQuery(namespace) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectRecentlyUpdatedPages(`value`: Long, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectRecentlyUpdatedPagesQuery(value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectRecentlyUpdatedPages(value_: Long): Query<Pages> = selectRecentlyUpdatedPages(value_, ::Pages)

  public fun <T : Any> selectRecentlyCreatedPages(`value`: Long, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectRecentlyCreatedPagesQuery(value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectRecentlyCreatedPages(value_: Long): Query<Pages> = selectRecentlyCreatedPages(value_, ::Pages)

  public fun countPages(): Query<Long> = Query(1_240_499_126, arrayOf("pages"), driver, "SteleDatabase.sq", "countPages", "SELECT COUNT(*) FROM pages") { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectJournalPages(
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectJournalPagesQuery(value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectJournalPages(value_: Long, value__: Long): Query<SelectJournalPages> = selectJournalPages(value_, value__, ::SelectJournalPages)

  public fun <T : Any> selectJournalPageByDate(journal_date: String?, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectJournalPageByDateQuery(journal_date) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectJournalPageByDate(journal_date: String?): Query<Pages> = selectJournalPageByDate(journal_date, ::Pages)

  public fun <T : Any> selectJournalPageByDateAndSection(
    journal_date: String?,
    section_id: String,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectJournalPageByDateAndSectionQuery(journal_date, section_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectJournalPageByDateAndSection(journal_date: String?, section_id: String): Query<Pages> = selectJournalPageByDateAndSection(journal_date, section_id, ::Pages)

  public fun <T : Any> selectPagesBySectionId(
    section_id: String,
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectPagesBySectionIdQuery(section_id, value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPagesBySectionId(
    section_id: String,
    value_: Long,
    value__: Long,
  ): Query<Pages> = selectPagesBySectionId(section_id, value_, value__, ::Pages)

  public fun selectNeighbourPageUuids(pageUuid: String): Query<String> = SelectNeighbourPageUuidsQuery(pageUuid) { cursor ->
    cursor.getString(0)!!
  }

  public fun <T : Any> selectOutgoingReferences(from_block_uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectOutgoingReferencesQuery(from_block_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectOutgoingReferences(from_block_uuid: String): Query<Blocks> = selectOutgoingReferences(from_block_uuid, ::Blocks)

  public fun <T : Any> selectIncomingReferences(to_block_uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectIncomingReferencesQuery(to_block_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectIncomingReferences(to_block_uuid: String): Query<Blocks> = selectIncomingReferences(to_block_uuid, ::Blocks)

  public fun <T : Any> selectOrphanedBlocks(mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = Query(-1_994_028_502, arrayOf("blocks", "block_references"), driver, "SteleDatabase.sq", "selectOrphanedBlocks", """
  |SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version, b.content_hash, b.block_type FROM blocks b
  |LEFT JOIN block_references br ON b.uuid = br.to_block_uuid
  |WHERE br.id IS NULL
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectOrphanedBlocks(): Query<Blocks> = selectOrphanedBlocks(::Blocks)

  public fun <T : Any> selectMostConnectedBlocks(`value`: Long, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
    reference_count: Long,
  ) -> T): Query<T> = SelectMostConnectedBlocksQuery(value) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!,
      cursor.getLong(14)!!
    )
  }

  public fun selectMostConnectedBlocks(value_: Long): Query<SelectMostConnectedBlocks> = selectMostConnectedBlocks(value_, ::SelectMostConnectedBlocks)

  public fun <T : Any> selectPagesByNameLike(name: String, mapper: (
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    backlink_count: Long,
    section_id: String,
  ) -> T): Query<T> = SelectPagesByNameLikeQuery(name) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPagesByNameLike(name: String): Query<Pages> = selectPagesByNameLike(name, ::Pages)

  public fun <T : Any> selectPagesByNameLikePaginated(
    name: String,
    `value`: Long,
    value_: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      backlink_count: Long,
      section_id: String,
    ) -> T,
  ): Query<T> = SelectPagesByNameLikePaginatedQuery(name, value, value_) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!
    )
  }

  public fun selectPagesByNameLikePaginated(
    name: String,
    value_: Long,
    value__: Long,
  ): Query<Pages> = selectPagesByNameLikePaginated(name, value_, value__, ::Pages)

  public fun <T : Any> selectBlocksReferencing(to_block_uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksReferencingQuery(to_block_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksReferencing(to_block_uuid: String): Query<Blocks> = selectBlocksReferencing(to_block_uuid, ::Blocks)

  public fun <T : Any> selectPluginDataById(id: Long, mapper: (
    id: Long,
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
    value_: String,
    created_at: Long,
    updated_at: Long?,
  ) -> T): Query<T> = SelectPluginDataByIdQuery(id) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)
    )
  }

  public fun selectPluginDataById(id: Long): Query<Plugin_data> = selectPluginDataById(id, ::Plugin_data)

  public fun <T : Any> selectPluginDataByPlugin(plugin_id: String, mapper: (
    id: Long,
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
    value_: String,
    created_at: Long,
    updated_at: Long?,
  ) -> T): Query<T> = SelectPluginDataByPluginQuery(plugin_id) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)
    )
  }

  public fun selectPluginDataByPlugin(plugin_id: String): Query<Plugin_data> = selectPluginDataByPlugin(plugin_id, ::Plugin_data)

  public fun <T : Any> selectPluginDataByEntity(
    entity_type: String,
    entity_uuid: String,
    mapper: (
      id: Long,
      plugin_id: String,
      entity_type: String,
      entity_uuid: String,
      key: String,
      value_: String,
      created_at: Long,
      updated_at: Long?,
    ) -> T,
  ): Query<T> = SelectPluginDataByEntityQuery(entity_type, entity_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)
    )
  }

  public fun selectPluginDataByEntity(entity_type: String, entity_uuid: String): Query<Plugin_data> = selectPluginDataByEntity(entity_type, entity_uuid, ::Plugin_data)

  public fun <T : Any> selectPluginDataByKey(
    plugin_id: String,
    key: String,
    mapper: (
      id: Long,
      plugin_id: String,
      entity_type: String,
      entity_uuid: String,
      key: String,
      value_: String,
      created_at: Long,
      updated_at: Long?,
    ) -> T,
  ): Query<T> = SelectPluginDataByKeyQuery(plugin_id, key) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)
    )
  }

  public fun selectPluginDataByKey(plugin_id: String, key: String): Query<Plugin_data> = selectPluginDataByKey(plugin_id, key, ::Plugin_data)

  public fun <T : Any> selectPluginDataByPluginAndEntity(
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    mapper: (
      id: Long,
      plugin_id: String,
      entity_type: String,
      entity_uuid: String,
      key: String,
      value_: String,
      created_at: Long,
      updated_at: Long?,
    ) -> T,
  ): Query<T> = SelectPluginDataByPluginAndEntityQuery(plugin_id, entity_type, entity_uuid) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)
    )
  }

  public fun selectPluginDataByPluginAndEntity(
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
  ): Query<Plugin_data> = selectPluginDataByPluginAndEntity(plugin_id, entity_type, entity_uuid, ::Plugin_data)

  public fun countPluginDataByPlugin(plugin_id: String): Query<Long> = CountPluginDataByPluginQuery(plugin_id) { cursor ->
    cursor.getLong(0)!!
  }

  public fun countPluginDataByEntity(entity_type: String, entity_uuid: String): Query<Long> = CountPluginDataByEntityQuery(entity_type, entity_uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun existsPluginData(
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
  ): Query<Long> = ExistsPluginDataQuery(plugin_id, entity_type, entity_uuid, key) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> searchBlocksByContentFts(
    query: String,
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      highlight: String?,
      bm25_score: Double,
    ) -> T,
  ): Query<T> = SearchBlocksByContentFtsQuery(query, limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getString(9),
      cursor.getLong(10)!!,
      cursor.getString(11),
      cursor.getDouble(12)!!
    )
  }

  public fun searchBlocksByContentFts(
    query: String,
    limit: Long,
    offset: Long,
  ): Query<SearchBlocksByContentFts> = searchBlocksByContentFts(query, limit, offset, ::SearchBlocksByContentFts)

  public fun <T : Any> searchBlocksByContentFtsInPage(
    query: String,
    pageUuid: String,
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      highlight: String?,
      bm25_score: Double,
    ) -> T,
  ): Query<T> = SearchBlocksByContentFtsInPageQuery(query, pageUuid, limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getString(9),
      cursor.getLong(10)!!,
      cursor.getString(11),
      cursor.getDouble(12)!!
    )
  }

  public fun searchBlocksByContentFtsInPage(
    query: String,
    pageUuid: String,
    limit: Long,
    offset: Long,
  ): Query<SearchBlocksByContentFtsInPage> = searchBlocksByContentFtsInPage(query, pageUuid, limit, offset, ::SearchBlocksByContentFtsInPage)

  public fun searchBlocksCountFts(query: String): Query<Long> = SearchBlocksCountFtsQuery(query) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> searchPagesByNameFts(
    query: String,
    limit: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      highlight: String?,
      bm25_score: Double,
    ) -> T,
  ): Query<T> = SearchPagesByNameFtsQuery(query, limit) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getDouble(13)!!
    )
  }

  public fun searchPagesByNameFts(query: String, limit: Long): Query<SearchPagesByNameFts> = searchPagesByNameFts(query, limit, ::SearchPagesByNameFts)

  public fun <T : Any> searchPagesByNameFtsInDateRange(
    query: String,
    startMs: Long,
    endMs: Long,
    limit: Long,
    mapper: (
      uuid: String,
      name: String,
      namespace: String?,
      file_path: String?,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      is_favorite: Long?,
      is_journal: Long?,
      journal_date: String?,
      is_content_loaded: Long,
      highlight: String?,
      bm25_score: Double,
    ) -> T,
  ): Query<T> = SearchPagesByNameFtsInDateRangeQuery(query, startMs, endMs, limit) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6),
      cursor.getLong(7)!!,
      cursor.getLong(8),
      cursor.getLong(9),
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getDouble(13)!!
    )
  }

  public fun searchPagesByNameFtsInDateRange(
    query: String,
    startMs: Long,
    endMs: Long,
    limit: Long,
  ): Query<SearchPagesByNameFtsInDateRange> = searchPagesByNameFtsInDateRange(query, startMs, endMs, limit, ::SearchPagesByNameFtsInDateRange)

  public fun <T : Any> searchBlocksByContentFtsInDateRange(
    query: String,
    startMs: Long,
    endMs: Long,
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      page_uuid: String,
      parent_uuid: String?,
      left_uuid: String?,
      content: String,
      level: Long,
      position: String,
      created_at: Long,
      updated_at: Long,
      properties: String?,
      version: Long,
      highlight: String?,
      bm25_score: Double,
    ) -> T,
  ): Query<T> = SearchBlocksByContentFtsInDateRangeQuery(query, startMs, endMs, limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getString(9),
      cursor.getLong(10)!!,
      cursor.getString(11),
      cursor.getDouble(12)!!
    )
  }

  public fun searchBlocksByContentFtsInDateRange(
    query: String,
    startMs: Long,
    endMs: Long,
    limit: Long,
    offset: Long,
  ): Query<SearchBlocksByContentFtsInDateRange> = searchBlocksByContentFtsInDateRange(query, startMs, endMs, limit, offset, ::SearchBlocksByContentFtsInDateRange)

  public fun <T : Any> selectPageVisitsByUuids(page_uuid: Collection<String>, mapper: (
    page_uuid: String,
    last_visited_at: Long,
    visit_count: Long,
  ) -> T): Query<T> = SelectPageVisitsByUuidsQuery(page_uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!,
      cursor.getLong(2)!!
    )
  }

  public fun selectPageVisitsByUuids(page_uuid: Collection<String>): Query<SelectPageVisitsByUuids> = selectPageVisitsByUuids(page_uuid, ::SelectPageVisitsByUuids)

  public fun <T : Any> selectPageVisitByUuid(page_uuid: String, mapper: (
    page_uuid: String,
    last_visited_at: Long,
    visit_count: Long,
  ) -> T): Query<T> = SelectPageVisitByUuidQuery(page_uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!,
      cursor.getLong(2)!!
    )
  }

  public fun selectPageVisitByUuid(page_uuid: String): Query<SelectPageVisitByUuid> = selectPageVisitByUuid(page_uuid, ::SelectPageVisitByUuid)

  public fun countBlocksWithWikilink(pageName: String): Query<Long> = CountBlocksWithWikilinkQuery(pageName) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectBlocksWithWikilink(pageName: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ) -> T): Query<T> = SelectBlocksWithWikilinkQuery(pageName) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5)!!,
      cursor.getLong(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksWithWikilink(pageName: String): Query<Blocks> = selectBlocksWithWikilink(pageName, ::Blocks)

  public fun countLinkedReferencesForPage(pageName: String): Query<Long> = CountLinkedReferencesForPageQuery(pageName) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectBacklinkCountsForPages(uuid: Collection<String>, mapper: (page_name: String, backlink_count: Long) -> T): Query<T> = SelectBacklinkCountsForPagesQuery(uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun selectBacklinkCountsForPages(uuid: Collection<String>): Query<SelectBacklinkCountsForPages> = selectBacklinkCountsForPages(uuid, ::SelectBacklinkCountsForPages)

  public fun selectPageBacklinkCount(name: String): Query<Long> = SelectPageBacklinkCountQuery(name) { cursor ->
    cursor.getLong(0)!!
  }

  public fun selectWikilinkPageNamesForBlock(block_uuid: String): Query<String> = SelectWikilinkPageNamesForBlockQuery(block_uuid) { cursor ->
    cursor.getString(0)!!
  }

  public fun selectWikilinkPageNamesForBlocks(block_uuid: Collection<String>): Query<String> = SelectWikilinkPageNamesForBlocksQuery(block_uuid) { cursor ->
    cursor.getString(0)!!
  }

  public fun selectWikilinkPageNamesForPage(page_uuid: String): Query<String> = SelectWikilinkPageNamesForPageQuery(page_uuid) { cursor ->
    cursor.getString(0)!!
  }

  public fun selectMetadata(key: String): Query<String> = SelectMetadataQuery(key) { cursor ->
    cursor.getString(0)!!
  }

  public fun <T : Any> selectAllMetadata(mapper: (key: String, value_: String) -> T): Query<T> = Query(-1_855_662_477, arrayOf("metadata"), driver, "SteleDatabase.sq", "selectAllMetadata", "SELECT key, value FROM metadata") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!
    )
  }

  public fun selectAllMetadata(): Query<Metadata> = selectAllMetadata(::Metadata)

  public fun <T : Any> selectAllBlocksWithPagePath(mapper: (
    uuid: String,
    parent_uuid: String?,
    position: String,
    content: String,
    file_path: String,
  ) -> T): Query<T> = Query(557_803_812, arrayOf("blocks", "pages"), driver, "SteleDatabase.sq", "selectAllBlocksWithPagePath", """
  |SELECT b.uuid, b.parent_uuid, b.position, b.content, p.file_path
  |FROM blocks b
  |JOIN pages p ON b.page_uuid = p.uuid
  |WHERE p.file_path IS NOT NULL
  """.trimMargin()) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1),
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!
    )
  }

  public fun selectAllBlocksWithPagePath(): Query<SelectAllBlocksWithPagePath> = selectAllBlocksWithPagePath(::SelectAllBlocksWithPagePath)

  public fun <T : Any> selectOperationsBySessionDesc(
    session_id: String,
    `value`: Long,
    mapper: (
      op_id: String,
      session_id: String,
      seq: Long,
      op_type: String,
      entity_uuid: String?,
      page_uuid: String?,
      payload: String,
      created_at: Long,
    ) -> T,
  ): Query<T> = SelectOperationsBySessionDescQuery(session_id, value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getLong(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4),
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getLong(7)!!
    )
  }

  public fun selectOperationsBySessionDesc(session_id: String, value_: Long): Query<Operations> = selectOperationsBySessionDesc(session_id, value_, ::Operations)

  public fun <T : Any> selectOperationsByPageUuid(page_uuid: String?, mapper: (
    op_id: String,
    session_id: String,
    seq: Long,
    op_type: String,
    entity_uuid: String?,
    page_uuid: String?,
    payload: String,
    created_at: Long,
  ) -> T): Query<T> = SelectOperationsByPageUuidQuery(page_uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getLong(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4),
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getLong(7)!!
    )
  }

  public fun selectOperationsByPageUuid(page_uuid: String?): Query<Operations> = selectOperationsByPageUuid(page_uuid, ::Operations)

  public fun <T : Any> selectOperationsSince(
    session_id: String,
    seq: Long,
    mapper: (
      op_id: String,
      session_id: String,
      seq: Long,
      op_type: String,
      entity_uuid: String?,
      page_uuid: String?,
      payload: String,
      created_at: Long,
    ) -> T,
  ): Query<T> = SelectOperationsSinceQuery(session_id, seq) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getLong(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4),
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getLong(7)!!
    )
  }

  public fun selectOperationsSince(session_id: String, seq: Long): Query<Operations> = selectOperationsSince(session_id, seq, ::Operations)

  public fun <T : Any> countOperationPayloadSize(session_id: String, mapper: (SUM: Long?) -> T): Query<T> = CountOperationPayloadSizeQuery(session_id) { cursor ->
    mapper(
      cursor.getLong(0)
    )
  }

  public fun countOperationPayloadSize(session_id: String): Query<CountOperationPayloadSize> = countOperationPayloadSize(session_id, ::CountOperationPayloadSize)

  public fun selectLogicalClock(session_id: String): Query<Long> = SelectLogicalClockQuery(session_id) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAppliedMigrations(graph_id: String, mapper: (
    id: String,
    checksum: String,
    status: String,
  ) -> T): Query<T> = SelectAppliedMigrationsQuery(graph_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!
    )
  }

  public fun selectAppliedMigrations(graph_id: String): Query<SelectAppliedMigrations> = selectAppliedMigrations(graph_id, ::SelectAppliedMigrations)

  public fun <T : Any> selectMigrationById(
    id: String,
    graph_id: String,
    mapper: (
      id: String,
      graph_id: String,
      description: String,
      checksum: String,
      applied_at: Long,
      execution_ms: Long,
      status: String,
      applied_by: String,
      execution_order: Long,
      changes_applied: Long,
      error_message: String?,
    ) -> T,
  ): Query<T> = SelectMigrationByIdQuery(id, graph_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10)
    )
  }

  public fun selectMigrationById(id: String, graph_id: String): Query<Migration_changelog> = selectMigrationById(id, graph_id, ::Migration_changelog)

  public fun <T : Any> selectRunningMigrations(graph_id: String, mapper: (
    id: String,
    graph_id: String,
    description: String,
    checksum: String,
    applied_at: Long,
    execution_ms: Long,
    status: String,
    applied_by: String,
    execution_order: Long,
    changes_applied: Long,
    error_message: String?,
  ) -> T): Query<T> = SelectRunningMigrationsQuery(graph_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10)
    )
  }

  public fun selectRunningMigrations(graph_id: String): Query<Migration_changelog> = selectRunningMigrations(graph_id, ::Migration_changelog)

  public fun <T : Any> selectAllMigrationsForGraph(graph_id: String, mapper: (
    id: String,
    graph_id: String,
    description: String,
    checksum: String,
    applied_at: Long,
    execution_ms: Long,
    status: String,
    applied_by: String,
    execution_order: Long,
    changes_applied: Long,
    error_message: String?,
  ) -> T): Query<T> = SelectAllMigrationsForGraphQuery(graph_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10)
    )
  }

  public fun selectAllMigrationsForGraph(graph_id: String): Query<Migration_changelog> = selectAllMigrationsForGraph(graph_id, ::Migration_changelog)

  public fun <T : Any> selectGitConfig(graph_id: String, mapper: (
    graph_id: String,
    repo_root: String,
    wiki_subdir: String,
    remote_name: String,
    remote_branch: String,
    auth_type: String,
    ssh_key_path: String?,
    ssh_key_passphrase_key: String?,
    https_token_key: String?,
    oauth_token_key: String?,
    poll_interval_minutes: Long,
    auto_commit: Long,
    commit_message_template: String,
  ) -> T): Query<T> = SelectGitConfigQuery(graph_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getString(6),
      cursor.getString(7),
      cursor.getString(8),
      cursor.getString(9),
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getString(12)!!
    )
  }

  public fun selectGitConfig(graph_id: String): Query<Git_config> = selectGitConfig(graph_id, ::Git_config)

  public fun <T : Any> selectAllImageAnnotations(mapper: (
    uuid: String,
    block_uuid: String,
    page_uuid: String,
    graph_path: String,
    file_path: String,
    thumbnail_path: String?,
    source: String,
    source_uri: String?,
    captured_at_ms: Long?,
    imported_at_ms: Long,
    calibration_method: String,
    pixels_per_meter: Double,
    calibration_confidence_pct: Long,
    unit: String,
    tags: String,
    lat_lng: String?,
    altitude_m: Double?,
    bearing_deg: Double?,
    pitch_deg: Double?,
    roll_deg: Double?,
    focal_length_mm: Double?,
    focal_length_35mm_eq: Double?,
    camera_make: String?,
    camera_model: String?,
  ) -> T): Query<T> = Query(-1_056_409_075, arrayOf("image_annotations"), driver, "SteleDatabase.sq", "selectAllImageAnnotations", "SELECT image_annotations.uuid, image_annotations.block_uuid, image_annotations.page_uuid, image_annotations.graph_path, image_annotations.file_path, image_annotations.thumbnail_path, image_annotations.source, image_annotations.source_uri, image_annotations.captured_at_ms, image_annotations.imported_at_ms, image_annotations.calibration_method, image_annotations.pixels_per_meter, image_annotations.calibration_confidence_pct, image_annotations.unit, image_annotations.tags, image_annotations.lat_lng, image_annotations.altitude_m, image_annotations.bearing_deg, image_annotations.pitch_deg, image_annotations.roll_deg, image_annotations.focal_length_mm, image_annotations.focal_length_35mm_eq, image_annotations.camera_make, image_annotations.camera_model FROM image_annotations ORDER BY imported_at_ms DESC") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getLong(8),
      cursor.getLong(9)!!,
      cursor.getString(10)!!,
      cursor.getDouble(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!,
      cursor.getString(14)!!,
      cursor.getString(15),
      cursor.getDouble(16),
      cursor.getDouble(17),
      cursor.getDouble(18),
      cursor.getDouble(19),
      cursor.getDouble(20),
      cursor.getDouble(21),
      cursor.getString(22),
      cursor.getString(23)
    )
  }

  public fun selectAllImageAnnotations(): Query<Image_annotations> = selectAllImageAnnotations(::Image_annotations)

  public fun <T : Any> selectImageAnnotationByUuid(uuid: String, mapper: (
    uuid: String,
    block_uuid: String,
    page_uuid: String,
    graph_path: String,
    file_path: String,
    thumbnail_path: String?,
    source: String,
    source_uri: String?,
    captured_at_ms: Long?,
    imported_at_ms: Long,
    calibration_method: String,
    pixels_per_meter: Double,
    calibration_confidence_pct: Long,
    unit: String,
    tags: String,
    lat_lng: String?,
    altitude_m: Double?,
    bearing_deg: Double?,
    pitch_deg: Double?,
    roll_deg: Double?,
    focal_length_mm: Double?,
    focal_length_35mm_eq: Double?,
    camera_make: String?,
    camera_model: String?,
  ) -> T): Query<T> = SelectImageAnnotationByUuidQuery(uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getLong(8),
      cursor.getLong(9)!!,
      cursor.getString(10)!!,
      cursor.getDouble(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!,
      cursor.getString(14)!!,
      cursor.getString(15),
      cursor.getDouble(16),
      cursor.getDouble(17),
      cursor.getDouble(18),
      cursor.getDouble(19),
      cursor.getDouble(20),
      cursor.getDouble(21),
      cursor.getString(22),
      cursor.getString(23)
    )
  }

  public fun selectImageAnnotationByUuid(uuid: String): Query<Image_annotations> = selectImageAnnotationByUuid(uuid, ::Image_annotations)

  public fun <T : Any> selectImageAnnotationsByPage(page_uuid: String, mapper: (
    uuid: String,
    block_uuid: String,
    page_uuid: String,
    graph_path: String,
    file_path: String,
    thumbnail_path: String?,
    source: String,
    source_uri: String?,
    captured_at_ms: Long?,
    imported_at_ms: Long,
    calibration_method: String,
    pixels_per_meter: Double,
    calibration_confidence_pct: Long,
    unit: String,
    tags: String,
    lat_lng: String?,
    altitude_m: Double?,
    bearing_deg: Double?,
    pitch_deg: Double?,
    roll_deg: Double?,
    focal_length_mm: Double?,
    focal_length_35mm_eq: Double?,
    camera_make: String?,
    camera_model: String?,
  ) -> T): Query<T> = SelectImageAnnotationsByPageQuery(page_uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getLong(8),
      cursor.getLong(9)!!,
      cursor.getString(10)!!,
      cursor.getDouble(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!,
      cursor.getString(14)!!,
      cursor.getString(15),
      cursor.getDouble(16),
      cursor.getDouble(17),
      cursor.getDouble(18),
      cursor.getDouble(19),
      cursor.getDouble(20),
      cursor.getDouble(21),
      cursor.getString(22),
      cursor.getString(23)
    )
  }

  public fun selectImageAnnotationsByPage(page_uuid: String): Query<Image_annotations> = selectImageAnnotationsByPage(page_uuid, ::Image_annotations)

  public fun <T : Any> selectImageAnnotationsByTag(`value`: String, mapper: (
    uuid: String,
    block_uuid: String,
    page_uuid: String,
    graph_path: String,
    file_path: String,
    thumbnail_path: String?,
    source: String,
    source_uri: String?,
    captured_at_ms: Long?,
    imported_at_ms: Long,
    calibration_method: String,
    pixels_per_meter: Double,
    calibration_confidence_pct: Long,
    unit: String,
    tags: String,
    lat_lng: String?,
    altitude_m: Double?,
    bearing_deg: Double?,
    pitch_deg: Double?,
    roll_deg: Double?,
    focal_length_mm: Double?,
    focal_length_35mm_eq: Double?,
    camera_make: String?,
    camera_model: String?,
  ) -> T): Query<T> = SelectImageAnnotationsByTagQuery(value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5),
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getLong(8),
      cursor.getLong(9)!!,
      cursor.getString(10)!!,
      cursor.getDouble(11)!!,
      cursor.getLong(12)!!,
      cursor.getString(13)!!,
      cursor.getString(14)!!,
      cursor.getString(15),
      cursor.getDouble(16),
      cursor.getDouble(17),
      cursor.getDouble(18),
      cursor.getDouble(19),
      cursor.getDouble(20),
      cursor.getDouble(21),
      cursor.getString(22),
      cursor.getString(23)
    )
  }

  public fun selectImageAnnotationsByTag(value_: String): Query<Image_annotations> = selectImageAnnotationsByTag(value_, ::Image_annotations)

  public fun <T : Any> selectMeasurementsForImage(image_uuid: String, mapper: (
    uuid: String,
    image_uuid: String,
    annotation_type: String,
    normalized_points: String,
    value_meters: Double?,
    value_display: String?,
    label: String?,
    color_hex: String,
    ble_device_id: String?,
  ) -> T): Query<T> = SelectMeasurementsForImageQuery(image_uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getDouble(4),
      cursor.getString(5),
      cursor.getString(6),
      cursor.getString(7)!!,
      cursor.getString(8)
    )
  }

  public fun selectMeasurementsForImage(image_uuid: String): Query<Measurement_annotations> = selectMeasurementsForImage(image_uuid, ::Measurement_annotations)

  public fun <T : Any> selectAssetByUuid(uuid: String, mapper: (
    uuid: String,
    file_path: String,
    relative_path: String,
    media_type: String,
    subfolder: String,
    tags: String,
    auto_labels: String,
    ocr_text: String?,
    cloud_description: String?,
    page_uuids: String,
    size_bytes: Long,
    imported_at_ms: Long,
    ml_processed: Long,
    ml_attempted_at: Long?,
    ml_failed: Long,
    content_hash: String?,
    is_orphan: Long,
    ml_tags_source: String,
  ) -> T): Query<T> = SelectAssetByUuidQuery(uuid) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getString(8),
      cursor.getString(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13),
      cursor.getLong(14)!!,
      cursor.getString(15),
      cursor.getLong(16)!!,
      cursor.getString(17)!!
    )
  }

  public fun selectAssetByUuid(uuid: String): Query<Asset_index> = selectAssetByUuid(uuid, ::Asset_index)

  public fun <T : Any> selectAssets(
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      file_path: String,
      relative_path: String,
      media_type: String,
      subfolder: String,
      tags: String,
      auto_labels: String,
      ocr_text: String?,
      cloud_description: String?,
      page_uuids: String,
      size_bytes: Long,
      imported_at_ms: Long,
      ml_processed: Long,
      ml_attempted_at: Long?,
      ml_failed: Long,
      content_hash: String?,
      is_orphan: Long,
      ml_tags_source: String,
    ) -> T,
  ): Query<T> = SelectAssetsQuery(limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getString(8),
      cursor.getString(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13),
      cursor.getLong(14)!!,
      cursor.getString(15),
      cursor.getLong(16)!!,
      cursor.getString(17)!!
    )
  }

  public fun selectAssets(limit: Long, offset: Long): Query<Asset_index> = selectAssets(limit, offset, ::Asset_index)

  public fun <T : Any> selectAssetsByMediaType(
    media_type: String,
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      file_path: String,
      relative_path: String,
      media_type: String,
      subfolder: String,
      tags: String,
      auto_labels: String,
      ocr_text: String?,
      cloud_description: String?,
      page_uuids: String,
      size_bytes: Long,
      imported_at_ms: Long,
      ml_processed: Long,
      ml_attempted_at: Long?,
      ml_failed: Long,
      content_hash: String?,
      is_orphan: Long,
      ml_tags_source: String,
    ) -> T,
  ): Query<T> = SelectAssetsByMediaTypeQuery(media_type, limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getString(8),
      cursor.getString(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13),
      cursor.getLong(14)!!,
      cursor.getString(15),
      cursor.getLong(16)!!,
      cursor.getString(17)!!
    )
  }

  public fun selectAssetsByMediaType(
    media_type: String,
    limit: Long,
    offset: Long,
  ): Query<Asset_index> = selectAssetsByMediaType(media_type, limit, offset, ::Asset_index)

  public fun <T : Any> searchAssets(
    query: String,
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      file_path: String,
      relative_path: String,
      media_type: String,
      subfolder: String,
      tags: String,
      auto_labels: String,
      ocr_text: String?,
      cloud_description: String?,
      page_uuids: String,
      size_bytes: Long,
      imported_at_ms: Long,
      ml_processed: Long,
      ml_attempted_at: Long?,
      ml_failed: Long,
      content_hash: String?,
      is_orphan: Long,
      ml_tags_source: String,
    ) -> T,
  ): Query<T> = SearchAssetsQuery(query, limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getString(8),
      cursor.getString(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13),
      cursor.getLong(14)!!,
      cursor.getString(15),
      cursor.getLong(16)!!,
      cursor.getString(17)!!
    )
  }

  public fun searchAssets(
    query: String,
    limit: Long,
    offset: Long,
  ): Query<Asset_index> = searchAssets(query, limit, offset, ::Asset_index)

  public fun <T : Any> selectUnprocessedAssets(
    limit: Long,
    offset: Long,
    mapper: (
      uuid: String,
      file_path: String,
      relative_path: String,
      media_type: String,
      subfolder: String,
      tags: String,
      auto_labels: String,
      ocr_text: String?,
      cloud_description: String?,
      page_uuids: String,
      size_bytes: Long,
      imported_at_ms: Long,
      ml_processed: Long,
      ml_attempted_at: Long?,
      ml_failed: Long,
      content_hash: String?,
      is_orphan: Long,
      ml_tags_source: String,
    ) -> T,
  ): Query<T> = SelectUnprocessedAssetsQuery(limit, offset) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getString(6)!!,
      cursor.getString(7),
      cursor.getString(8),
      cursor.getString(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13),
      cursor.getLong(14)!!,
      cursor.getString(15),
      cursor.getLong(16)!!,
      cursor.getString(17)!!
    )
  }

  public fun selectUnprocessedAssets(limit: Long, offset: Long): Query<Asset_index> = selectUnprocessedAssets(limit, offset, ::Asset_index)

  public fun countUnprocessedAssets(): Query<Long> = Query(-1_728_676_438, arrayOf("asset_index"), driver, "SteleDatabase.sq", "countUnprocessedAssets", "SELECT COUNT(*) FROM asset_index WHERE ml_processed = 0 AND ml_failed = 0") { cursor ->
    cursor.getLong(0)!!
  }

  public fun countAssets(): Query<Long> = Query(-611_689_007, arrayOf("asset_index"), driver, "SteleDatabase.sq", "countAssets", "SELECT COUNT(*) FROM asset_index") { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAllPendingMoves(mapper: (
    id: Long,
    asset_uuid: String,
    old_file_path: String,
    new_file_path: String,
    old_relative_path: String,
    new_relative_path: String,
    created_at_ms: Long,
  ) -> T): Query<T> = Query(-947_239_761, arrayOf("pending_asset_moves"), driver, "SteleDatabase.sq", "selectAllPendingMoves", "SELECT pending_asset_moves.id, pending_asset_moves.asset_uuid, pending_asset_moves.old_file_path, pending_asset_moves.new_file_path, pending_asset_moves.old_relative_path, pending_asset_moves.new_relative_path, pending_asset_moves.created_at_ms FROM pending_asset_moves") { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getString(5)!!,
      cursor.getLong(6)!!
    )
  }

  public fun selectAllPendingMoves(): Query<Pending_asset_moves> = selectAllPendingMoves(::Pending_asset_moves)

  public fun selectLastInsertRowId(): ExecutableQuery<Long> = Query(998_850_025, driver, "SteleDatabase.sq", "selectLastInsertRowId", "SELECT last_insert_rowid()") { cursor ->
    cursor.getLong(0)!!
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockParent(parent_uuid: String?, uuid: String): Long {
    val result = driver.execute(805_213_229, """UPDATE blocks SET parent_uuid = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(805_213_229) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockParentPositionAndLevel(
    parent_uuid: String?,
    position: String,
    level: Long,
    uuid: String,
  ): Long {
    val result = driver.execute(-1_913_429_373, """UPDATE blocks SET parent_uuid = ?, position = ?, level = ? WHERE uuid = ?""", 4) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, position)
          bindLong(parameterIndex++, level)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-1_913_429_373) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockHierarchy(
    parent_uuid: String?,
    left_uuid: String?,
    position: String,
    level: Long,
    uuid: String,
  ): Long {
    val result = driver.execute(759_870_418, """UPDATE blocks SET parent_uuid = ?, left_uuid = ?, position = ?, level = ? WHERE uuid = ?""", 5) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, left_uuid)
          bindString(parameterIndex++, position)
          bindLong(parameterIndex++, level)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(759_870_418) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockPositionOnly(position: String, uuid: String): Long {
    val result = driver.execute(1_997_868_472, """UPDATE blocks SET position = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, position)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_997_868_472) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun shiftRootBlockPositionsFrom(page_uuid: String, position: String): Long {
    val result = driver.execute(-2_055_563_862, """
        |UPDATE blocks SET position = position + 1
        |WHERE page_uuid = ? AND parent_uuid IS NULL AND position >= ?
        """.trimMargin(), 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, position)
        }.await()
    notifyQueries(-2_055_563_862) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun shiftChildBlockPositionsFrom(parent_uuid: String?, position: String): Long {
    val result = driver.execute(null, """
        |UPDATE blocks SET position = position + 1
        |WHERE parent_uuid ${ if (parent_uuid == null) "IS" else "=" } ? AND position >= ?
        """.trimMargin(), 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, position)
        }.await()
    notifyQueries(-1_085_809_534) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockContent(
    content: String,
    updated_at: Long,
    content_hash: String?,
    uuid: String,
  ): Long {
    val result = driver.execute(936_712_534, """UPDATE blocks SET content = ?, updated_at = ?, version = version + 1, content_hash = ? WHERE uuid = ?""", 4) {
          var parameterIndex = 0
          bindString(parameterIndex++, content)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, content_hash)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(936_712_534) { emit ->
      emit("blocks")
      emit("blocks_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockFull(
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    updated_at: Long,
    properties: String?,
    content_hash: String?,
    block_type: String,
    uuid: String,
  ): Long {
    val result = driver.execute(1_055_306_450, """
        |UPDATE blocks SET
        |    page_uuid = ?, parent_uuid = ?, left_uuid = ?, content = ?,
        |    level = ?, position = ?, updated_at = ?, properties = ?,
        |    version = version + 1, content_hash = ?, block_type = ?
        |WHERE uuid = ?
        """.trimMargin(), 11) {
          var parameterIndex = 0
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, left_uuid)
          bindString(parameterIndex++, content)
          bindLong(parameterIndex++, level)
          bindString(parameterIndex++, position)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, properties)
          bindString(parameterIndex++, content_hash)
          bindString(parameterIndex++, block_type)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_055_306_450) { emit ->
      emit("blocks")
      emit("blocks_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockLevelOnly(level: Long, uuid: String): Long {
    val result = driver.execute(1_283_321_581, """UPDATE blocks SET level = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, level)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_283_321_581) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockLeftUuid(left_uuid: String?, uuid: String): Long {
    val result = driver.execute(1_259_103_013, """UPDATE blocks SET left_uuid = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, left_uuid)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_259_103_013) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockProperties(properties: String?, uuid: String): Long {
    val result = driver.execute(10_067_766, """UPDATE blocks SET properties = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, properties)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(10_067_766) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteBlockByUuid(uuid: String): Long {
    val result = driver.execute(1_663_446_547, """DELETE FROM blocks WHERE uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_663_446_547) { emit ->
      emit("block_references")
      emit("blocks")
      emit("blocks_fts")
      emit("properties")
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteBlockChildren(parent_uuid: String?): Long {
    val result = driver.execute(null, """DELETE FROM blocks WHERE parent_uuid ${ if (parent_uuid == null) "IS" else "=" } ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
        }.await()
    notifyQueries(948_586_272) { emit ->
      emit("block_references")
      emit("blocks")
      emit("blocks_fts")
      emit("properties")
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteAllBlocks(): Long {
    val result = driver.execute(-1_165_677_061, """DELETE FROM blocks""", 0).await()
    notifyQueries(-1_165_677_061) { emit ->
      emit("block_references")
      emit("blocks")
      emit("blocks_fts")
      emit("properties")
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteBlocksByPageUuid(page_uuid: String): Long {
    val result = driver.execute(-2_119_562_093, """DELETE FROM blocks WHERE page_uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, page_uuid)
        }.await()
    notifyQueries(-2_119_562_093) { emit ->
      emit("block_references")
      emit("blocks")
      emit("blocks_fts")
      emit("properties")
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteBlocksByPageUuids(page_uuid: Collection<String>): Long {
    val page_uuidIndexes = createArguments(count = page_uuid.size)
    val result = driver.execute(null, """DELETE FROM blocks WHERE page_uuid IN $page_uuidIndexes""", page_uuid.size) {
          var parameterIndex = 0
          page_uuid.forEach { page_uuid_ ->
            bindString(parameterIndex++, page_uuid_)
          }
        }.await()
    notifyQueries(-1_281_915_328) { emit ->
      emit("block_references")
      emit("blocks")
      emit("blocks_fts")
      emit("properties")
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertBlock(
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
  ): Long {
    val result = driver.execute(2_142_163_379, """
        |INSERT OR REPLACE INTO blocks (uuid, page_uuid, parent_uuid, left_uuid, content, level, position, created_at, updated_at, properties, version, content_hash, block_type)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 13) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, left_uuid)
          bindString(parameterIndex++, content)
          bindLong(parameterIndex++, level)
          bindString(parameterIndex++, position)
          bindLong(parameterIndex++, created_at)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, properties)
          bindLong(parameterIndex++, version)
          bindString(parameterIndex++, content_hash)
          bindString(parameterIndex++, block_type)
        }.await()
    notifyQueries(2_142_163_379) { emit ->
      emit("blocks")
      emit("blocks_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockForSave(
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: String,
    updated_at: Long,
    properties: String?,
    version: Long,
    content_hash: String?,
    block_type: String,
    uuid: String,
  ): Long {
    val result = driver.execute(-693_036_349, """
        |UPDATE blocks
        |SET page_uuid = ?, parent_uuid = ?, left_uuid = ?, content = ?, level = ?,
        |    position = ?, updated_at = ?, properties = ?, version = ?, content_hash = ?, block_type = ?
        |WHERE uuid = ?
        """.trimMargin(), 12) {
          var parameterIndex = 0
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, left_uuid)
          bindString(parameterIndex++, content)
          bindLong(parameterIndex++, level)
          bindString(parameterIndex++, position)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, properties)
          bindLong(parameterIndex++, version)
          bindString(parameterIndex++, content_hash)
          bindString(parameterIndex++, block_type)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-693_036_349) { emit ->
      emit("blocks")
      emit("blocks_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePageProperties(properties: String?, uuid: String): Long {
    val result = driver.execute(1_668_226_252, """UPDATE pages SET properties = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, properties)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_668_226_252) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePageName(name: String, uuid: String): Long {
    val result = driver.execute(-658_837_276, """UPDATE pages SET name = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, name)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-658_837_276) { emit ->
      emit("pages")
      emit("pages_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePage(
    namespace: String?,
    file_path: String?,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    section_id: String,
    uuid: String,
  ): Long {
    val result = driver.execute(1_994_000_057, """
        |UPDATE pages SET
        |    namespace = ?,
        |    file_path = ?,
        |    updated_at = ?,
        |    properties = ?,
        |    version = ?,
        |    is_favorite = ?,
        |    is_journal = ?,
        |    journal_date = ?,
        |    is_content_loaded = ?,
        |    section_id = ?
        |WHERE uuid = ?
        """.trimMargin(), 11) {
          var parameterIndex = 0
          bindString(parameterIndex++, namespace)
          bindString(parameterIndex++, file_path)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, properties)
          bindLong(parameterIndex++, version)
          bindLong(parameterIndex++, is_favorite)
          bindLong(parameterIndex++, is_journal)
          bindString(parameterIndex++, journal_date)
          bindLong(parameterIndex++, is_content_loaded)
          bindString(parameterIndex++, section_id)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_994_000_057) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deletePageByUuid(uuid: String): Long {
    val result = driver.execute(1_953_287_981, """DELETE FROM pages WHERE uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_953_287_981) { emit ->
      emit("blocks")
      emit("pages")
      emit("pages_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertPage(
    uuid: String,
    name: String,
    namespace: String?,
    file_path: String?,
    created_at: Long,
    updated_at: Long,
    properties: String?,
    version: Long,
    is_favorite: Long?,
    is_journal: Long?,
    journal_date: String?,
    is_content_loaded: Long,
    section_id: String,
  ): Long {
    val result = driver.execute(-1_038_870_359, """
        |INSERT OR IGNORE INTO pages (uuid, name, namespace, file_path, created_at, updated_at, properties, version, is_favorite, is_journal, journal_date, is_content_loaded, section_id)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 13) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, name)
          bindString(parameterIndex++, namespace)
          bindString(parameterIndex++, file_path)
          bindLong(parameterIndex++, created_at)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, properties)
          bindLong(parameterIndex++, version)
          bindLong(parameterIndex++, is_favorite)
          bindLong(parameterIndex++, is_journal)
          bindString(parameterIndex++, journal_date)
          bindLong(parameterIndex++, is_content_loaded)
          bindString(parameterIndex++, section_id)
        }.await()
    notifyQueries(-1_038_870_359) { emit ->
      emit("pages")
      emit("pages_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePageFavorite(is_favorite: Long?, uuid: String): Long {
    val result = driver.execute(-10_196_107, """UPDATE pages SET is_favorite = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, is_favorite)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-10_196_107) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteAllPages(): Long {
    val result = driver.execute(1_637_559_471, """DELETE FROM pages""", 0).await()
    notifyQueries(1_637_559_471) { emit ->
      emit("blocks")
      emit("pages")
      emit("pages_fts")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertBlockReference(
    from_block_uuid: String,
    to_block_uuid: String,
    created_at: Long,
  ): Long {
    val result = driver.execute(1_226_032_856, """
        |INSERT OR REPLACE INTO block_references (from_block_uuid, to_block_uuid, created_at)
        |VALUES (?, ?, ?)
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, from_block_uuid)
          bindString(parameterIndex++, to_block_uuid)
          bindLong(parameterIndex++, created_at)
        }.await()
    notifyQueries(1_226_032_856) { emit ->
      emit("block_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteBlockReference(from_block_uuid: String, to_block_uuid: String): Long {
    val result = driver.execute(-1_489_470_902, """DELETE FROM block_references WHERE from_block_uuid = ? AND to_block_uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, from_block_uuid)
          bindString(parameterIndex++, to_block_uuid)
        }.await()
    notifyQueries(-1_489_470_902) { emit ->
      emit("block_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertPluginData(
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
    value_: String,
    created_at: Long,
    updated_at: Long?,
  ): Long {
    val result = driver.execute(-1_794_622_441, """
        |INSERT INTO plugin_data (plugin_id, entity_type, entity_uuid, key, value, created_at, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 7) {
          var parameterIndex = 0
          bindString(parameterIndex++, plugin_id)
          bindString(parameterIndex++, entity_type)
          bindString(parameterIndex++, entity_uuid)
          bindString(parameterIndex++, key)
          bindString(parameterIndex++, value_)
          bindLong(parameterIndex++, created_at)
          bindLong(parameterIndex++, updated_at)
        }.await()
    notifyQueries(-1_794_622_441) { emit ->
      emit("plugin_data")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePluginData(
    value_: String,
    updated_at: Long?,
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
  ): Long {
    val result = driver.execute(998_727_207, """UPDATE plugin_data SET value = ?, updated_at = ? WHERE plugin_id = ? AND entity_type = ? AND entity_uuid = ? AND key = ?""", 6) {
          var parameterIndex = 0
          bindString(parameterIndex++, value_)
          bindLong(parameterIndex++, updated_at)
          bindString(parameterIndex++, plugin_id)
          bindString(parameterIndex++, entity_type)
          bindString(parameterIndex++, entity_uuid)
          bindString(parameterIndex++, key)
        }.await()
    notifyQueries(998_727_207) { emit ->
      emit("plugin_data")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun upsertPluginData(
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
    value_: String,
    created_at: Long,
    updated_at: Long?,
  ): Long {
    val result = driver.execute(-610_961_651, """
        |INSERT OR REPLACE INTO plugin_data (plugin_id, entity_type, entity_uuid, key, value, created_at, updated_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 7) {
          var parameterIndex = 0
          bindString(parameterIndex++, plugin_id)
          bindString(parameterIndex++, entity_type)
          bindString(parameterIndex++, entity_uuid)
          bindString(parameterIndex++, key)
          bindString(parameterIndex++, value_)
          bindLong(parameterIndex++, created_at)
          bindLong(parameterIndex++, updated_at)
        }.await()
    notifyQueries(-610_961_651) { emit ->
      emit("plugin_data")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deletePluginData(
    plugin_id: String,
    entity_type: String,
    entity_uuid: String,
    key: String,
  ): Long {
    val result = driver.execute(761_559_177, """DELETE FROM plugin_data WHERE plugin_id = ? AND entity_type = ? AND entity_uuid = ? AND key = ?""", 4) {
          var parameterIndex = 0
          bindString(parameterIndex++, plugin_id)
          bindString(parameterIndex++, entity_type)
          bindString(parameterIndex++, entity_uuid)
          bindString(parameterIndex++, key)
        }.await()
    notifyQueries(761_559_177) { emit ->
      emit("plugin_data")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deletePluginDataByPlugin(plugin_id: String): Long {
    val result = driver.execute(1_301_794_323, """DELETE FROM plugin_data WHERE plugin_id = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, plugin_id)
        }.await()
    notifyQueries(1_301_794_323) { emit ->
      emit("plugin_data")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deletePluginDataByEntity(entity_type: String, entity_uuid: String): Long {
    val result = driver.execute(988_693_187, """DELETE FROM plugin_data WHERE entity_type = ? AND entity_uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, entity_type)
          bindString(parameterIndex++, entity_uuid)
        }.await()
    notifyQueries(988_693_187) { emit ->
      emit("plugin_data")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertPageVisitIfAbsent(page_uuid: String, last_visited_at: Long): Long {
    val result = driver.execute(-671_925_512, """
        |INSERT OR IGNORE INTO page_visits (page_uuid, visit_count, last_visited_at)
        |VALUES (?, 0, ?)
        """.trimMargin(), 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, page_uuid)
          bindLong(parameterIndex++, last_visited_at)
        }.await()
    notifyQueries(-671_925_512) { emit ->
      emit("page_visits")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePageVisit(last_visited_at: Long, page_uuid: String): Long {
    val result = driver.execute(1_058_513_426, """
        |UPDATE page_visits
        |SET visit_count = visit_count + 1, last_visited_at = ?
        |WHERE page_uuid = ?
        """.trimMargin(), 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, last_visited_at)
          bindString(parameterIndex++, page_uuid)
        }.await()
    notifyQueries(1_058_513_426) { emit ->
      emit("page_visits")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun recomputeBacklinkCountForPage(name: String): Long {
    val result = driver.execute(1_594_896_973, """
        |UPDATE pages SET backlink_count = (
        |    SELECT COUNT(*) FROM blocks
        |    WHERE blocks.content LIKE '%[[' || pages.name || ']]%'
        |) WHERE pages.name = ?
        """.trimMargin(), 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, name)
        }.await()
    notifyQueries(1_594_896_973) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun setPageBacklinkCount(backlink_count: Long, name: String): Long {
    val result = driver.execute(1_739_125_790, """UPDATE pages SET backlink_count = ? WHERE name = ?""", 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, backlink_count)
          bindString(parameterIndex++, name)
        }.await()
    notifyQueries(1_739_125_790) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertWikilinkReference(block_uuid: String, page_name: String): Long {
    val result = driver.execute(1_899_879_143, """INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name) VALUES (?, ?)""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, block_uuid)
          bindString(parameterIndex++, page_name)
        }.await()
    notifyQueries(1_899_879_143) { emit ->
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteWikilinkReferencesForBlock(block_uuid: String): Long {
    val result = driver.execute(1_887_875_138, """DELETE FROM wikilink_references WHERE block_uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, block_uuid)
        }.await()
    notifyQueries(1_887_875_138) { emit ->
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteWikilinkReferencesForPageName(page_name: String): Long {
    val result = driver.execute(615_443_941, """DELETE FROM wikilink_references WHERE page_name = ? COLLATE NOCASE""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, page_name)
        }.await()
    notifyQueries(615_443_941) { emit ->
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun recomputeBacklinkCountFromIndex(name: String): Long {
    val result = driver.execute(-1_528_089_251, """
        |UPDATE pages SET backlink_count = (
        |    SELECT COUNT(*) FROM wikilink_references WHERE page_name = pages.name COLLATE NOCASE
        |) WHERE pages.name = ?
        """.trimMargin(), 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, name)
        }.await()
    notifyQueries(-1_528_089_251) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun recomputeBacklinkCountsForPages(name: Collection<String>): Long {
    val nameIndexes = createArguments(count = name.size)
    val result = driver.execute(null, """
        |UPDATE pages SET backlink_count = (
        |    SELECT COUNT(*) FROM wikilink_references WHERE page_name = pages.name COLLATE NOCASE
        |) WHERE pages.name IN $nameIndexes
        """.trimMargin(), name.size) {
          var parameterIndex = 0
          name.forEach { name_ ->
            bindString(parameterIndex++, name_)
          }
        }.await()
    notifyQueries(-484_178_877) { emit ->
      emit("pages")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateWikilinkPageNameForRename(newName: String, oldName: String): Long {
    val result = driver.execute(738_521_177, """UPDATE OR IGNORE wikilink_references SET page_name = ? WHERE page_name = ? COLLATE NOCASE""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, newName)
          bindString(parameterIndex++, oldName)
        }.await()
    notifyQueries(738_521_177) { emit ->
      emit("wikilink_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun upsertMetadata(key: String, value_: String): Long {
    val result = driver.execute(421_270_719, """INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, key)
          bindString(parameterIndex++, value_)
        }.await()
    notifyQueries(421_270_719) { emit ->
      emit("metadata")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockUuidForMigration(uuid: String, uuid_: String): Long {
    val result = driver.execute(-1_394_940_893, """UPDATE blocks SET uuid = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, uuid_)
        }.await()
    notifyQueries(-1_394_940_893) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateParentUuidForMigration(parent_uuid: String?, parent_uuid_: String?): Long {
    val result = driver.execute(null, """UPDATE blocks SET parent_uuid = ? WHERE parent_uuid ${ if (parent_uuid_ == null) "IS" else "=" } ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, parent_uuid_)
        }.await()
    notifyQueries(1_196_885_716) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateLeftUuidForMigration(left_uuid: String?, left_uuid_: String?): Long {
    val result = driver.execute(null, """UPDATE blocks SET left_uuid = ? WHERE left_uuid ${ if (left_uuid_ == null) "IS" else "=" } ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, left_uuid)
          bindString(parameterIndex++, left_uuid_)
        }.await()
    notifyQueries(-750_147_919) { emit ->
      emit("blocks")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockReferencesFromForMigration(from_block_uuid: String, from_block_uuid_: String): Long {
    val result = driver.execute(1_262_608_506, """UPDATE block_references SET from_block_uuid = ? WHERE from_block_uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, from_block_uuid)
          bindString(parameterIndex++, from_block_uuid_)
        }.await()
    notifyQueries(1_262_608_506) { emit ->
      emit("block_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateBlockReferencesToForMigration(to_block_uuid: String, to_block_uuid_: String): Long {
    val result = driver.execute(-1_154_362_229, """UPDATE block_references SET to_block_uuid = ? WHERE to_block_uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, to_block_uuid)
          bindString(parameterIndex++, to_block_uuid_)
        }.await()
    notifyQueries(-1_154_362_229) { emit ->
      emit("block_references")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updatePropertiesBlockUuidForMigration(block_uuid: String, block_uuid_: String): Long {
    val result = driver.execute(1_878_397_232, """UPDATE properties SET block_uuid = ? WHERE block_uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, block_uuid)
          bindString(parameterIndex++, block_uuid_)
        }.await()
    notifyQueries(1_878_397_232) { emit ->
      emit("properties")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertOperation(
    op_id: String,
    session_id: String,
    seq: Long,
    op_type: String,
    entity_uuid: String?,
    page_uuid: String?,
    payload: String,
    created_at: Long,
  ): Long {
    val result = driver.execute(-1_292_181_619, """
        |INSERT INTO operations (op_id, session_id, seq, op_type, entity_uuid, page_uuid, payload, created_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 8) {
          var parameterIndex = 0
          bindString(parameterIndex++, op_id)
          bindString(parameterIndex++, session_id)
          bindLong(parameterIndex++, seq)
          bindString(parameterIndex++, op_type)
          bindString(parameterIndex++, entity_uuid)
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, payload)
          bindLong(parameterIndex++, created_at)
        }.await()
    notifyQueries(-1_292_181_619) { emit ->
      emit("operations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteOperationsBefore(session_id: String, seq: Long): Long {
    val result = driver.execute(-562_863_977, """DELETE FROM operations WHERE session_id = ? AND seq < ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, session_id)
          bindLong(parameterIndex++, seq)
        }.await()
    notifyQueries(-562_863_977) { emit ->
      emit("operations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun upsertLogicalClock(session_id: String, seq: Long): Long {
    val result = driver.execute(-601_899_243, """INSERT OR REPLACE INTO logical_clock (session_id, seq) VALUES (?, ?)""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, session_id)
          bindLong(parameterIndex++, seq)
        }.await()
    notifyQueries(-601_899_243) { emit ->
      emit("logical_clock")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertMigrationRecord(
    id: String,
    graph_id: String,
    description: String,
    checksum: String,
    applied_at: Long,
    execution_ms: Long,
    status: String,
    applied_by: String,
    execution_order: Long,
    changes_applied: Long,
    error_message: String?,
  ): Long {
    val result = driver.execute(1_305_497_445, """
        |INSERT INTO migration_changelog
        |    (id, graph_id, description, checksum, applied_at, execution_ms, status, applied_by, execution_order, changes_applied, error_message)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 11) {
          var parameterIndex = 0
          bindString(parameterIndex++, id)
          bindString(parameterIndex++, graph_id)
          bindString(parameterIndex++, description)
          bindString(parameterIndex++, checksum)
          bindLong(parameterIndex++, applied_at)
          bindLong(parameterIndex++, execution_ms)
          bindString(parameterIndex++, status)
          bindString(parameterIndex++, applied_by)
          bindLong(parameterIndex++, execution_order)
          bindLong(parameterIndex++, changes_applied)
          bindString(parameterIndex++, error_message)
        }.await()
    notifyQueries(1_305_497_445) { emit ->
      emit("migration_changelog")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateMigrationStatus(
    status: String,
    error_message: String?,
    execution_ms: Long,
    changes_applied: Long,
    id: String,
    graph_id: String,
  ): Long {
    val result = driver.execute(21_715_702, """
        |UPDATE migration_changelog SET status = ?, error_message = ?, execution_ms = ?, changes_applied = ?
        |WHERE id = ? AND graph_id = ?
        """.trimMargin(), 6) {
          var parameterIndex = 0
          bindString(parameterIndex++, status)
          bindString(parameterIndex++, error_message)
          bindLong(parameterIndex++, execution_ms)
          bindLong(parameterIndex++, changes_applied)
          bindString(parameterIndex++, id)
          bindString(parameterIndex++, graph_id)
        }.await()
    notifyQueries(21_715_702) { emit ->
      emit("migration_changelog")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteMigrationRecord(id: String, graph_id: String): Long {
    val result = driver.execute(-1_270_740_429, """DELETE FROM migration_changelog WHERE id = ? AND graph_id = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, id)
          bindString(parameterIndex++, graph_id)
        }.await()
    notifyQueries(-1_270_740_429) { emit ->
      emit("migration_changelog")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateMigrationChecksum(
    checksum: String,
    id: String,
    graph_id: String,
  ): Long {
    val result = driver.execute(-387_828_153, """UPDATE migration_changelog SET checksum = ? WHERE id = ? AND graph_id = ?""", 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, checksum)
          bindString(parameterIndex++, id)
          bindString(parameterIndex++, graph_id)
        }.await()
    notifyQueries(-387_828_153) { emit ->
      emit("migration_changelog")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertOrReplaceGitConfig(
    graph_id: String,
    repo_root: String,
    wiki_subdir: String,
    remote_name: String,
    remote_branch: String,
    auth_type: String,
    ssh_key_path: String?,
    ssh_key_passphrase_key: String?,
    https_token_key: String?,
    oauth_token_key: String?,
    poll_interval_minutes: Long,
    auto_commit: Long,
    commit_message_template: String,
  ): Long {
    val result = driver.execute(66_633_213, """
        |INSERT OR REPLACE INTO git_config(
        |    graph_id, repo_root, wiki_subdir, remote_name, remote_branch,
        |    auth_type, ssh_key_path, ssh_key_passphrase_key, https_token_key, oauth_token_key,
        |    poll_interval_minutes, auto_commit, commit_message_template
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 13) {
          var parameterIndex = 0
          bindString(parameterIndex++, graph_id)
          bindString(parameterIndex++, repo_root)
          bindString(parameterIndex++, wiki_subdir)
          bindString(parameterIndex++, remote_name)
          bindString(parameterIndex++, remote_branch)
          bindString(parameterIndex++, auth_type)
          bindString(parameterIndex++, ssh_key_path)
          bindString(parameterIndex++, ssh_key_passphrase_key)
          bindString(parameterIndex++, https_token_key)
          bindString(parameterIndex++, oauth_token_key)
          bindLong(parameterIndex++, poll_interval_minutes)
          bindLong(parameterIndex++, auto_commit)
          bindString(parameterIndex++, commit_message_template)
        }.await()
    notifyQueries(66_633_213) { emit ->
      emit("git_config")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteGitConfig(graph_id: String): Long {
    val result = driver.execute(-1_517_498_072, """DELETE FROM git_config WHERE graph_id = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, graph_id)
        }.await()
    notifyQueries(-1_517_498_072) { emit ->
      emit("git_config")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertImageAnnotation(
    uuid: String,
    block_uuid: String,
    page_uuid: String,
    graph_path: String,
    file_path: String,
    thumbnail_path: String?,
    source: String,
    source_uri: String?,
    captured_at_ms: Long?,
    imported_at_ms: Long,
    calibration_method: String,
    pixels_per_meter: Double,
    calibration_confidence_pct: Long,
    unit: String,
    tags: String,
    lat_lng: String?,
    altitude_m: Double?,
    bearing_deg: Double?,
    pitch_deg: Double?,
    roll_deg: Double?,
    focal_length_mm: Double?,
    focal_length_35mm_eq: Double?,
    camera_make: String?,
    camera_model: String?,
  ): Long {
    val result = driver.execute(390_467_728, """
        |INSERT OR REPLACE INTO image_annotations (
        |    uuid, block_uuid, page_uuid, graph_path, file_path, thumbnail_path,
        |    source, source_uri, captured_at_ms, imported_at_ms,
        |    calibration_method, pixels_per_meter, calibration_confidence_pct,
        |    unit, tags, lat_lng, altitude_m, bearing_deg, pitch_deg, roll_deg,
        |    focal_length_mm, focal_length_35mm_eq, camera_make, camera_model
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 24) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, block_uuid)
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, graph_path)
          bindString(parameterIndex++, file_path)
          bindString(parameterIndex++, thumbnail_path)
          bindString(parameterIndex++, source)
          bindString(parameterIndex++, source_uri)
          bindLong(parameterIndex++, captured_at_ms)
          bindLong(parameterIndex++, imported_at_ms)
          bindString(parameterIndex++, calibration_method)
          bindDouble(parameterIndex++, pixels_per_meter)
          bindLong(parameterIndex++, calibration_confidence_pct)
          bindString(parameterIndex++, unit)
          bindString(parameterIndex++, tags)
          bindString(parameterIndex++, lat_lng)
          bindDouble(parameterIndex++, altitude_m)
          bindDouble(parameterIndex++, bearing_deg)
          bindDouble(parameterIndex++, pitch_deg)
          bindDouble(parameterIndex++, roll_deg)
          bindDouble(parameterIndex++, focal_length_mm)
          bindDouble(parameterIndex++, focal_length_35mm_eq)
          bindString(parameterIndex++, camera_make)
          bindString(parameterIndex++, camera_model)
        }.await()
    notifyQueries(390_467_728) { emit ->
      emit("image_annotations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateImageAnnotation(
    block_uuid: String,
    page_uuid: String,
    graph_path: String,
    file_path: String,
    thumbnail_path: String?,
    source: String,
    source_uri: String?,
    captured_at_ms: Long?,
    imported_at_ms: Long,
    calibration_method: String,
    pixels_per_meter: Double,
    calibration_confidence_pct: Long,
    unit: String,
    tags: String,
    lat_lng: String?,
    altitude_m: Double?,
    bearing_deg: Double?,
    pitch_deg: Double?,
    roll_deg: Double?,
    focal_length_mm: Double?,
    focal_length_35mm_eq: Double?,
    camera_make: String?,
    camera_model: String?,
    uuid: String,
  ): Long {
    val result = driver.execute(-935_741_312, """
        |UPDATE image_annotations SET
        |    block_uuid = ?,
        |    page_uuid = ?,
        |    graph_path = ?,
        |    file_path = ?,
        |    thumbnail_path = ?,
        |    source = ?,
        |    source_uri = ?,
        |    captured_at_ms = ?,
        |    imported_at_ms = ?,
        |    calibration_method = ?,
        |    pixels_per_meter = ?,
        |    calibration_confidence_pct = ?,
        |    unit = ?,
        |    tags = ?,
        |    lat_lng = ?,
        |    altitude_m = ?,
        |    bearing_deg = ?,
        |    pitch_deg = ?,
        |    roll_deg = ?,
        |    focal_length_mm = ?,
        |    focal_length_35mm_eq = ?,
        |    camera_make = ?,
        |    camera_model = ?
        |WHERE uuid = ?
        """.trimMargin(), 24) {
          var parameterIndex = 0
          bindString(parameterIndex++, block_uuid)
          bindString(parameterIndex++, page_uuid)
          bindString(parameterIndex++, graph_path)
          bindString(parameterIndex++, file_path)
          bindString(parameterIndex++, thumbnail_path)
          bindString(parameterIndex++, source)
          bindString(parameterIndex++, source_uri)
          bindLong(parameterIndex++, captured_at_ms)
          bindLong(parameterIndex++, imported_at_ms)
          bindString(parameterIndex++, calibration_method)
          bindDouble(parameterIndex++, pixels_per_meter)
          bindLong(parameterIndex++, calibration_confidence_pct)
          bindString(parameterIndex++, unit)
          bindString(parameterIndex++, tags)
          bindString(parameterIndex++, lat_lng)
          bindDouble(parameterIndex++, altitude_m)
          bindDouble(parameterIndex++, bearing_deg)
          bindDouble(parameterIndex++, pitch_deg)
          bindDouble(parameterIndex++, roll_deg)
          bindDouble(parameterIndex++, focal_length_mm)
          bindDouble(parameterIndex++, focal_length_35mm_eq)
          bindString(parameterIndex++, camera_make)
          bindString(parameterIndex++, camera_model)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-935_741_312) { emit ->
      emit("image_annotations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteImageAnnotation(uuid: String): Long {
    val result = driver.execute(2_109_197_150, """DELETE FROM image_annotations WHERE uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(2_109_197_150) { emit ->
      emit("image_annotations")
      emit("measurement_annotations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertMeasurementAnnotation(
    uuid: String,
    image_uuid: String,
    annotation_type: String,
    normalized_points: String,
    value_meters: Double?,
    value_display: String?,
    label: String?,
    color_hex: String,
    ble_device_id: String?,
  ): Long {
    val result = driver.execute(-368_694_991, """
        |INSERT OR REPLACE INTO measurement_annotations (
        |    uuid, image_uuid, annotation_type, normalized_points,
        |    value_meters, value_display, label, color_hex, ble_device_id
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 9) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, image_uuid)
          bindString(parameterIndex++, annotation_type)
          bindString(parameterIndex++, normalized_points)
          bindDouble(parameterIndex++, value_meters)
          bindString(parameterIndex++, value_display)
          bindString(parameterIndex++, label)
          bindString(parameterIndex++, color_hex)
          bindString(parameterIndex++, ble_device_id)
        }.await()
    notifyQueries(-368_694_991) { emit ->
      emit("measurement_annotations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteMeasurementsForImage(image_uuid: String): Long {
    val result = driver.execute(-2_064_403_595, """DELETE FROM measurement_annotations WHERE image_uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, image_uuid)
        }.await()
    notifyQueries(-2_064_403_595) { emit ->
      emit("measurement_annotations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteMeasurementAnnotation(uuid: String): Long {
    val result = driver.execute(-804_777_601, """DELETE FROM measurement_annotations WHERE uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-804_777_601) { emit ->
      emit("measurement_annotations")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertAsset(
    uuid: String,
    file_path: String,
    relative_path: String,
    media_type: String,
    subfolder: String,
    tags: String,
    auto_labels: String,
    ocr_text: String?,
    cloud_description: String?,
    page_uuids: String,
    size_bytes: Long,
    imported_at_ms: Long,
    content_hash: String?,
  ): Long {
    val result = driver.execute(2_141_452_310, """
        |INSERT INTO asset_index(uuid, file_path, relative_path, media_type, subfolder, tags, auto_labels, ocr_text, cloud_description, page_uuids, size_bytes, imported_at_ms, ml_processed, ml_failed, content_hash, is_orphan, ml_tags_source)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 0, 'NONE')
        """.trimMargin(), 13) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, file_path)
          bindString(parameterIndex++, relative_path)
          bindString(parameterIndex++, media_type)
          bindString(parameterIndex++, subfolder)
          bindString(parameterIndex++, tags)
          bindString(parameterIndex++, auto_labels)
          bindString(parameterIndex++, ocr_text)
          bindString(parameterIndex++, cloud_description)
          bindString(parameterIndex++, page_uuids)
          bindLong(parameterIndex++, size_bytes)
          bindLong(parameterIndex++, imported_at_ms)
          bindString(parameterIndex++, content_hash)
        }.await()
    notifyQueries(2_141_452_310) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertAssetOrIgnore(
    uuid: String,
    file_path: String,
    relative_path: String,
    media_type: String,
    subfolder: String,
    tags: String,
    auto_labels: String,
    ocr_text: String?,
    cloud_description: String?,
    page_uuids: String,
    size_bytes: Long,
    imported_at_ms: Long,
    content_hash: String?,
  ): Long {
    val result = driver.execute(1_434_560_459, """
        |INSERT OR IGNORE INTO asset_index(uuid, file_path, relative_path, media_type, subfolder, tags, auto_labels, ocr_text, cloud_description, page_uuids, size_bytes, imported_at_ms, ml_processed, ml_failed, content_hash, is_orphan, ml_tags_source)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 0, 'NONE')
        """.trimMargin(), 13) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
          bindString(parameterIndex++, file_path)
          bindString(parameterIndex++, relative_path)
          bindString(parameterIndex++, media_type)
          bindString(parameterIndex++, subfolder)
          bindString(parameterIndex++, tags)
          bindString(parameterIndex++, auto_labels)
          bindString(parameterIndex++, ocr_text)
          bindString(parameterIndex++, cloud_description)
          bindString(parameterIndex++, page_uuids)
          bindLong(parameterIndex++, size_bytes)
          bindLong(parameterIndex++, imported_at_ms)
          bindString(parameterIndex++, content_hash)
        }.await()
    notifyQueries(1_434_560_459) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateAssetFilePath(
    filePath: String,
    relativePath: String,
    uuid: String,
  ): Long {
    val result = driver.execute(1_923_840_487, """UPDATE asset_index SET file_path = ?, relative_path = ? WHERE uuid = ?""", 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, filePath)
          bindString(parameterIndex++, relativePath)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_923_840_487) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateAssetTags(tags: String, uuid: String): Long {
    val result = driver.execute(1_498_546_495, """UPDATE asset_index SET tags = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, tags)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_498_546_495) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateAssetAutoLabels(
    autoLabels: String,
    mlTagsSource: String,
    uuid: String,
  ): Long {
    val result = driver.execute(-324_615_372, """UPDATE asset_index SET auto_labels = ?, ml_tags_source = ? WHERE uuid = ?""", 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, autoLabels)
          bindString(parameterIndex++, mlTagsSource)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-324_615_372) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateAssetOcrText(ocrText: String?, uuid: String): Long {
    val result = driver.execute(1_232_601_221, """UPDATE asset_index SET ocr_text = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, ocrText)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_232_601_221) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateAssetCloudDescription(
    cloudDescription: String?,
    mlTagsSource: String,
    uuid: String,
  ): Long {
    val result = driver.execute(-280_162_643, """UPDATE asset_index SET cloud_description = ?, ml_tags_source = ? WHERE uuid = ?""", 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, cloudDescription)
          bindString(parameterIndex++, mlTagsSource)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(-280_162_643) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun markAssetMlProcessed(attemptedAt: Long?, uuid: String): Long {
    val result = driver.execute(158_555_853, """UPDATE asset_index SET ml_processed = 1, ml_attempted_at = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, attemptedAt)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(158_555_853) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun markAssetMlFailed(attemptedAt: Long?, uuid: String): Long {
    val result = driver.execute(83_642_654, """UPDATE asset_index SET ml_failed = 1, ml_attempted_at = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, attemptedAt)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(83_642_654) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun updateAssetPageUuids(pageUuids: String, uuid: String): Long {
    val result = driver.execute(1_714_875_747, """UPDATE asset_index SET page_uuids = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindString(parameterIndex++, pageUuids)
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_714_875_747) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteAsset(uuid: String): Long {
    val result = driver.execute(1_122_778_212, """DELETE FROM asset_index WHERE uuid = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, uuid)
        }.await()
    notifyQueries(1_122_778_212) { emit ->
      emit("asset_index")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertPendingMove(
    asset_uuid: String,
    old_file_path: String,
    new_file_path: String,
    old_relative_path: String,
    new_relative_path: String,
    created_at_ms: Long,
  ): Long {
    val result = driver.execute(442_412_718, """
        |INSERT INTO pending_asset_moves(asset_uuid, old_file_path, new_file_path, old_relative_path, new_relative_path, created_at_ms)
        |VALUES (?, ?, ?, ?, ?, ?)
        """.trimMargin(), 6) {
          var parameterIndex = 0
          bindString(parameterIndex++, asset_uuid)
          bindString(parameterIndex++, old_file_path)
          bindString(parameterIndex++, new_file_path)
          bindString(parameterIndex++, old_relative_path)
          bindString(parameterIndex++, new_relative_path)
          bindLong(parameterIndex++, created_at_ms)
        }.await()
    notifyQueries(442_412_718) { emit ->
      emit("pending_asset_moves")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deletePendingMove(id: Long): Long {
    val result = driver.execute(-1_920_335_748, """DELETE FROM pending_asset_moves WHERE id = ?""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, id)
        }.await()
    notifyQueries(-1_920_335_748) { emit ->
      emit("pending_asset_moves")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun pragmaWalCheckpointTruncate(): Long {
    val result = driver.execute(-158_994_413, """PRAGMA wal_checkpoint(TRUNCATE)""", 0).await()
    return result
  }

  private inner class SelectBlockByUuidQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_177_587_166, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlockByUuid"
  }

  private inner class SelectBlocksByUuidsQuery<out T : Any>(
    public val uuid: Collection<String>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val uuidIndexes = createArguments(count = uuid.size)
      return driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE uuid IN $uuidIndexes""", mapper, uuid.size) {
            var parameterIndex = 0
            uuid.forEach { uuid_ ->
              bindString(parameterIndex++, uuid_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksByUuids"
  }

  private inner class ExistsBlockByUuidQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(703_157_954, """SELECT COUNT(*) FROM blocks WHERE uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:existsBlockByUuid"
  }

  private inner class SelectAllBlocksPaginatedQuery<out T : Any>(
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-619_541_469, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks ORDER BY uuid LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAllBlocksPaginated"
  }

  private inner class SelectAllBlocksPaginatedAfterUuidQuery<out T : Any>(
    public val uuid: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_119_311_148, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE uuid > ? ORDER BY uuid LIMIT ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAllBlocksPaginatedAfterUuid"
  }

  private inner class SelectBlockChildrenQuery<out T : Any>(
    public val parent_uuid: String?,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE parent_uuid ${ if (parent_uuid == null) "IS" else "=" } ? ORDER BY position LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, parent_uuid)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlockChildren"
  }

  private inner class CountBlockChildrenQuery<out T : Any>(
    public val parent_uuid: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT COUNT(*) FROM blocks WHERE parent_uuid ${ if (parent_uuid == null) "IS" else "=" } ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, parent_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:countBlockChildren"
  }

  private inner class SelectBlockSiblingsQuery<out T : Any>(
    public val uuid: String,
    public val uuid_: String,
    public val uuid__: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(821_827_425, """
    |SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks 
    |WHERE parent_uuid IS (SELECT parent_uuid FROM blocks WHERE uuid = ?) 
    |AND page_uuid = (SELECT page_uuid FROM blocks WHERE uuid = ?)
    |AND uuid != ? 
    |ORDER BY position
    """.trimMargin(), mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
      bindString(parameterIndex++, uuid_)
      bindString(parameterIndex++, uuid__)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlockSiblings"
  }

  private inner class SelectRootBlocksQuery<out T : Any>(
    public val page_uuid: String,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_052_737_061, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE parent_uuid IS NULL AND page_uuid = ? ORDER BY position LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectRootBlocks"
  }

  private inner class CountRootBlocksQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_021_025_366, """SELECT COUNT(*) FROM blocks WHERE parent_uuid IS NULL AND page_uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:countRootBlocks"
  }

  private inner class SelectBlocksByPageUuidQuery<out T : Any>(
    public val page_uuid: String,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(264_501_732, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE page_uuid = ? ORDER BY position LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksByPageUuid"
  }

  private inner class SelectBlocksByPageUuidUnpaginatedQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-801_581_104, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE page_uuid = ? ORDER BY position""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksByPageUuidUnpaginated"
  }

  private inner class SelectBlocksWithContentLikeQuery<out T : Any>(
    public val content: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_328_715_929, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE content LIKE ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, content)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksWithContentLike"
  }

  private inner class SelectBlocksWithContentLikePaginatedQuery<out T : Any>(
    public val content: String,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_872_908_070, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE content LIKE ? ORDER BY created_at DESC LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, content)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksWithContentLikePaginated"
  }

  private inner class CountBlocksByPageUuidQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(602_074_965, """SELECT COUNT(*) FROM blocks WHERE page_uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:countBlocksByPageUuid"
  }

  private inner class SelectBlocksByParentUuidOrderedQuery<out T : Any>(
    public val parent_uuid: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE parent_uuid ${ if (parent_uuid == null) "IS" else "=" } ? ORDER BY position""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, parent_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksByParentUuidOrdered"
  }

  private inner class SelectBlocksByParentUuidsQuery<out T : Any>(
    public val parent_uuid: Collection<String?>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val parent_uuidIndexes = createArguments(count = parent_uuid.size)
      return driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE parent_uuid IN $parent_uuidIndexes ORDER BY parent_uuid, position""", mapper, parent_uuid.size) {
            var parameterIndex = 0
            parent_uuid.forEach { parent_uuid_ ->
              bindString(parameterIndex++, parent_uuid_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksByParentUuids"
  }

  private inner class SelectBlockHierarchyRecursiveQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-999_229_715, """
    |WITH RECURSIVE subtree(
    |    id, uuid, page_uuid, parent_uuid, left_uuid, content, level, position,
    |    created_at, updated_at, properties, version, content_hash, block_type, depth
    |) AS (
    |    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
    |           b.level, b.position, b.created_at, b.updated_at, b.properties,
    |           b.version, b.content_hash, b.block_type, 0 AS depth
    |    FROM blocks b
    |    WHERE b.uuid = ?
    |    UNION ALL
    |    SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
    |           b.level, b.position, b.created_at, b.updated_at, b.properties,
    |           b.version, b.content_hash, b.block_type, s.depth + 1
    |    FROM blocks b
    |    INNER JOIN subtree s ON b.parent_uuid = s.uuid
    |    WHERE s.depth < 100
    |)
    |SELECT subtree.id, subtree.uuid, subtree.page_uuid, subtree.parent_uuid, subtree.left_uuid, subtree.content, subtree.level, subtree.position, subtree.created_at, subtree.updated_at, subtree.properties, subtree.version, subtree.content_hash, subtree.block_type, subtree.depth FROM subtree
    |ORDER BY depth, parent_uuid, position
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlockHierarchyRecursive"
  }

  private inner class SelectRootBlocksByPageUuidOrderedQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(359_935_271, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE parent_uuid IS NULL AND page_uuid = ? ORDER BY position""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectRootBlocksByPageUuidOrdered"
  }

  private inner class SelectBlockByLeftUuidQuery<out T : Any>(
    public val left_uuid: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE left_uuid ${ if (left_uuid == null) "IS" else "=" } ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, left_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlockByLeftUuid"
  }

  private inner class SelectLastChildQuery<out T : Any>(
    public val parent_uuid: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE parent_uuid ${ if (parent_uuid == null) "IS" else "=" } ? ORDER BY position DESC LIMIT 1""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, parent_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectLastChild"
  }

  private inner class SelectBlocksHashByPageUuidQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_615_242_258, """SELECT uuid, content_hash FROM blocks WHERE page_uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksHashByPageUuid"
  }

  private inner class SelectBlocksByContentHashQuery<out T : Any>(
    public val content_hash: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE content_hash ${ if (content_hash == null) "IS" else "=" } ? ORDER BY created_at""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, content_hash)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksByContentHash"
  }

  private inner class SelectDuplicateBlockHashesQuery<out T : Any>(
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_141_612_549, """
    |SELECT content_hash, COUNT(*) AS cnt
    |FROM blocks
    |WHERE content_hash IS NOT NULL
    |GROUP BY content_hash
    |HAVING COUNT(*) > 1
    |ORDER BY cnt DESC
    |LIMIT ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectDuplicateBlockHashes"
  }

  private inner class SelectPageByUuidQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-493_662_914, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPageByUuid"
  }

  private inner class SelectPageByNameQuery<out T : Any>(
    public val name: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-493_890_546, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE name = ? LIMIT 1""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, name)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPageByName"
  }

  private inner class ExistsPageByUuidQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_957_014_370, """SELECT COUNT(*) FROM pages WHERE uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:existsPageByUuid"
  }

  private inner class ExistsPageByNameQuery<out T : Any>(
    public val name: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_957_242_002, """SELECT COUNT(*) FROM pages WHERE name = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, name)
    }

    override fun toString(): String = "SteleDatabase.sq:existsPageByName"
  }

  private inner class SelectAllPagesPaginatedQuery<out T : Any>(
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_018_316_883, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages ORDER BY name LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAllPagesPaginated"
  }

  private inner class SelectUnloadedPagesPaginatedQuery<out T : Any>(
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_551_624_324, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_content_loaded = 0 ORDER BY uuid LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectUnloadedPagesPaginated"
  }

  private inner class SelectUnloadedPagesBySectionQuery<out T : Any>(
    public val section_id: Collection<String>,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val section_idIndexes = createArguments(count = section_id.size)
      return driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_content_loaded = 0 AND section_id IN $section_idIndexes ORDER BY uuid LIMIT ? OFFSET ?""", mapper, section_id.size + 2) {
            var parameterIndex = 0
            section_id.forEach { section_id_ ->
              bindString(parameterIndex++, section_id_)
            }
            bindLong(parameterIndex++, value)
            bindLong(parameterIndex++, value_)
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectUnloadedPagesBySection"
  }

  private inner class SelectPagesByNamesQuery<out T : Any>(
    public val name: Collection<String>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val nameIndexes = createArguments(count = name.size)
      return driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE name IN $nameIndexes""", mapper, name.size) {
            var parameterIndex = 0
            name.forEach { name_ ->
              bindString(parameterIndex++, name_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectPagesByNames"
  }

  private inner class SelectJournalPagesByDatesQuery<out T : Any>(
    public val journal_date: Collection<String?>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val journal_dateIndexes = createArguments(count = journal_date.size)
      return driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_journal = 1 AND journal_date IN $journal_dateIndexes""", mapper, journal_date.size) {
            var parameterIndex = 0
            journal_date.forEach { journal_date_ ->
              bindString(parameterIndex++, journal_date_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectJournalPagesByDates"
  }

  private inner class SelectPagesByNamespaceQuery<out T : Any>(
    public val namespace: String?,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE namespace ${ if (namespace == null) "IS" else "=" } ? ORDER BY name LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, namespace)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPagesByNamespace"
  }

  private inner class SelectPagesByNamespaceUnpaginatedQuery<out T : Any>(
    public val namespace: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE namespace ${ if (namespace == null) "IS" else "=" } ? ORDER BY name""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, namespace)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPagesByNamespaceUnpaginated"
  }

  private inner class CountPagesByNamespaceQuery<out T : Any>(
    public val namespace: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT COUNT(*) FROM pages WHERE namespace ${ if (namespace == null) "IS" else "=" } ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, namespace)
    }

    override fun toString(): String = "SteleDatabase.sq:countPagesByNamespace"
  }

  private inner class SelectRecentlyUpdatedPagesQuery<out T : Any>(
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(2_012_172_782, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages ORDER BY updated_at DESC LIMIT ?""", mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectRecentlyUpdatedPages"
  }

  private inner class SelectRecentlyCreatedPagesQuery<out T : Any>(
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(193_234_913, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages ORDER BY created_at DESC LIMIT ?""", mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectRecentlyCreatedPages"
  }

  private inner class SelectJournalPagesQuery<out T : Any>(
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_864_064_170, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_journal = 1 AND journal_date IS NOT NULL ORDER BY journal_date DESC LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectJournalPages"
  }

  private inner class SelectJournalPageByDateQuery<out T : Any>(
    public val journal_date: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_journal = 1 AND journal_date ${ if (journal_date == null) "IS" else "=" } ? LIMIT 1""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, journal_date)
    }

    override fun toString(): String = "SteleDatabase.sq:selectJournalPageByDate"
  }

  private inner class SelectJournalPageByDateAndSectionQuery<out T : Any>(
    public val journal_date: String?,
    public val section_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE is_journal = 1 AND journal_date ${ if (journal_date == null) "IS" else "=" } ? AND section_id = ? LIMIT 1""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, journal_date)
      bindString(parameterIndex++, section_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectJournalPageByDateAndSection"
  }

  private inner class SelectPagesBySectionIdQuery<out T : Any>(
    public val section_id: String,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_803_138_750, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE section_id = ? ORDER BY name ASC LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, section_id)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPagesBySectionId"
  }

  private inner class SelectNeighbourPageUuidsQuery<out T : Any>(
    public val pageUuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "block_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "block_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-2_037_987_511, """
    |SELECT DISTINCT to_b.page_uuid AS page_uuid
    |FROM block_references br
    |JOIN blocks from_b ON from_b.uuid = br.from_block_uuid
    |JOIN blocks to_b   ON to_b.uuid   = br.to_block_uuid
    |WHERE from_b.page_uuid = ? AND to_b.page_uuid != ?
    |UNION
    |SELECT DISTINCT from_b.page_uuid AS page_uuid
    |FROM block_references br
    |JOIN blocks from_b ON from_b.uuid = br.from_block_uuid
    |JOIN blocks to_b   ON to_b.uuid   = br.to_block_uuid
    |WHERE to_b.page_uuid = ? AND from_b.page_uuid != ?
    """.trimMargin(), mapper, 4) {
      var parameterIndex = 0
      bindString(parameterIndex++, pageUuid)
      bindString(parameterIndex++, pageUuid)
      bindString(parameterIndex++, pageUuid)
      bindString(parameterIndex++, pageUuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectNeighbourPageUuids"
  }

  private inner class SelectOutgoingReferencesQuery<out T : Any>(
    public val from_block_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "block_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "block_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-494_736_591, """
    |SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version, b.content_hash, b.block_type FROM blocks b
    |INNER JOIN block_references br ON b.uuid = br.to_block_uuid
    |WHERE br.from_block_uuid = ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, from_block_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectOutgoingReferences"
  }

  private inner class SelectIncomingReferencesQuery<out T : Any>(
    public val to_block_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "block_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "block_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(585_273_195, """
    |SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version, b.content_hash, b.block_type FROM blocks b
    |INNER JOIN block_references br ON b.uuid = br.from_block_uuid
    |WHERE br.to_block_uuid = ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, to_block_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectIncomingReferences"
  }

  private inner class SelectMostConnectedBlocksQuery<out T : Any>(
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "block_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "block_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_980_914_833, """
    |SELECT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version, b.content_hash, b.block_type, COUNT(br.id) AS reference_count
    |FROM blocks b
    |LEFT JOIN block_references br ON b.uuid = br.to_block_uuid OR b.uuid = br.from_block_uuid
    |GROUP BY b.uuid
    |ORDER BY reference_count DESC
    |LIMIT ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectMostConnectedBlocks"
  }

  private inner class SelectPagesByNameLikeQuery<out T : Any>(
    public val name: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(141_501_984, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE name LIKE ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, name)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPagesByNameLike"
  }

  private inner class SelectPagesByNameLikePaginatedQuery<out T : Any>(
    public val name: String,
    public val `value`: Long,
    public val value_: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(673_779_853, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count, pages.section_id FROM pages WHERE name LIKE ? ORDER BY name LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, name)
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPagesByNameLikePaginated"
  }

  private inner class SelectBlocksReferencingQuery<out T : Any>(
    public val to_block_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "block_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "block_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-974_356_443, """
    |SELECT DISTINCT b.id, b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level, b.position, b.created_at, b.updated_at, b.properties, b.version, b.content_hash, b.block_type FROM blocks b
    |INNER JOIN block_references br ON b.uuid = br.from_block_uuid
    |WHERE br.to_block_uuid = ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, to_block_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksReferencing"
  }

  private inner class SelectPluginDataByIdQuery<out T : Any>(
    public val id: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_505_356_172, """SELECT plugin_data.id, plugin_data.plugin_id, plugin_data.entity_type, plugin_data.entity_uuid, plugin_data.key, plugin_data.value, plugin_data.created_at, plugin_data.updated_at FROM plugin_data WHERE id = ?""", mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPluginDataById"
  }

  private inner class SelectPluginDataByPluginQuery<out T : Any>(
    public val plugin_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_125_405_916, """SELECT plugin_data.id, plugin_data.plugin_id, plugin_data.entity_type, plugin_data.entity_uuid, plugin_data.key, plugin_data.value, plugin_data.created_at, plugin_data.updated_at FROM plugin_data WHERE plugin_id = ? ORDER BY created_at""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, plugin_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPluginDataByPlugin"
  }

  private inner class SelectPluginDataByEntityQuery<out T : Any>(
    public val entity_type: String,
    public val entity_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_438_507_052, """SELECT plugin_data.id, plugin_data.plugin_id, plugin_data.entity_type, plugin_data.entity_uuid, plugin_data.key, plugin_data.value, plugin_data.created_at, plugin_data.updated_at FROM plugin_data WHERE entity_type = ? AND entity_uuid = ? ORDER BY key""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, entity_type)
      bindString(parameterIndex++, entity_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPluginDataByEntity"
  }

  private inner class SelectPluginDataByKeyQuery<out T : Any>(
    public val plugin_id: String,
    public val key: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-578_596_850, """SELECT plugin_data.id, plugin_data.plugin_id, plugin_data.entity_type, plugin_data.entity_uuid, plugin_data.key, plugin_data.value, plugin_data.created_at, plugin_data.updated_at FROM plugin_data WHERE plugin_id = ? AND key = ? ORDER BY entity_type, entity_uuid""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, plugin_id)
      bindString(parameterIndex++, key)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPluginDataByKey"
  }

  private inner class SelectPluginDataByPluginAndEntityQuery<out T : Any>(
    public val plugin_id: String,
    public val entity_type: String,
    public val entity_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_919_683_030, """SELECT plugin_data.id, plugin_data.plugin_id, plugin_data.entity_type, plugin_data.entity_uuid, plugin_data.key, plugin_data.value, plugin_data.created_at, plugin_data.updated_at FROM plugin_data WHERE plugin_id = ? AND entity_type = ? AND entity_uuid = ? ORDER BY key""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, plugin_id)
      bindString(parameterIndex++, entity_type)
      bindString(parameterIndex++, entity_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPluginDataByPluginAndEntity"
  }

  private inner class CountPluginDataByPluginQuery<out T : Any>(
    public val plugin_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_159_923_797, """SELECT COUNT(*) FROM plugin_data WHERE plugin_id = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, plugin_id)
    }

    override fun toString(): String = "SteleDatabase.sq:countPluginDataByPlugin"
  }

  private inner class CountPluginDataByEntityQuery<out T : Any>(
    public val entity_type: String,
    public val entity_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(846_822_661, """SELECT COUNT(*) FROM plugin_data WHERE entity_type = ? AND entity_uuid = ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, entity_type)
      bindString(parameterIndex++, entity_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:countPluginDataByEntity"
  }

  private inner class ExistsPluginDataQuery<out T : Any>(
    public val plugin_id: String,
    public val entity_type: String,
    public val entity_uuid: String,
    public val key: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("plugin_data", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("plugin_data", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_146_224_122, """SELECT COUNT(*) FROM plugin_data WHERE plugin_id = ? AND entity_type = ? AND entity_uuid = ? AND key = ?""", mapper, 4) {
      var parameterIndex = 0
      bindString(parameterIndex++, plugin_id)
      bindString(parameterIndex++, entity_type)
      bindString(parameterIndex++, entity_uuid)
      bindString(parameterIndex++, key)
    }

    override fun toString(): String = "SteleDatabase.sq:existsPluginData"
  }

  private inner class SearchBlocksByContentFtsQuery<out T : Any>(
    public val query: String,
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "blocks_fts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "blocks_fts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(642_676_818, """
    |SELECT
    |    b.uuid,
    |    b.page_uuid,
    |    b.parent_uuid,
    |    b.left_uuid,
    |    b.content,
    |    b.level,
    |    b.position,
    |    b.created_at,
    |    b.updated_at,
    |    b.properties,
    |    b.version,
    |    highlight(blocks_fts, 0, '<em>', '</em>') AS highlight,
    |    bm25(blocks_fts) AS bm25_score
    |FROM blocks_fts bm
    |JOIN blocks b ON b.id = bm.rowid
    |WHERE blocks_fts MATCH ?
    |ORDER BY bm25(blocks_fts)
    |LIMIT ? OFFSET ?
    """.trimMargin(), mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:searchBlocksByContentFts"
  }

  private inner class SearchBlocksByContentFtsInPageQuery<out T : Any>(
    public val query: String,
    public val pageUuid: String,
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "blocks_fts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "blocks_fts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(337_193_030, """
    |SELECT
    |    b.uuid,
    |    b.page_uuid,
    |    b.parent_uuid,
    |    b.left_uuid,
    |    b.content,
    |    b.level,
    |    b.position,
    |    b.created_at,
    |    b.updated_at,
    |    b.properties,
    |    b.version,
    |    highlight(blocks_fts, 0, '<em>', '</em>') AS highlight,
    |    bm25(blocks_fts) AS bm25_score
    |FROM blocks_fts bm
    |JOIN blocks b ON b.id = bm.rowid
    |WHERE blocks_fts MATCH ?
    |AND b.page_uuid = ?
    |ORDER BY bm25(blocks_fts)
    |LIMIT ? OFFSET ?
    """.trimMargin(), mapper, 4) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
      bindString(parameterIndex++, pageUuid)
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:searchBlocksByContentFtsInPage"
  }

  private inner class SearchBlocksCountFtsQuery<out T : Any>(
    public val query: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks_fts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks_fts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(990_901_189, """
    |SELECT COUNT(*) AS result_count
    |FROM blocks_fts bm
    |WHERE blocks_fts MATCH ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
    }

    override fun toString(): String = "SteleDatabase.sq:searchBlocksCountFts"
  }

  private inner class SearchPagesByNameFtsQuery<out T : Any>(
    public val query: String,
    public val limit: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", "pages_fts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", "pages_fts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(2_023_372_136, """
    |SELECT
    |    p.uuid,
    |    p.name,
    |    p.namespace,
    |    p.file_path,
    |    p.created_at,
    |    p.updated_at,
    |    p.properties,
    |    p.version,
    |    p.is_favorite,
    |    p.is_journal,
    |    p.journal_date,
    |    p.is_content_loaded,
    |    highlight(pages_fts, 0, '<em>', '</em>') AS highlight,
    |    bm25(pages_fts) AS bm25_score
    |FROM pages_fts pf
    |JOIN pages p ON p.rowid = pf.rowid
    |WHERE pages_fts MATCH ?
    |ORDER BY bm25(pages_fts)
    |LIMIT ?
    """.trimMargin(), mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
      bindLong(parameterIndex++, limit)
    }

    override fun toString(): String = "SteleDatabase.sq:searchPagesByNameFts"
  }

  private inner class SearchPagesByNameFtsInDateRangeQuery<out T : Any>(
    public val query: String,
    public val startMs: Long,
    public val endMs: Long,
    public val limit: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", "pages_fts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", "pages_fts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_087_339_518, """
    |SELECT p.uuid, p.name, p.namespace, p.file_path, p.created_at, p.updated_at,
    |       p.properties, p.version, p.is_favorite, p.is_journal, p.journal_date,
    |       p.is_content_loaded,
    |       highlight(pages_fts, 0, '<em>', '</em>') AS highlight,
    |       bm25(pages_fts) AS bm25_score
    |FROM pages_fts pf
    |JOIN pages p ON p.rowid = pf.rowid
    |WHERE pages_fts MATCH ?
    |AND p.updated_at >= ? AND p.updated_at <= ?
    |ORDER BY bm25(pages_fts)
    |LIMIT ?
    """.trimMargin(), mapper, 4) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
      bindLong(parameterIndex++, startMs)
      bindLong(parameterIndex++, endMs)
      bindLong(parameterIndex++, limit)
    }

    override fun toString(): String = "SteleDatabase.sq:searchPagesByNameFtsInDateRange"
  }

  private inner class SearchBlocksByContentFtsInDateRangeQuery<out T : Any>(
    public val query: String,
    public val startMs: Long,
    public val endMs: Long,
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", "blocks_fts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", "blocks_fts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(234_891_992, """
    |SELECT b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content, b.level,
    |       b.position, b.created_at, b.updated_at, b.properties, b.version,
    |       highlight(blocks_fts, 0, '<em>', '</em>') AS highlight,
    |       bm25(blocks_fts) AS bm25_score
    |FROM blocks_fts bm
    |JOIN blocks b ON b.id = bm.rowid
    |WHERE blocks_fts MATCH ?
    |AND b.updated_at >= ? AND b.updated_at <= ?
    |ORDER BY bm25(blocks_fts)
    |LIMIT ? OFFSET ?
    """.trimMargin(), mapper, 5) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
      bindLong(parameterIndex++, startMs)
      bindLong(parameterIndex++, endMs)
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:searchBlocksByContentFtsInDateRange"
  }

  private inner class SelectPageVisitsByUuidsQuery<out T : Any>(
    public val page_uuid: Collection<String>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("page_visits", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("page_visits", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val page_uuidIndexes = createArguments(count = page_uuid.size)
      return driver.executeQuery(null, """
          |SELECT page_uuid, last_visited_at, visit_count
          |FROM page_visits
          |WHERE page_uuid IN $page_uuidIndexes
          """.trimMargin(), mapper, page_uuid.size) {
            var parameterIndex = 0
            page_uuid.forEach { page_uuid_ ->
              bindString(parameterIndex++, page_uuid_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectPageVisitsByUuids"
  }

  private inner class SelectPageVisitByUuidQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("page_visits", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("page_visits", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-2_119_147_311, """
    |SELECT page_uuid, last_visited_at, visit_count
    |FROM page_visits
    |WHERE page_uuid = ?
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPageVisitByUuid"
  }

  private inner class CountBlocksWithWikilinkQuery<out T : Any>(
    public val pageName: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-322_444_156, """SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[' || ? || ']]%'""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, pageName)
    }

    override fun toString(): String = "SteleDatabase.sq:countBlocksWithWikilink"
  }

  private inner class SelectBlocksWithWikilinkQuery<out T : Any>(
    public val pageName: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_687_193_427, """SELECT blocks.id, blocks.uuid, blocks.page_uuid, blocks.parent_uuid, blocks.left_uuid, blocks.content, blocks.level, blocks.position, blocks.created_at, blocks.updated_at, blocks.properties, blocks.version, blocks.content_hash, blocks.block_type FROM blocks WHERE content LIKE '%[[' || ? || ']]%' ORDER BY page_uuid, position""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, pageName)
    }

    override fun toString(): String = "SteleDatabase.sq:selectBlocksWithWikilink"
  }

  private inner class CountLinkedReferencesForPageQuery<out T : Any>(
    public val pageName: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_158_809_001, """SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[' || ? || ']]%'""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, pageName)
    }

    override fun toString(): String = "SteleDatabase.sq:countLinkedReferencesForPage"
  }

  private inner class SelectBacklinkCountsForPagesQuery<out T : Any>(
    public val uuid: Collection<String>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val uuidIndexes = createArguments(count = uuid.size)
      return driver.executeQuery(null, """
          |SELECT name AS page_name, backlink_count
          |FROM pages
          |WHERE uuid IN $uuidIndexes
          """.trimMargin(), mapper, uuid.size) {
            var parameterIndex = 0
            uuid.forEach { uuid_ ->
              bindString(parameterIndex++, uuid_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectBacklinkCountsForPages"
  }

  private inner class SelectPageBacklinkCountQuery<out T : Any>(
    public val name: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("pages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("pages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_616_094_686, """SELECT backlink_count FROM pages WHERE name = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, name)
    }

    override fun toString(): String = "SteleDatabase.sq:selectPageBacklinkCount"
  }

  private inner class SelectWikilinkPageNamesForBlockQuery<out T : Any>(
    public val block_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("wikilink_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("wikilink_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_490_472_214, """SELECT page_name FROM wikilink_references WHERE block_uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, block_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectWikilinkPageNamesForBlock"
  }

  private inner class SelectWikilinkPageNamesForBlocksQuery<out T : Any>(
    public val block_uuid: Collection<String>,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("wikilink_references", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("wikilink_references", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
      val block_uuidIndexes = createArguments(count = block_uuid.size)
      return driver.executeQuery(null, """SELECT DISTINCT page_name FROM wikilink_references WHERE block_uuid IN $block_uuidIndexes""", mapper, block_uuid.size) {
            var parameterIndex = 0
            block_uuid.forEach { block_uuid_ ->
              bindString(parameterIndex++, block_uuid_)
            }
          }
    }

    override fun toString(): String = "SteleDatabase.sq:selectWikilinkPageNamesForBlocks"
  }

  private inner class SelectWikilinkPageNamesForPageQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("wikilink_references", "blocks", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("wikilink_references", "blocks", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-367_155_994, """
    |SELECT DISTINCT page_name FROM wikilink_references
    |WHERE block_uuid IN (SELECT uuid FROM blocks WHERE page_uuid = ?)
    """.trimMargin(), mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectWikilinkPageNamesForPage"
  }

  private inner class SelectMetadataQuery<out T : Any>(
    public val key: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("metadata", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("metadata", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_885_990_004, """SELECT value FROM metadata WHERE key = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, key)
    }

    override fun toString(): String = "SteleDatabase.sq:selectMetadata"
  }

  private inner class SelectOperationsBySessionDescQuery<out T : Any>(
    public val session_id: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("operations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("operations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_678_222_055, """SELECT operations.op_id, operations.session_id, operations.seq, operations.op_type, operations.entity_uuid, operations.page_uuid, operations.payload, operations.created_at FROM operations WHERE session_id = ? ORDER BY seq DESC LIMIT ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, session_id)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectOperationsBySessionDesc"
  }

  private inner class SelectOperationsByPageUuidQuery<out T : Any>(
    public val page_uuid: String?,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("operations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("operations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT operations.op_id, operations.session_id, operations.seq, operations.op_type, operations.entity_uuid, operations.page_uuid, operations.payload, operations.created_at FROM operations WHERE page_uuid ${ if (page_uuid == null) "IS" else "=" } ? ORDER BY seq ASC""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectOperationsByPageUuid"
  }

  private inner class SelectOperationsSinceQuery<out T : Any>(
    public val session_id: String,
    public val seq: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("operations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("operations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-2_003_635_279, """SELECT operations.op_id, operations.session_id, operations.seq, operations.op_type, operations.entity_uuid, operations.page_uuid, operations.payload, operations.created_at FROM operations WHERE session_id = ? AND seq > ? ORDER BY seq ASC""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, session_id)
      bindLong(parameterIndex++, seq)
    }

    override fun toString(): String = "SteleDatabase.sq:selectOperationsSince"
  }

  private inner class CountOperationPayloadSizeQuery<out T : Any>(
    public val session_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("operations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("operations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(554_681_494, """SELECT SUM(LENGTH(payload)) FROM operations WHERE session_id = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, session_id)
    }

    override fun toString(): String = "SteleDatabase.sq:countOperationPayloadSize"
  }

  private inner class SelectLogicalClockQuery<out T : Any>(
    public val session_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("logical_clock", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("logical_clock", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_957_924_706, """SELECT seq FROM logical_clock WHERE session_id = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, session_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectLogicalClock"
  }

  private inner class SelectAppliedMigrationsQuery<out T : Any>(
    public val graph_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("migration_changelog", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("migration_changelog", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(347_740_933, """SELECT id, checksum, status FROM migration_changelog WHERE graph_id = ? AND status = 'APPLIED' ORDER BY execution_order""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, graph_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAppliedMigrations"
  }

  private inner class SelectMigrationByIdQuery<out T : Any>(
    public val id: String,
    public val graph_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("migration_changelog", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("migration_changelog", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_304_131_965, """SELECT migration_changelog.id, migration_changelog.graph_id, migration_changelog.description, migration_changelog.checksum, migration_changelog.applied_at, migration_changelog.execution_ms, migration_changelog.status, migration_changelog.applied_by, migration_changelog.execution_order, migration_changelog.changes_applied, migration_changelog.error_message FROM migration_changelog WHERE id = ? AND graph_id = ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, id)
      bindString(parameterIndex++, graph_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectMigrationById"
  }

  private inner class SelectRunningMigrationsQuery<out T : Any>(
    public val graph_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("migration_changelog", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("migration_changelog", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-338_897_465, """SELECT migration_changelog.id, migration_changelog.graph_id, migration_changelog.description, migration_changelog.checksum, migration_changelog.applied_at, migration_changelog.execution_ms, migration_changelog.status, migration_changelog.applied_by, migration_changelog.execution_order, migration_changelog.changes_applied, migration_changelog.error_message FROM migration_changelog WHERE graph_id = ? AND status = 'RUNNING'""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, graph_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectRunningMigrations"
  }

  private inner class SelectAllMigrationsForGraphQuery<out T : Any>(
    public val graph_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("migration_changelog", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("migration_changelog", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-2_001_246_770, """SELECT migration_changelog.id, migration_changelog.graph_id, migration_changelog.description, migration_changelog.checksum, migration_changelog.applied_at, migration_changelog.execution_ms, migration_changelog.status, migration_changelog.applied_by, migration_changelog.execution_order, migration_changelog.changes_applied, migration_changelog.error_message FROM migration_changelog WHERE graph_id = ? ORDER BY execution_order""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, graph_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAllMigrationsForGraph"
  }

  private inner class SelectGitConfigQuery<out T : Any>(
    public val graph_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("git_config", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("git_config", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_451_609_335, """SELECT git_config.graph_id, git_config.repo_root, git_config.wiki_subdir, git_config.remote_name, git_config.remote_branch, git_config.auth_type, git_config.ssh_key_path, git_config.ssh_key_passphrase_key, git_config.https_token_key, git_config.oauth_token_key, git_config.poll_interval_minutes, git_config.auto_commit, git_config.commit_message_template FROM git_config WHERE graph_id = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, graph_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectGitConfig"
  }

  private inner class SelectImageAnnotationByUuidQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("image_annotations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("image_annotations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-575_052_033, """SELECT image_annotations.uuid, image_annotations.block_uuid, image_annotations.page_uuid, image_annotations.graph_path, image_annotations.file_path, image_annotations.thumbnail_path, image_annotations.source, image_annotations.source_uri, image_annotations.captured_at_ms, image_annotations.imported_at_ms, image_annotations.calibration_method, image_annotations.pixels_per_meter, image_annotations.calibration_confidence_pct, image_annotations.unit, image_annotations.tags, image_annotations.lat_lng, image_annotations.altitude_m, image_annotations.bearing_deg, image_annotations.pitch_deg, image_annotations.roll_deg, image_annotations.focal_length_mm, image_annotations.focal_length_35mm_eq, image_annotations.camera_make, image_annotations.camera_model FROM image_annotations WHERE uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectImageAnnotationByUuid"
  }

  private inner class SelectImageAnnotationsByPageQuery<out T : Any>(
    public val page_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("image_annotations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("image_annotations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_651_202_740, """SELECT image_annotations.uuid, image_annotations.block_uuid, image_annotations.page_uuid, image_annotations.graph_path, image_annotations.file_path, image_annotations.thumbnail_path, image_annotations.source, image_annotations.source_uri, image_annotations.captured_at_ms, image_annotations.imported_at_ms, image_annotations.calibration_method, image_annotations.pixels_per_meter, image_annotations.calibration_confidence_pct, image_annotations.unit, image_annotations.tags, image_annotations.lat_lng, image_annotations.altitude_m, image_annotations.bearing_deg, image_annotations.pitch_deg, image_annotations.roll_deg, image_annotations.focal_length_mm, image_annotations.focal_length_35mm_eq, image_annotations.camera_make, image_annotations.camera_model FROM image_annotations WHERE page_uuid = ? ORDER BY imported_at_ms DESC""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, page_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectImageAnnotationsByPage"
  }

  private inner class SelectImageAnnotationsByTagQuery<out T : Any>(
    public val `value`: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("image_annotations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("image_annotations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(778_023_229, """SELECT image_annotations.uuid, image_annotations.block_uuid, image_annotations.page_uuid, image_annotations.graph_path, image_annotations.file_path, image_annotations.thumbnail_path, image_annotations.source, image_annotations.source_uri, image_annotations.captured_at_ms, image_annotations.imported_at_ms, image_annotations.calibration_method, image_annotations.pixels_per_meter, image_annotations.calibration_confidence_pct, image_annotations.unit, image_annotations.tags, image_annotations.lat_lng, image_annotations.altitude_m, image_annotations.bearing_deg, image_annotations.pitch_deg, image_annotations.roll_deg, image_annotations.focal_length_mm, image_annotations.focal_length_35mm_eq, image_annotations.camera_make, image_annotations.camera_model FROM image_annotations WHERE tags LIKE '%"' || ? || '"%' ORDER BY imported_at_ms DESC""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectImageAnnotationsByTag"
  }

  private inner class SelectMeasurementsForImageQuery<out T : Any>(
    public val image_uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("measurement_annotations", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("measurement_annotations", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_858_375_750, """SELECT measurement_annotations.uuid, measurement_annotations.image_uuid, measurement_annotations.annotation_type, measurement_annotations.normalized_points, measurement_annotations.value_meters, measurement_annotations.value_display, measurement_annotations.label, measurement_annotations.color_hex, measurement_annotations.ble_device_id FROM measurement_annotations WHERE image_uuid = ? ORDER BY rowid""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, image_uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectMeasurementsForImage"
  }

  private inner class SelectAssetByUuidQuery<out T : Any>(
    public val uuid: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("asset_index", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("asset_index", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-807_861_691, """SELECT asset_index.uuid, asset_index.file_path, asset_index.relative_path, asset_index.media_type, asset_index.subfolder, asset_index.tags, asset_index.auto_labels, asset_index.ocr_text, asset_index.cloud_description, asset_index.page_uuids, asset_index.size_bytes, asset_index.imported_at_ms, asset_index.ml_processed, asset_index.ml_attempted_at, asset_index.ml_failed, asset_index.content_hash, asset_index.is_orphan, asset_index.ml_tags_source FROM asset_index WHERE uuid = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, uuid)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAssetByUuid"
  }

  private inner class SelectAssetsQuery<out T : Any>(
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("asset_index", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("asset_index", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_047_691_488, """SELECT asset_index.uuid, asset_index.file_path, asset_index.relative_path, asset_index.media_type, asset_index.subfolder, asset_index.tags, asset_index.auto_labels, asset_index.ocr_text, asset_index.cloud_description, asset_index.page_uuids, asset_index.size_bytes, asset_index.imported_at_ms, asset_index.ml_processed, asset_index.ml_attempted_at, asset_index.ml_failed, asset_index.content_hash, asset_index.is_orphan, asset_index.ml_tags_source FROM asset_index ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAssets"
  }

  private inner class SelectAssetsByMediaTypeQuery<out T : Any>(
    public val media_type: String,
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("asset_index", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("asset_index", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_673_176_217, """SELECT asset_index.uuid, asset_index.file_path, asset_index.relative_path, asset_index.media_type, asset_index.subfolder, asset_index.tags, asset_index.auto_labels, asset_index.ocr_text, asset_index.cloud_description, asset_index.page_uuids, asset_index.size_bytes, asset_index.imported_at_ms, asset_index.ml_processed, asset_index.ml_attempted_at, asset_index.ml_failed, asset_index.content_hash, asset_index.is_orphan, asset_index.ml_tags_source FROM asset_index WHERE media_type = ? ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?""", mapper, 3) {
      var parameterIndex = 0
      bindString(parameterIndex++, media_type)
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAssetsByMediaType"
  }

  private inner class SearchAssetsQuery<out T : Any>(
    public val query: String,
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("asset_index", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("asset_index", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_326_743_244, """SELECT asset_index.uuid, asset_index.file_path, asset_index.relative_path, asset_index.media_type, asset_index.subfolder, asset_index.tags, asset_index.auto_labels, asset_index.ocr_text, asset_index.cloud_description, asset_index.page_uuids, asset_index.size_bytes, asset_index.imported_at_ms, asset_index.ml_processed, asset_index.ml_attempted_at, asset_index.ml_failed, asset_index.content_hash, asset_index.is_orphan, asset_index.ml_tags_source FROM asset_index WHERE (file_path LIKE ? ESCAPE '\' OR tags LIKE ? ESCAPE '\' OR auto_labels LIKE ? ESCAPE '\' OR ocr_text LIKE ? ESCAPE '\') ORDER BY imported_at_ms DESC LIMIT ? OFFSET ?""", mapper, 6) {
      var parameterIndex = 0
      bindString(parameterIndex++, query)
      bindString(parameterIndex++, query)
      bindString(parameterIndex++, query)
      bindString(parameterIndex++, query)
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:searchAssets"
  }

  private inner class SelectUnprocessedAssetsQuery<out T : Any>(
    public val limit: Long,
    public val offset: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("asset_index", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("asset_index", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(691_455_227, """SELECT asset_index.uuid, asset_index.file_path, asset_index.relative_path, asset_index.media_type, asset_index.subfolder, asset_index.tags, asset_index.auto_labels, asset_index.ocr_text, asset_index.cloud_description, asset_index.page_uuids, asset_index.size_bytes, asset_index.imported_at_ms, asset_index.ml_processed, asset_index.ml_attempted_at, asset_index.ml_failed, asset_index.content_hash, asset_index.is_orphan, asset_index.ml_tags_source FROM asset_index WHERE ml_processed = 0 AND ml_failed = 0 ORDER BY imported_at_ms ASC LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, limit)
      bindLong(parameterIndex++, offset)
    }

    override fun toString(): String = "SteleDatabase.sq:selectUnprocessedAssets"
  }
}
