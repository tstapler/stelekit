package dev.stapler.stelekit.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.stapler.stelekit.model.Block as DomainBlock

/**
 * SQLDelight implementation of BlockRepository using the shared data model
 */
class SqlDelightBlockRepository(
    private val database: SteleDatabase
) : BlockRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findById(id: Long): DomainBlock? {
        return database.blocksQueries.selectBlockById(id).executeAsOneOrNull()?.toDomainBlock()
    }

    override suspend fun findAll(): List<DomainBlock> {
        return database.blocksQueries.selectAllBlocks().executeAsList().map { it.toDomainBlock() }
    }

    override suspend fun findAllPaginated(pagination: Pagination): Page<DomainBlock> {
        val totalCount = database.blocksQueries.countBlocks().executeAsOne()
        val items = database.blocksQueries.selectAllBlocksPaginated(pagination.limit.toLong(), pagination.offset.toLong())
            .executeAsList()
            .map { it.toDomainBlock() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun save(entity: DomainBlock): DomainBlock {
        val blockEntity = entity.toDbBlock()
        database.blocksQueries.insert(blockEntity)
        return entity
    }

    override suspend fun saveAll(entities: List<DomainBlock>): List<DomainBlock> {
        entities.forEach { entity ->
            val blockEntity = entity.toDbBlock()
            database.blocksQueries.insert(blockEntity)
        }
        return entities
    }

    override suspend fun deleteById(id: Long): Boolean {
        database.blocksQueries.deleteBlockById(id)
        return true
    }

    override suspend fun existsById(id: Long): Boolean {
        return database.blocksQueries.existsBlockById(id).executeAsOne() > 0
    }

    override suspend fun count(): Long {
        return database.blocksQueries.countBlocks().executeAsOne()
    }

    override suspend fun findByUuid(uuid: String): DomainBlock? {
        return database.blocksQueries.selectBlockByUuid(uuid).executeAsOneOrNull()?.toDomainBlock()
    }

    override suspend fun findByUuids(uuids: List<String>): List<DomainBlock> {
        return uuids.mapNotNull { findByUuid(it) }
    }

    override suspend fun existsByUuid(uuid: String): Boolean {
        return database.blocksQueries.existsBlockByUuid(uuid).executeAsOne() > 0
    }

    override suspend fun delete(entity: DomainBlock): Boolean {
        return deleteById(entity.id)
    }

    override suspend fun existsById(id: Long): Boolean {
        return database.blocksQueries.existsById(id).executeAsOne() > 0
    }

    override suspend fun count(): Long {
        return database.blocksQueries.count().executeAsOne()
    }

    override suspend fun findByUuid(uuid: String): DomainBlock? {
        return database.blocksQueries.selectByUuid(uuid).executeAsOneOrNull()?.toDomainBlock()
    }

    override suspend fun findByUuids(uuids: List<String>): List<DomainBlock> {
        return uuids.mapNotNull { findByUuid(it) }
    }

    override suspend fun existsByUuid(uuid: String): Boolean {
        return database.blocksQueries.existsByUuid(uuid).executeAsOne() > 0
    }

    override suspend fun findChildren(parentId: Long, pagination: Pagination): Page<DomainBlock> {
        val totalCount = database.blocksQueries.countBlockChildren(parentId).executeAsOne()
        val items = database.blocksQueries.selectBlockChildren(parentId, pagination.limit.toLong(), pagination.offset.toLong())
            .executeAsList()
            .map { it.toDomainBlock() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun findSiblings(blockId: Long): List<DomainBlock> {
        return database.blocksQueries.selectBlockSiblings(blockId).executeAsList().map { it.toDomainBlock() }
    }

    override suspend fun findAncestors(blockId: Long): List<DomainBlock> {
        // TODO: Implement with CTE when schema supports it
        return emptyList()
    }

    override suspend fun findDescendants(blockId: Long, maxDepth: Int?): List<DomainBlock> {
        // TODO: Implement with CTE when schema supports it
        return emptyList()
    }

    override suspend fun findRootBlocks(pageId: Long, pagination: Pagination): Page<DomainBlock> {
        val totalCount = database.blocksQueries.countRootBlocks(pageId).executeAsOne()
        val items = database.blocksQueries.selectRootBlocks(pageId, pagination.limit.toLong(), pagination.offset.toLong())
            .executeAsList()
            .map { it.toDomainBlock() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun search(criteria: BlockSearchCriteria, pagination: Pagination): Page<DomainBlock> {
        // TODO: Implement search functionality
        return Page(emptyList(), 0, false)
    }

    override suspend fun searchByContent(query: String, pagination: Pagination): Page<DomainBlock> {
        // TODO: Implement content search
        return Page(emptyList(), 0, false)
    }

    override suspend fun searchByProperties(properties: Map<String, String>, pagination: Pagination): Page<DomainBlock> {
        // TODO: Implement property search
        return Page(emptyList(), 0, false)
    }

    override suspend fun findReferences(blockId: Long): List<DomainBlock> {
        return database.blocksQueries.selectBlockReferences(blockId).executeAsList().map { it.toDomainBlock() }
    }

    override suspend fun findBackReferences(blockId: Long): List<DomainBlock> {
        return database.blocksQueries.selectBlockBackReferences(blockId).executeAsList().map { it.toDomainBlock() }
    }

    override suspend fun findBlocksByPage(pageId: Long, pagination: Pagination): Page<DomainBlock> {
        val totalCount = database.blocksQueries.countBlocksByPageId(pageId).executeAsOne()
        val items = database.blocksQueries.selectBlocksByPageId(pageId, pagination.limit.toLong(), pagination.offset.toLong())
            .executeAsList()
            .map { it.toDomainBlock() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun countBlocksByPage(pageId: Long): Long {
        return database.blocksQueries.countBlocksByPageId(pageId).executeAsOne()
    }

    override suspend fun findTasks(marker: String?, pagination: Pagination): Page<DomainBlock> {
        // TODO: Implement task filtering
        return Page(emptyList(), 0, false)
    }

    override suspend fun findTasksByPage(pageId: Long, marker: String?): List<DomainBlock> {
        // TODO: Implement task filtering by page
        return emptyList()
    }

    override suspend fun updateParent(blockIds: List<Long>, newParentId: Long?): List<DomainBlock> {
        blockIds.forEach { blockId ->
            database.blocksQueries.updateBlockParent(newParentId, blockId)
        }
        return blockIds.mapNotNull { findById(it) }
    }

    override suspend fun updateProperties(blockId: Long, properties: Map<String, String>): DomainBlock? {
        val propertiesJson = json.encodeToString(properties)
        database.blocksQueries.updateBlockProperties(propertiesJson, blockId)
        return findById(blockId)
    }
        return blockIds.mapNotNull { findById(it) }
    }

    override suspend fun moveBlocks(blockIds: List<Long>, targetParentId: Long?, targetLeftId: Long?): List<DomainBlock> {
        // TODO: Implement block moving logic
        return emptyList()
    }

    override suspend fun collapseBlocks(blockIds: List<Long>, collapsed: Boolean): List<DomainBlock> {
        // TODO: Implement collapse functionality
        return emptyList()
    }

    override suspend fun updateProperties(blockId: Long, properties: Map<String, String>): DomainBlock? {
        val propertiesJson = json.encodeToString(properties)
        database.blocksQueries.updateProperties(propertiesJson, blockId)
        return findById(blockId)
    }

    override suspend fun getProperties(blockId: Long): Map<String, String> {
        val block = findById(blockId)
        return block?.properties ?: emptyMap()
    }

    override fun observeBlock(blockId: Long): Flow<DomainBlock?> {
        return database.blocksQueries.selectBlockById(blockId)
            .asFlow()
            .mapToOneOrNull()
            .map { it?.toDomainBlock() }
    }

    override fun observeBlocksByPage(pageId: Long): Flow<List<DomainBlock>> {
        return database.blocksQueries.selectBlocksByPageIdUnpaginated(pageId)
            .asFlow()
            .mapToList()
            .map { it.map { dbBlock -> dbBlock.toDomainBlock() } }
    }

    override fun observeSearchResults(criteria: BlockSearchCriteria): Flow<List<DomainBlock>> {
        // TODO: Implement reactive search
        return kotlinx.coroutines.flow.flow { emit(emptyList()) }
    }

    // Extension functions for mapping between domain and database models
    private fun dev.stapler.stelekit.db.Blocks.toDomainBlock(): DomainBlock {
        val extendedProps = properties?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

        return DomainBlock(
            id = id,
            uuid = uuid,
            pageId = page_id,
            parentId = parent_id,
            leftId = left_id,
            content = content,
            level = level.toInt(),
            position = position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = extendedProps,
            // Extended properties from JSON - simplified for now
            title = extendedProps["title"],
            name = extendedProps["name"],
            collapsed = extendedProps["collapsed"]?.toBoolean() ?: false,
            marker = extendedProps["marker"],
            priority = extendedProps["priority"],
            scheduled = extendedProps["scheduled"]?.toLong(),
            deadline = extendedProps["deadline"]?.toLong(),
            repeated = extendedProps["repeated"]?.toBoolean() ?: false,
            journalDay = extendedProps["journalDay"]?.toLong(),
            format = extendedProps["format"] ?: "markdown",
            type = extendedProps["type"],
            preBlock = extendedProps["preBlock"]?.toBoolean() ?: false,
            refs = extendedProps["refs"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            tags = extendedProps["tags"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            aliases = extendedProps["aliases"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            macros = extendedProps["macros"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            fileId = extendedProps["fileId"]?.toLong(),
            txId = extendedProps["txId"]?.toLong(),
            propertiesOrder = extendedProps["propertiesOrder"]?.split(",") ?: emptyList(),
            propertiesTextValues = extendedProps
        )
    }

    private fun DomainBlock.toDbBlock(): dev.stapler.stelekit.db.Blocks {
        val extendedProps = mapOf(
            "title" to title,
            "name" to name,
            "collapsed" to collapsed.toString(),
            "marker" to marker,
            "priority" to priority,
            "scheduled" to scheduled?.toString(),
            "deadline" to deadline?.toString(),
            "repeated" to repeated.toString(),
            "journalDay" to journalDay?.toString(),
            "format" to format,
            "type" to type,
            "preBlock" to preBlock.toString(),
            "refs" to refs.joinToString(","),
            "tags" to tags.joinToString(","),
            "aliases" to aliases.joinToString(","),
            "macros" to macros.joinToString(","),
            "fileId" to fileId?.toString(),
            "txId" to txId?.toString(),
            "propertiesOrder" to propertiesOrder.joinToString(",")
        ).filterValues { it != null } + propertiesTextValues

        return dev.stapler.stelekit.db.Blocks(
            id = id,
            uuid = uuid,
            page_id = pageId,
            parent_id = parentId,
            left_id = leftId,
            content = content,
            level = level.toLong(),
            position = position.toLong(),
            created_at = createdAt.toEpochMilliseconds(),
            updated_at = updatedAt.toEpochMilliseconds(),
            properties = json.encodeToString(extendedProps),
            version = version
        )
    }
}