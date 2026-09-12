// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.repository.InMemoryImageAnnotationRepository
import dev.stapler.stelekit.ui.gallery.GallerySortOrder
import dev.stapler.stelekit.ui.gallery.GalleryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryViewModelTest {

    private fun makeRepo(vararg annotations: ImageAnnotation): InMemoryImageAnnotationRepository {
        val repo = InMemoryImageAnnotationRepository()
        annotations.forEach { repo.upsert(it) }
        return repo
    }

    private fun makeImage(
        uuid: String,
        tags: List<String> = emptyList(),
        importedAtMs: Long = 0L,
        capturedAtMs: Long? = null,
    ): ImageAnnotation = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-$uuid",
        pageUuid = "page-1",
        graphPath = "/graphs/test",
        filePath = "/graphs/test/assets/images/$uuid.jpg",
        tags = tags,
        importedAtMs = importedAtMs,
        capturedAtMs = capturedAtMs,
    )

    // ── 1. Initial load exposes all images ────────────────────────────────────

    @Test
    fun initialLoad_exposesAllImages() = runBlocking {
        val img1 = makeImage("img-1", importedAtMs = 100)
        val img2 = makeImage("img-2", importedAtMs = 200)
        val repo = makeRepo(img1, img2)
        val vm = GalleryViewModel(repo)

        // Allow the first collection to settle
        val state = vm.state.first { !it.isLoading }
        assertEquals(2, state.images.size, "Expected 2 images in gallery")
        vm.close()
    }

    // ── 2. selectTag filters to matching annotations ──────────────────────────

    @Test
    fun selectTag_filtersImages() = runBlocking {
        val img1 = makeImage("img-1", tags = listOf("kitchen"))
        val img2 = makeImage("img-2", tags = listOf("bathroom"))
        val img3 = makeImage("img-3", tags = listOf("kitchen", "bathroom"))
        val repo = makeRepo(img1, img2, img3)
        val vm = GalleryViewModel(repo)

        vm.selectTag("kitchen")

        val state = vm.state.first { !it.isLoading }
        val filtered = state.images
        assertEquals(2, filtered.size, "Expected 2 images tagged 'kitchen'")
        assertTrue(filtered.any { it.uuid == "img-1" })
        assertTrue(filtered.any { it.uuid == "img-3" })
        assertEquals("kitchen", state.selectedTag)
        vm.close()
    }

    // ── 3. selectTag(null) removes filter ─────────────────────────────────────

    @Test
    fun selectTag_null_clearsFilter() = runBlocking {
        val img1 = makeImage("img-1", tags = listOf("kitchen"))
        val img2 = makeImage("img-2", tags = listOf("bathroom"))
        val repo = makeRepo(img1, img2)
        val vm = GalleryViewModel(repo)

        vm.selectTag("kitchen")
        vm.selectTag(null)

        val state = vm.state.first { !it.isLoading && it.selectedTag == null }
        assertEquals(2, state.images.size, "Expected all images after clearing filter")
        assertNull(state.selectedTag)
        vm.close()
    }

    // ── 4. Sort BY_DATE_IMPORTED orders newest-first ──────────────────────────

    @Test
    fun sortByImportDate_newestFirst() = runBlocking {
        val img1 = makeImage("img-1", importedAtMs = 100)
        val img2 = makeImage("img-2", importedAtMs = 300)
        val img3 = makeImage("img-3", importedAtMs = 200)
        val repo = makeRepo(img1, img2, img3)
        val vm = GalleryViewModel(repo)

        // Wait for the constructor's own async initial load to settle before calling
        // setSortOrder — see sortByCaptureDate_newestFirst's identical rationale below.
        vm.state.first { !it.isLoading }
        vm.setSortOrder(GallerySortOrder.BY_DATE_IMPORTED)

        val state = vm.state.first { !it.isLoading && it.sortOrder == GallerySortOrder.BY_DATE_IMPORTED }
        val uuids = state.images.map { it.uuid }
        assertEquals(listOf("img-2", "img-3", "img-1"), uuids, "Expected newest-import first")
        vm.close()
    }

    // ── 5. Sort BY_DATE_CAPTURED uses capturedAtMs ────────────────────────────

    @Test
    fun sortByCaptureDate_newestFirst() = runBlocking {
        val img1 = makeImage("img-1", capturedAtMs = 50)
        val img2 = makeImage("img-2", capturedAtMs = 200)
        val img3 = makeImage("img-3", capturedAtMs = null) // no capture date
        val repo = makeRepo(img1, img2, img3)
        val vm = GalleryViewModel(repo)

        // Root cause of this test's flakiness: GalleryViewModel's init{} launches loadImages()
        // on a real background dispatcher (Dispatchers.Default), which can genuinely run in
        // parallel with this test's own thread — there is no happens-before relationship between
        // construction returning and the initial load's first collect. setSortOrder() (unlike
        // selectTag()) does not restart loadImages() and so has no generation-guard protection;
        // it reads/writes `sortOrder` directly. If the initial load's collect callback reads
        // `_state.value.sortOrder` (still the constructor default, BY_DATE_IMPORTED) *after*
        // setSortOrder had already flipped it to BY_DATE_CAPTURED but *before* setSortOrder's own
        // update was applied, the final state observed by `.first { !it.isLoading }` can be the
        // initial load's emission sorted by the wrong (default) order — sortByImportDate's
        // equivalent race is masked only because BY_DATE_IMPORTED happens to already be the
        // default. Fix: wait for the initial load to fully settle before ever calling
        // setSortOrder, removing the race instead of hoping the ordering falls the right way.
        vm.state.first { !it.isLoading }
        vm.setSortOrder(GallerySortOrder.BY_DATE_CAPTURED)

        val state = vm.state.first { !it.isLoading && it.sortOrder == GallerySortOrder.BY_DATE_CAPTURED }
        // Images with capture date come first (newest first), then null-capture images
        val uuids = state.images.map { it.uuid }
        assertTrue(uuids.indexOf("img-2") < uuids.indexOf("img-1"), "img-2 (newer) should precede img-1")
        vm.close()
    }

    // ── 6. Default sort order is BY_DATE_IMPORTED ─────────────────────────────

    @Test
    fun defaultSortOrder_isByDateImported() = runBlocking {
        val repo = makeRepo()
        val vm = GalleryViewModel(repo)
        val state = vm.state.first()
        assertEquals(GallerySortOrder.BY_DATE_IMPORTED, state.sortOrder)
        vm.close()
    }

    // ── 7. Empty repo produces empty state ────────────────────────────────────

    @Test
    fun emptyRepo_emptyState() = runBlocking {
        val vm = GalleryViewModel(InMemoryImageAnnotationRepository())
        val state = vm.state.first { !it.isLoading }
        assertTrue(state.images.isEmpty())
        vm.close()
    }
}
