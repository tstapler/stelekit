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
import dev.stapler.stelekit.model.Page as DomainPage

/**
 * SQLDelight implementation of PageRepository using the shared data model
 */
class SqlDelightPageRepository(
    private val database: SteleDatabase
) : PageRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findById(id: Long): DomainPage? {
        return database.pagesQueries.selectPageById(id).executeAsOneOrNull()?.toDomainPage()
    }

    override suspend fun findAll(): List<DomainPage> {
        return database.pagesQueries.selectAllPages().executeAsList().map { it.toDomainPage() }
    }

    override suspend fun findAllPaginated(pagination: Pagination): Page<DomainPage> {
        val totalCount = database.pagesQueries.countPages().executeAsOne()
        val items = database.pagesQueries.selectAllPagesPaginated(pagination.limit.toLong(), pagination.offset.toLong())
            .executeAsList()
            .map { it.toDomainPage() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun save(entity: DomainPage): DomainPage {
        val pageEntity = entity.toDbPage()
        database.pagesQueries.insert(pageEntity)
        return entity
    }

    override suspend fun saveAll(entities: List<DomainPage>): List<DomainPage> {
        entities.forEach { entity ->
            val pageEntity = entity.toDbPage()
            database.pagesQueries.insert(pageEntity)
        }
        return entities
    }

    override suspend fun deleteById(id: Long): Boolean {
        database.pagesQueries.deletePageById(id)
        return true
    }

    override suspend fun existsById(id: Long): Boolean {
        return database.pagesQueries.existsPageById(id).executeAsOne() > 0
    }

    override suspend fun count(): Long {
        return database.pagesQueries.countPages().executeAsOne()
    }

    override suspend fun findByUuid(uuid: String): DomainPage? {
        return database.pagesQueries.selectPageByUuid(uuid).executeAsOneOrNull()?.toDomainPage()
    }

    override suspend fun existsByUuid(uuid: String): Boolean {
        return database.pagesQueries.existsPageByUuid(uuid).executeAsOne() > 0
    }

    override suspend fun findByName(name: String): DomainPage? {
        return database.pagesQueries.selectPageByName(name).executeAsOneOrNull()?.toDomainPage()
    }

    override suspend fun existsByName(name: String): Boolean {
        return database.pagesQueries.existsPageByName(name).executeAsOne() > 0
    }

    override suspend fun delete(entity: DomainPage): Boolean {
        return deleteById(entity.id)
    }

    override suspend fun existsById(id: Long): Boolean {
        return database.pagesQueries.existsById(id).executeAsOne() > 0
    }

    override suspend fun count(): Long {
        return database.pagesQueries.count().executeAsOne()
    }

    override suspend fun findByUuid(uuid: String): DomainPage? {
        return database.pagesQueries.selectByUuid(uuid).executeAsOneOrNull()?.toDomainPage()
    }

    override suspend fun existsByUuid(uuid: String): Boolean {
        return database.pagesQueries.existsByUuid(uuid).executeAsOne() > 0
    }

    override suspend fun findByName(name: String): DomainPage? {
        return database.pagesQueries.selectByName(name).executeAsOneOrNull()?.toDomainPage()
    }

    override suspend fun existsByName(name: String): Boolean {
        return database.pagesQueries.existsByName(name).executeAsOne() > 0
    }

    override suspend fun findJournals(pagination: Pagination): Page<DomainPage> {
        // TODO: Implement journal filtering
        return Page(emptyList(), 0, false)
    }

    override suspend fun findJournalByDay(day: Long): DomainPage? {
        // TODO: Implement journal day lookup
        return null
    }

    override suspend fun findJournalsInRange(startDay: Long, endDay: Long): List<DomainPage> {
        // TODO: Implement journal range lookup
        return emptyList()
    }

    override suspend fun findPagesInNamespace(namespace: String, pagination: Pagination): Page<DomainPage> {
        val totalCount = database.pagesQueries.countPagesByNamespace(namespace).executeAsOne()
        val items = database.pagesQueries.selectPagesByNamespace(namespace, pagination.limit.toLong(), pagination.offset.toLong())
            .executeAsList()
            .map { it.toDomainPage() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun findChildPages(parentPageId: Long, pagination: Pagination): Page<DomainPage> {
        // TODO: Implement namespace-based child page lookup
        return Page(emptyList(), 0, false)
    }

    override suspend fun findParentPages(childPageId: Long): List<DomainPage> {
        // TODO: Implement namespace-based parent page lookup
        return emptyList()
    }

    override suspend fun search(criteria: PageSearchCriteria, pagination: Pagination): Page<DomainPage> {
        // TODO: Implement search functionality
        return Page(emptyList(), 0, false)
    }

    override suspend fun searchByName(query: String, pagination: Pagination): Page<DomainPage> {
        // TODO: Implement name search
        return Page(emptyList(), 0, false)
    }

    override suspend fun searchByProperties(properties: Map<String, String>, pagination: Pagination): Page<DomainPage> {
        // TODO: Implement property search
        return Page(emptyList(), 0, false)
    }

    override suspend fun findPagesWithBlocks(blockIds: List<Long>): List<DomainPage> {
        return blockIds.flatMap { blockId ->
            database.pagesQueries.selectPagesByBlockIds(blockId).executeAsList().map { it.toDomainPage() }
        }.distinct()
    }

    override suspend fun findRecentlyUpdated(pagination: Pagination): Page<DomainPage> {
        val totalCount = database.pagesQueries.countPages().executeAsOne()
        val items = database.pagesQueries.selectRecentlyUpdatedPages(pagination.limit.toLong())
            .executeAsList()
            .map { it.toDomainPage() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun findRecentlyCreated(pagination: Pagination): Page<DomainPage> {
        val totalCount = database.pagesQueries.countPages().executeAsOne()
        val items = database.pagesQueries.selectRecentlyCreatedPages(pagination.limit.toLong())
            .executeAsList()
            .map { it.toDomainPage() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun updateProperties(pageId: Long, properties: Map<String, String>): DomainPage? {
        val propertiesJson = json.encodeToString(properties)
        database.pagesQueries.updatePageProperties(propertiesJson, pageId)
        return findById(pageId)
    }

    override suspend fun renamePage(pageId: Long, newName: String): DomainPage? {
        database.pagesQueries.updatePageName(newName, pageId)
        return findById(pageId)
    }

    override suspend fun findRecentlyUpdated(pagination: Pagination): Page<DomainPage> {
        val totalCount = database.pagesQueries.count().executeAsOne()
        val items = database.pagesQueries.selectRecentlyUpdated(pagination.limit.toLong())
            .executeAsList()
            .map { it.toDomainPage() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun findRecentlyCreated(pagination: Pagination): Page<DomainPage> {
        val totalCount = database.pagesQueries.count().executeAsOne()
        val items = database.pagesQueries.selectRecentlyCreated(pagination.limit.toLong())
            .executeAsList()
            .map { it.toDomainPage() }

        return Page(items, totalCount.toInt(), items.size >= pagination.limit)
    }

    override suspend fun updateProperties(pageId: Long, properties: Map<String, String>): DomainPage? {
        val propertiesJson = json.encodeToString(properties)
        database.pagesQueries.updateProperties(propertiesJson, pageId)
        return findById(pageId)
    }

    override suspend fun getProperties(pageId: Long): Map<String, String> {
        val page = findById(pageId)
        return page?.properties ?: emptyMap()
    }

    override suspend fun renamePage(pageId: Long, newName: String): DomainPage? {
        database.pagesQueries.updateName(newName, pageId)
        return findById(pageId)
    }

    override fun observePage(pageId: Long): Flow<DomainPage?> {
        return database.pagesQueries.selectPageById(pageId)
            .asFlow()
            .mapToOneOrNull()
            .map { it?.toDomainPage() }
    }

    override fun observePagesByNamespace(namespace: String): Flow<List<DomainPage>> {
        return database.pagesQueries.selectPagesByNamespaceUnpaginated(namespace)
            .asFlow()
            .mapToList()
            .map { it.map { dbPage -> dbPage.toDomainPage() } }
    }

    override fun observeRecentlyUpdated(): Flow<List<DomainPage>> {
        return database.pagesQueries.selectRecentlyUpdatedPages(20)
            .asFlow()
            .mapToList()
            .map { it.map { dbPage -> dbPage.toDomainPage() } }
    }

    override suspend fun resolvePageName(name: String): String {
        // Normalize page name for lookup
        return name.trim().lowercase()
    }

    override suspend fun normalizePageName(name: String): String {
        return name.trim()
    }

    override suspend fun generateUniqueName(baseName: String): String {
        val normalized = normalizePageName(baseName)
        if (!existsByName(normalized)) {
            return normalized
        }

        var counter = 1
        var uniqueName: String
        do {
            uniqueName = "${normalized}_${counter}"
            counter++
        } while (existsByName(uniqueName))

        return uniqueName
    }

    // Extension functions for mapping between domain and database models
    private fun dev.stapler.stelekit.db.Pages.toDomainPage(): DomainPage {
        val extendedProps = properties?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()

        return DomainPage(
            id = id,
            uuid = uuid,
            name = name,
            title = extendedProps["title"] ?: name,
            namespace = extendedProps["namespace"],
            filePath = file_path,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = extendedProps,
            collapsed = extendedProps["collapsed"]?.toBoolean() ?: false,
            journalDay = extendedProps["journalDay"]?.toLong(),
            aliases = extendedProps["aliases"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            tags = extendedProps["tags"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            refs = extendedProps["refs"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            propertiesOrder = extendedProps["propertiesOrder"]?.split(",") ?: emptyList(),
            propertiesTextValues = extendedProps
        )
    }

    private fun DomainPage.toDbPage(): dev.stapler.stelekit.db.Pages {
        val extendedProps = mapOf(
            "title" to title,
            "namespace" to namespace,
            "collapsed" to collapsed.toString(),
            "journalDay" to journalDay?.toString(),
            "aliases" to aliases.joinToString(","),
            "tags" to tags.joinToString(","),
            "refs" to refs.joinToString(","),
            "propertiesOrder" to propertiesOrder.joinToString(",")
        ).filterValues { it != null } + propertiesTextValues

        return dev.stapler.stelekit.db.Pages(
            id = id,
            uuid = uuid,
            name = name,
            namespace = namespace,
            file_path = filePath,
            created_at = createdAt.toEpochMilliseconds(),
            updated_at = updatedAt.toEpochMilliseconds(),
            properties = json.encodeToString(extendedProps),
            version = version
        )
    }
}