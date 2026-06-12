package dev.stapler.stelekit.db

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
    position: Long,
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
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlockByUuid(uuid: String): Query<Blocks> = selectBlockByUuid(uuid, ::Blocks)

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
    position: Long,
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
      cursor.getLong(7)!!,
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
      position: Long,
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
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectAllBlocksPaginated(value_: Long, value__: Long): Query<Blocks> = selectAllBlocksPaginated(value_, value__, ::Blocks)

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
      position: Long,
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
      cursor.getLong(7)!!,
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
      position: Long,
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
      cursor.getLong(7)!!,
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
      position: Long,
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
      cursor.getLong(7)!!,
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
      position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
      position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getString(10),
      cursor.getLong(11)!!,
      cursor.getString(12),
      cursor.getString(13)!!
    )
  }

  public fun selectBlocksByParentUuids(parent_uuid: Collection<String?>): Query<Blocks> = selectBlocksByParentUuids(parent_uuid, ::Blocks)

  public fun <T : Any> selectRootBlocksByPageUuidOrdered(page_uuid: String, mapper: (
    id: Long,
    uuid: String,
    page_uuid: String,
    parent_uuid: String?,
    left_uuid: String?,
    content: String,
    level: Long,
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
    )
  }

  public fun selectPageByName(name: String): Query<Pages> = selectPageByName(name, ::Pages)

  public fun existsPageByUuid(uuid: String): Query<Long> = ExistsPageByUuidQuery(uuid) { cursor ->
    cursor.getLong(0)!!
  }

  public fun existsPageByName(name: String): Query<Long> = ExistsPageByNameQuery(name) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAllPages(mapper: (
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
  ) -> T): Query<T> = Query(347_863_808, arrayOf("pages"), driver, "SteleDatabase.sq", "selectAllPages", "SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages ORDER BY name") { cursor ->
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
      cursor.getLong(12)!!
    )
  }

  public fun selectAllPages(): Query<Pages> = selectAllPages(::Pages)

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
      cursor.getLong(12)!!
    )
  }

  public fun selectAllPagesPaginated(value_: Long, value__: Long): Query<Pages> = selectAllPagesPaginated(value_, value__, ::Pages)

  public fun <T : Any> selectUnloadedPages(mapper: (
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
  ) -> T): Query<T> = Query(-228_185_015, arrayOf("pages"), driver, "SteleDatabase.sq", "selectUnloadedPages", "SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE is_content_loaded = 0") { cursor ->
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
      cursor.getLong(12)!!
    )
  }

  public fun selectUnloadedPages(): Query<Pages> = selectUnloadedPages(::Pages)

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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
    )
  }

  public fun selectJournalPageByDate(journal_date: String?): Query<Pages> = selectJournalPageByDate(journal_date, ::Pages)

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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(7)!!,
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
      cursor.getLong(12)!!
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
      cursor.getLong(12)!!
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
    position: Long,
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
      cursor.getLong(7)!!,
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
      position: Long,
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
      cursor.getLong(6)!!,
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
      position: Long,
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
      cursor.getLong(6)!!,
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
      position: Long,
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
      cursor.getLong(6)!!,
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

  public fun <T : Any> selectHistogramForOperation(operation_name: String, mapper: (bucket_ms: Long, count: Long) -> T): Query<T> = SelectHistogramForOperationQuery(operation_name) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun selectHistogramForOperation(operation_name: String): Query<SelectHistogramForOperation> = selectHistogramForOperation(operation_name, ::SelectHistogramForOperation)

  public fun selectAllHistogramOperations(): Query<String> = Query(-11_535_700, arrayOf("perf_histogram_buckets"), driver, "SteleDatabase.sq", "selectAllHistogramOperations", "SELECT DISTINCT operation_name FROM perf_histogram_buckets ORDER BY operation_name") { cursor ->
    cursor.getString(0)!!
  }

  public fun selectDebugFlag(key: String): Query<Long> = SelectDebugFlagQuery(key) { cursor ->
    cursor.getLong(0)!!
  }

  public fun <T : Any> selectAllDebugFlags(mapper: (key: String, value_: Long) -> T): Query<T> = Query(213_515_832, arrayOf("debug_flags"), driver, "SteleDatabase.sq", "selectAllDebugFlags", "SELECT key, value FROM debug_flags ORDER BY key") { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getLong(1)!!
    )
  }

  public fun selectAllDebugFlags(): Query<SelectAllDebugFlags> = selectAllDebugFlags(::SelectAllDebugFlags)

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
    position: Long,
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
      cursor.getLong(7)!!,
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
    position: Long,
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
      cursor.getLong(2)!!,
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

  public fun <T : Any> selectRecentSpans(`value`: Long, mapper: (
    id: Long,
    trace_id: String,
    span_id: String,
    parent_span_id: String,
    name: String,
    start_epoch_ms: Long,
    end_epoch_ms: Long,
    duration_ms: Long,
    attributes_json: String,
    status_code: String,
  ) -> T): Query<T> = SelectRecentSpansQuery(value) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getString(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getString(8)!!,
      cursor.getString(9)!!
    )
  }

  public fun selectRecentSpans(value_: Long): Query<Spans> = selectRecentSpans(value_, ::Spans)

  public fun <T : Any> selectQueryStatsByVersion(app_version: String, mapper: (
    app_version: String,
    table_name: String,
    operation: String,
    calls: Long,
    errors: Long,
    total_ms: Long,
    min_ms: Long,
    max_ms: Long,
    b1: Long,
    b5: Long,
    b16: Long,
    b50: Long,
    b100: Long,
    b500: Long,
    b_inf: Long,
    first_seen: Long,
    last_seen: Long,
  ) -> T): Query<T> = SelectQueryStatsByVersionQuery(app_version) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13)!!,
      cursor.getLong(14)!!,
      cursor.getLong(15)!!,
      cursor.getLong(16)!!
    )
  }

  public fun selectQueryStatsByVersion(app_version: String): Query<Query_stats> = selectQueryStatsByVersion(app_version, ::Query_stats)

  public fun <T : Any> selectTopQueryStatsByTotalMs(
    app_version: String,
    `value`: Long,
    mapper: (
      app_version: String,
      table_name: String,
      operation: String,
      calls: Long,
      errors: Long,
      total_ms: Long,
      min_ms: Long,
      max_ms: Long,
      b1: Long,
      b5: Long,
      b16: Long,
      b50: Long,
      b100: Long,
      b500: Long,
      b_inf: Long,
      first_seen: Long,
      last_seen: Long,
    ) -> T,
  ): Query<T> = SelectTopQueryStatsByTotalMsQuery(app_version, value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13)!!,
      cursor.getLong(14)!!,
      cursor.getLong(15)!!,
      cursor.getLong(16)!!
    )
  }

  public fun selectTopQueryStatsByTotalMs(app_version: String, value_: Long): Query<Query_stats> = selectTopQueryStatsByTotalMs(app_version, value_, ::Query_stats)

  public fun <T : Any> selectTopQueryStatsByCalls(
    app_version: String,
    `value`: Long,
    mapper: (
      app_version: String,
      table_name: String,
      operation: String,
      calls: Long,
      errors: Long,
      total_ms: Long,
      min_ms: Long,
      max_ms: Long,
      b1: Long,
      b5: Long,
      b16: Long,
      b50: Long,
      b100: Long,
      b500: Long,
      b_inf: Long,
      first_seen: Long,
      last_seen: Long,
    ) -> T,
  ): Query<T> = SelectTopQueryStatsByCallsQuery(app_version, value) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getLong(3)!!,
      cursor.getLong(4)!!,
      cursor.getLong(5)!!,
      cursor.getLong(6)!!,
      cursor.getLong(7)!!,
      cursor.getLong(8)!!,
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getLong(11)!!,
      cursor.getLong(12)!!,
      cursor.getLong(13)!!,
      cursor.getLong(14)!!,
      cursor.getLong(15)!!,
      cursor.getLong(16)!!
    )
  }

  public fun selectTopQueryStatsByCalls(app_version: String, value_: Long): Query<Query_stats> = selectTopQueryStatsByCalls(app_version, value_, ::Query_stats)

  public fun selectAllQueryStatVersions(): Query<String> = Query(-639_506_637, arrayOf("query_stats"), driver, "SteleDatabase.sq", "selectAllQueryStatVersions", "SELECT DISTINCT app_version FROM query_stats ORDER BY app_version DESC") { cursor ->
    cursor.getString(0)!!
  }

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
      cursor.getLong(9)!!,
      cursor.getLong(10)!!,
      cursor.getString(11)!!
    )
  }

  public fun selectGitConfig(graph_id: String): Query<Git_config> = selectGitConfig(graph_id, ::Git_config)

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
    position: Long,
    level: Long,
    uuid: String,
  ): Long {
    val result = driver.execute(-1_913_429_373, """UPDATE blocks SET parent_uuid = ?, position = ?, level = ? WHERE uuid = ?""", 4) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindLong(parameterIndex++, position)
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
    position: Long,
    level: Long,
    uuid: String,
  ): Long {
    val result = driver.execute(759_870_418, """UPDATE blocks SET parent_uuid = ?, left_uuid = ?, position = ?, level = ? WHERE uuid = ?""", 5) {
          var parameterIndex = 0
          bindString(parameterIndex++, parent_uuid)
          bindString(parameterIndex++, left_uuid)
          bindLong(parameterIndex++, position)
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
  public suspend fun updateBlockPositionOnly(position: Long, uuid: String): Long {
    val result = driver.execute(1_997_868_472, """UPDATE blocks SET position = ? WHERE uuid = ?""", 2) {
          var parameterIndex = 0
          bindLong(parameterIndex++, position)
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
    position: Long,
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
          bindLong(parameterIndex++, position)
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
        |    is_content_loaded = ?
        |WHERE uuid = ?
        """.trimMargin(), 10) {
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
  ): Long {
    val result = driver.execute(-1_038_870_359, """
        |INSERT OR IGNORE INTO pages (uuid, name, namespace, file_path, created_at, updated_at, properties, version, is_favorite, is_journal, journal_date, is_content_loaded)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 12) {
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
  public suspend fun insertHistogramBucketIfAbsent(
    operation_name: String,
    bucket_ms: Long,
    recorded_at: Long,
  ): Long {
    val result = driver.execute(-212_282_134, """
        |INSERT OR IGNORE INTO perf_histogram_buckets (operation_name, bucket_ms, count, recorded_at)
        |VALUES (?, ?, 0, ?)
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, operation_name)
          bindLong(parameterIndex++, bucket_ms)
          bindLong(parameterIndex++, recorded_at)
        }.await()
    notifyQueries(-212_282_134) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun incrementHistogramBucketCount(
    recorded_at: Long,
    operation_name: String,
    bucket_ms: Long,
  ): Long {
    val result = driver.execute(-438_417_937, """
        |UPDATE perf_histogram_buckets
        |SET count = count + 1, recorded_at = ?
        |WHERE operation_name = ? AND bucket_ms = ?
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindLong(parameterIndex++, recorded_at)
          bindString(parameterIndex++, operation_name)
          bindLong(parameterIndex++, bucket_ms)
        }.await()
    notifyQueries(-438_417_937) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteOldHistogramRows(recorded_at: Long): Long {
    val result = driver.execute(-994_534_270, """DELETE FROM perf_histogram_buckets WHERE recorded_at < ?""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, recorded_at)
        }.await()
    notifyQueries(-994_534_270) { emit ->
      emit("perf_histogram_buckets")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun upsertDebugFlag(
    key: String,
    value_: Long,
    updated_at: Long,
  ): Long {
    val result = driver.execute(1_041_055_951, """
        |INSERT OR REPLACE INTO debug_flags (key, value, updated_at)
        |VALUES (?, ?, ?)
        """.trimMargin(), 3) {
          var parameterIndex = 0
          bindString(parameterIndex++, key)
          bindLong(parameterIndex++, value_)
          bindLong(parameterIndex++, updated_at)
        }.await()
    notifyQueries(1_041_055_951) { emit ->
      emit("debug_flags")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun recomputeAllBacklinkCounts(): Long {
    val result = driver.execute(-625_326_717, """
        |UPDATE pages SET backlink_count = (
        |    SELECT COUNT(*) FROM blocks
        |    WHERE blocks.content LIKE '%[[' || pages.name || ']]%'
        |)
        """.trimMargin(), 0).await()
    notifyQueries(-625_326_717) { emit ->
      emit("pages")
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
  public suspend fun insertSpan(
    trace_id: String,
    span_id: String,
    parent_span_id: String,
    name: String,
    start_epoch_ms: Long,
    end_epoch_ms: Long,
    duration_ms: Long,
    attributes_json: String,
    status_code: String,
  ): Long {
    val result = driver.execute(-1_038_766_748, """
        |INSERT INTO spans (trace_id, span_id, parent_span_id, name, start_epoch_ms, end_epoch_ms, duration_ms, attributes_json, status_code)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 9) {
          var parameterIndex = 0
          bindString(parameterIndex++, trace_id)
          bindString(parameterIndex++, span_id)
          bindString(parameterIndex++, parent_span_id)
          bindString(parameterIndex++, name)
          bindLong(parameterIndex++, start_epoch_ms)
          bindLong(parameterIndex++, end_epoch_ms)
          bindLong(parameterIndex++, duration_ms)
          bindString(parameterIndex++, attributes_json)
          bindString(parameterIndex++, status_code)
        }.await()
    notifyQueries(-1_038_766_748) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteSpansOlderThan(end_epoch_ms: Long): Long {
    val result = driver.execute(-1_376_925_320, """DELETE FROM spans WHERE end_epoch_ms < ?""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, end_epoch_ms)
        }.await()
    notifyQueries(-1_376_925_320) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteExcessSpans(`value`: Long): Long {
    val result = driver.execute(1_788_250_216, """DELETE FROM spans WHERE id NOT IN (SELECT id FROM spans ORDER BY start_epoch_ms DESC LIMIT ?)""", 1) {
          var parameterIndex = 0
          bindLong(parameterIndex++, value)
        }.await()
    notifyQueries(1_788_250_216) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteAllSpans(): Long {
    val result = driver.execute(1_640_771_412, """DELETE FROM spans""", 0).await()
    notifyQueries(1_640_771_412) { emit ->
      emit("spans")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun insertQueryStatIfAbsent(
    app_version: String,
    table_name: String,
    operation: String,
    first_seen: Long,
    last_seen: Long,
  ): Long {
    val result = driver.execute(1_909_304_888, """
        |INSERT OR IGNORE INTO query_stats (app_version, table_name, operation, calls, errors, total_ms, min_ms, max_ms, b1, b5, b16, b50, b100, b500, b_inf, first_seen, last_seen)
        |VALUES (?, ?, ?, 0, 0, 0, 9999999, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?)
        """.trimMargin(), 5) {
          var parameterIndex = 0
          bindString(parameterIndex++, app_version)
          bindString(parameterIndex++, table_name)
          bindString(parameterIndex++, operation)
          bindLong(parameterIndex++, first_seen)
          bindLong(parameterIndex++, last_seen)
        }.await()
    notifyQueries(1_909_304_888) { emit ->
      emit("query_stats")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun mergeQueryStat(
    calls: Long,
    errors: Long,
    total_ms: Long,
    `value`: Long?,
    value_: Long?,
    b1: Long,
    b5: Long,
    b16: Long,
    b50: Long,
    b100: Long,
    b500: Long,
    b_inf: Long,
    value__: Long?,
    app_version: String,
    table_name: String,
    operation: String,
  ): Long {
    val result = driver.execute(2_023_646_501, """
        |UPDATE query_stats SET
        |    calls    = calls    + ?,
        |    errors   = errors   + ?,
        |    total_ms = total_ms + ?,
        |    min_ms   = MIN(min_ms,  ?),
        |    max_ms   = MAX(max_ms,  ?),
        |    b1       = b1    + ?,
        |    b5       = b5    + ?,
        |    b16      = b16   + ?,
        |    b50      = b50   + ?,
        |    b100     = b100  + ?,
        |    b500     = b500  + ?,
        |    b_inf    = b_inf + ?,
        |    last_seen = MAX(last_seen, ?)
        |WHERE app_version = ? AND table_name = ? AND operation = ?
        """.trimMargin(), 16) {
          var parameterIndex = 0
          bindLong(parameterIndex++, calls)
          bindLong(parameterIndex++, errors)
          bindLong(parameterIndex++, total_ms)
          bindLong(parameterIndex++, value)
          bindLong(parameterIndex++, value_)
          bindLong(parameterIndex++, b1)
          bindLong(parameterIndex++, b5)
          bindLong(parameterIndex++, b16)
          bindLong(parameterIndex++, b50)
          bindLong(parameterIndex++, b100)
          bindLong(parameterIndex++, b500)
          bindLong(parameterIndex++, b_inf)
          bindLong(parameterIndex++, value__)
          bindString(parameterIndex++, app_version)
          bindString(parameterIndex++, table_name)
          bindString(parameterIndex++, operation)
        }.await()
    notifyQueries(2_023_646_501) { emit ->
      emit("query_stats")
    }
    return result
  }

  /**
   * @return The number of rows updated.
   */
  public suspend fun deleteQueryStatsForVersion(app_version: String): Long {
    val result = driver.execute(876_124_146, """DELETE FROM query_stats WHERE app_version = ?""", 1) {
          var parameterIndex = 0
          bindString(parameterIndex++, app_version)
        }.await()
    notifyQueries(876_124_146) { emit ->
      emit("query_stats")
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
    poll_interval_minutes: Long,
    auto_commit: Long,
    commit_message_template: String,
  ): Long {
    val result = driver.execute(66_633_213, """
        |INSERT OR REPLACE INTO git_config(
        |    graph_id, repo_root, wiki_subdir, remote_name, remote_branch,
        |    auth_type, ssh_key_path, ssh_key_passphrase_key, https_token_key,
        |    poll_interval_minutes, auto_commit, commit_message_template
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 12) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-493_662_914, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE uuid = ?""", mapper, 1) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-493_890_546, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE name = ? LIMIT 1""", mapper, 1) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_018_316_883, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages ORDER BY name LIMIT ? OFFSET ?""", mapper, 2) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
      bindLong(parameterIndex++, value_)
    }

    override fun toString(): String = "SteleDatabase.sq:selectAllPagesPaginated"
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE namespace ${ if (namespace == null) "IS" else "=" } ? ORDER BY name LIMIT ? OFFSET ?""", mapper, 3) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE namespace ${ if (namespace == null) "IS" else "=" } ? ORDER BY name""", mapper, 1) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(2_012_172_782, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages ORDER BY updated_at DESC LIMIT ?""", mapper, 1) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(193_234_913, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages ORDER BY created_at DESC LIMIT ?""", mapper, 1) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_864_064_170, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE is_journal = 1 AND journal_date IS NOT NULL ORDER BY journal_date DESC LIMIT ? OFFSET ?""", mapper, 2) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(null, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE is_journal = 1 AND journal_date ${ if (journal_date == null) "IS" else "=" } ? LIMIT 1""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, journal_date)
    }

    override fun toString(): String = "SteleDatabase.sq:selectJournalPageByDate"
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(141_501_984, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE name LIKE ?""", mapper, 1) {
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(673_779_853, """SELECT pages.uuid, pages.name, pages.namespace, pages.file_path, pages.created_at, pages.updated_at, pages.properties, pages.version, pages.is_favorite, pages.is_journal, pages.journal_date, pages.is_content_loaded, pages.backlink_count FROM pages WHERE name LIKE ? ORDER BY name LIMIT ? OFFSET ?""", mapper, 3) {
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

  private inner class SelectHistogramForOperationQuery<out T : Any>(
    public val operation_name: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("perf_histogram_buckets", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("perf_histogram_buckets", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_589_019, """SELECT bucket_ms, count FROM perf_histogram_buckets WHERE operation_name = ? ORDER BY bucket_ms""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, operation_name)
    }

    override fun toString(): String = "SteleDatabase.sq:selectHistogramForOperation"
  }

  private inner class SelectDebugFlagQuery<out T : Any>(
    public val key: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("debug_flags", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("debug_flags", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_764_549_726, """SELECT value FROM debug_flags WHERE key = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, key)
    }

    override fun toString(): String = "SteleDatabase.sq:selectDebugFlag"
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

  private inner class SelectRecentSpansQuery<out T : Any>(
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("spans", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("spans", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(681_519_953, """SELECT spans.id, spans.trace_id, spans.span_id, spans.parent_span_id, spans.name, spans.start_epoch_ms, spans.end_epoch_ms, spans.duration_ms, spans.attributes_json, spans.status_code FROM spans ORDER BY start_epoch_ms DESC LIMIT ?""", mapper, 1) {
      var parameterIndex = 0
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectRecentSpans"
  }

  private inner class SelectQueryStatsByVersionQuery<out T : Any>(
    public val app_version: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("query_stats", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("query_stats", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(542_632_205, """SELECT query_stats.app_version, query_stats.table_name, query_stats.operation, query_stats.calls, query_stats.errors, query_stats.total_ms, query_stats.min_ms, query_stats.max_ms, query_stats.b1, query_stats.b5, query_stats.b16, query_stats.b50, query_stats.b100, query_stats.b500, query_stats.b_inf, query_stats.first_seen, query_stats.last_seen FROM query_stats WHERE app_version = ? ORDER BY total_ms DESC""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
    }

    override fun toString(): String = "SteleDatabase.sq:selectQueryStatsByVersion"
  }

  private inner class SelectTopQueryStatsByTotalMsQuery<out T : Any>(
    public val app_version: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("query_stats", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("query_stats", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(-1_979_761_980, """SELECT query_stats.app_version, query_stats.table_name, query_stats.operation, query_stats.calls, query_stats.errors, query_stats.total_ms, query_stats.min_ms, query_stats.max_ms, query_stats.b1, query_stats.b5, query_stats.b16, query_stats.b50, query_stats.b100, query_stats.b500, query_stats.b_inf, query_stats.first_seen, query_stats.last_seen FROM query_stats WHERE app_version = ? ORDER BY total_ms DESC LIMIT ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectTopQueryStatsByTotalMs"
  }

  private inner class SelectTopQueryStatsByCallsQuery<out T : Any>(
    public val app_version: String,
    public val `value`: Long,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("query_stats", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("query_stats", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(410_865_423, """SELECT query_stats.app_version, query_stats.table_name, query_stats.operation, query_stats.calls, query_stats.errors, query_stats.total_ms, query_stats.min_ms, query_stats.max_ms, query_stats.b1, query_stats.b5, query_stats.b16, query_stats.b50, query_stats.b100, query_stats.b500, query_stats.b_inf, query_stats.first_seen, query_stats.last_seen FROM query_stats WHERE app_version = ? ORDER BY calls DESC LIMIT ?""", mapper, 2) {
      var parameterIndex = 0
      bindString(parameterIndex++, app_version)
      bindLong(parameterIndex++, value)
    }

    override fun toString(): String = "SteleDatabase.sq:selectTopQueryStatsByCalls"
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

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> = driver.executeQuery(1_451_609_335, """SELECT git_config.graph_id, git_config.repo_root, git_config.wiki_subdir, git_config.remote_name, git_config.remote_branch, git_config.auth_type, git_config.ssh_key_path, git_config.ssh_key_passphrase_key, git_config.https_token_key, git_config.poll_interval_minutes, git_config.auto_commit, git_config.commit_message_template FROM git_config WHERE graph_id = ?""", mapper, 1) {
      var parameterIndex = 0
      bindString(parameterIndex++, graph_id)
    }

    override fun toString(): String = "SteleDatabase.sq:selectGitConfig"
  }
}
