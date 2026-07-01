package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionFilter
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests that the section-aware page drain respects SectionFilter.
 *
 * GraphLoader.indexRemainingPages() calls:
 *   pageRepository.getUnloadedPagesBySection(subscribedIds + "", batchSize, offset).first()
 *
 * InMemoryPageRepository's default getUnloadedPagesBySection ignores sectionIds (delegates
 * to getUnloadedPages). SectionAwarePageRepository overrides it to actually filter — this
 * models the SQL-backed production behavior.
 *
 * TC-6.4-A: SectionFilter["acme-work"] → only "acme-work" and "" stubs are drained.
 * TC-6.4-B: No SectionFilter → all stubs visible via getUnloadedPages (no filter).
 * TC-6.4-C: Active section + global stubs are both drained.
 */
@OptIn(DirectRepositoryWrite::class)
class IndexDrainSectionFilterTest {

    /**
     * PageRepository that actually filters getUnloadedPagesBySection by sectionId,
     * modelling the behavior of the SQL-backed implementation.
     *
     * Uses delegation (PageRepository by inner) because InMemoryPageRepository is final.
     * Keeps a reference to `inner` so we can filter its getUnloadedPages output.
     *
     * The hot StateFlow backing InMemoryPageRepository never terminates, so callers
     * use .first() — same as indexRemainingPages does in production.
     */
    @OptIn(DirectRepositoryWrite::class)
    private class SectionAwarePageRepository(
        private val inner: InMemoryPageRepository = InMemoryPageRepository(),
    ) : PageRepository by inner {
        override fun getUnloadedPagesBySection(
            sectionIds: Collection<String>,
            limit: Int,
            offset: Int,
        ): Flow<Either<DomainError, List<Page>>> =
            inner.getUnloadedPages(limit = Int.MAX_VALUE / 2, offset = 0).map { result ->
                when (result) {
                    is Either.Right -> result.value
                        .filter { it.sectionId in sectionIds }
                        .drop(offset)
                        .take(limit)
                        .right()
                    is Either.Left -> result
                }
            }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun stubPage(name: String, sectionId: String): Page = Page(
        uuid = PageUuid(UuidGenerator.generateV7()),
        name = name,
        filePath = "/graph/pages/$name.md",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        isJournal = false,
        isContentLoaded = false,
        sectionId = sectionId,
    )

    // ── TC-6.4-A ─────────────────────────────────────────────────────────────

    @Test
    fun `TC-6_4-A SectionFilter drains only subscribed and global stubs`() = runTest {
        val acmeSection = SectionDefinition(
            id = "acme-work",
            displayName = "Acme Work",
            pagePathPrefix = "pages/acme-work",
            journalPathPrefix = "journals/acme-work",
        )
        val personalSection = SectionDefinition(
            id = "personal",
            displayName = "Personal",
            pagePathPrefix = "pages/personal",
            journalPathPrefix = "journals/personal",
        )
        // personal is HIDDEN — only acme-work is subscribed
        val sectionFilter = SectionFilter(
            SectionManifest(sections = listOf(acmeSection, personalSection)),
            mapOf("acme-work" to SectionState.ACTIVE, "personal" to SectionState.HIDDEN),
        )

        val pageRepo = SectionAwarePageRepository()
        pageRepo.savePages(listOf(
            stubPage("work-note",   sectionId = "acme-work"),
            stubPage("global-note", sectionId = ""),
            stubPage("diary",       sectionId = "personal"),
        ))

        assertEquals(setOf("acme-work"), sectionFilter.subscribedSectionIds(),
            "only non-hidden sections are subscribed")

        // Mirror what indexRemainingPages builds: subscribedIds + ""
        val drainIds = sectionFilter.subscribedSectionIds() + setOf("")
        val drained = pageRepo.getUnloadedPagesBySection(drainIds, 100, 0).first()
            .getOrNull().orEmpty()
        val drainedNames = drained.map { it.name }.toSet()

        assertTrue("work-note" in drainedNames, "acme-work stub must be drained")
        assertTrue("global-note" in drainedNames, "global (sectionId='') stub must be drained")
        assertTrue("diary" !in drainedNames, "personal stub must NOT be drained (section HIDDEN)")
        assertEquals(2, drained.size)
    }

    // ── TC-6.4-B ─────────────────────────────────────────────────────────────

    @Test
    fun `TC-6_4-B no SectionFilter all stubs are visible to the drain loop`() = runTest {
        val pageRepo = SectionAwarePageRepository()
        pageRepo.savePages(listOf(
            stubPage("work-note",   sectionId = "acme-work"),
            stubPage("global-note", sectionId = ""),
            stubPage("diary",       sectionId = "personal"),
        ))

        // No SectionFilter → indexRemainingPages calls getUnloadedPages (no section filter).
        val unfiltered = pageRepo.getUnloadedPages(100, 0).first().getOrNull().orEmpty()

        assertEquals(3, unfiltered.size,
            "without SectionFilter all stubs are visible to the drain loop")
    }

    // ── TC-6.4-C ─────────────────────────────────────────────────────────────

    @Test
    fun `TC-6_4-C active section and global stubs are both drained`() = runTest {
        val acmeSection = SectionDefinition(
            id = "acme-work",
            displayName = "Acme Work",
            pagePathPrefix = "pages/acme-work",
            journalPathPrefix = "journals/acme-work",
        )
        val sectionFilter = SectionFilter(
            SectionManifest(sections = listOf(acmeSection)),
            mapOf("acme-work" to SectionState.ACTIVE),
        )

        val pageRepo = SectionAwarePageRepository()
        pageRepo.savePages(listOf(
            stubPage("work-a", sectionId = "acme-work"),
            stubPage("work-b", sectionId = "acme-work"),
            stubPage("global", sectionId = ""),
        ))

        val drainIds = sectionFilter.subscribedSectionIds() + setOf("")
        val drained = pageRepo.getUnloadedPagesBySection(drainIds, 100, 0).first()
            .getOrNull().orEmpty()

        assertEquals(3, drained.size,
            "both acme-work and global (sectionId='') stubs must be drained")
    }
}
