// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.assets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The detail screen shows an image asset's viewer; image loading itself is covered by TieredImageModelTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(DirectRepositoryWrite::class)
class AssetDetailScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val asset = AssetEntry(
        uuid = AssetUuid("asset-001"),
        filePath = "/graph/assets/images/photo.jpg",
        relativePath = "../assets/images/photo.jpg",
        mediaType = AssetMediaType.IMAGE,
        subfolder = "images",
        tags = emptyList(),
        autoLabels = emptyList(),
        ocrText = null,
        cloudDescription = null,
        pageUuids = emptyList(),
        sizeBytes = 1024L,
        importedAtMs = 0L,
        mlProcessed = false,
        mlAttemptedAt = null,
        mlFailed = false,
        contentHash = null,
        isOrphan = true,
        mlTagsSource = "none",
    )

    @Test
    fun `image asset renders the viewer image`() {
        val repo = InMemoryAssetRepository().also { runBlocking { it.saveAsset(asset) } }
        val viewModel = AssetDetailViewModel(assetRepository = repo, assetUuid = asset.uuid)
        rule.setContent {
            AssetDetailScreen(viewModel = viewModel, onNavigateBack = {}, onNavigateToPage = {})
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithContentDescription("photo.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("photo.jpg").assertIsDisplayed()
    }
}
