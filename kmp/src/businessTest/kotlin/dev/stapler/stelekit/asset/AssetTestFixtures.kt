package dev.stapler.stelekit.asset

fun testAsset(
    uuid: String,
    filePath: String = "/graph/assets/images/$uuid.png",
    mediaType: AssetMediaType = AssetMediaType.IMAGE,
    subfolder: String = "images",
    importedAtMs: Long = 0L,
    sizeBytes: Long = 1L,
) = AssetEntry(
    uuid = AssetUuid(uuid),
    filePath = filePath,
    relativePath = "../assets/$subfolder/${filePath.substringAfterLast('/')}",
    mediaType = mediaType,
    subfolder = subfolder,
    tags = emptyList(),
    autoLabels = emptyList(),
    ocrText = null,
    cloudDescription = null,
    pageUuids = emptyList(),
    sizeBytes = sizeBytes,
    importedAtMs = importedAtMs,
    mlProcessed = false,
    mlAttemptedAt = null,
    mlFailed = false,
    contentHash = null,
    isOrphan = false,
    mlTagsSource = "NONE",
)
