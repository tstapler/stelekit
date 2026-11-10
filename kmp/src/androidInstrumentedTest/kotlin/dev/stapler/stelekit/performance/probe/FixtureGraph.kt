// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.system.Os
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.fixtures.FixtureGraphSeeds
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import java.io.File
import java.io.FileOutputStream

/**
 * Seeds directly through the repositories (opt-in): this runs before any actor exists and writes only synthetic rows.
 * Android wrapper over [FixtureGraphSeeds]: writes the 20 distinct 4000x3000 JPEGs once and inserts
 * the shared rows through the real repositories. Everything is named `synthetic-<n>` (plan B.6d).
 */
@OptIn(DirectRepositoryWrite::class)
class FixtureGraph(private val context: Context) {
    val root: File = File(context.filesDir, "graphs/synthetic-${System.nanoTime()}")
    private val dbName = "synthetic-fixture-${System.nanoTime()}.db"
    lateinit var assets: SqlDelightAssetRepository
        private set
    lateinit var annotations: SqlDelightImageAnnotationRepository
        private set

    /** The real file behind base index [index]; every row links to one of these. */
    fun jpegFile(index: Int): File = File(root, "assets/${FixtureGraphSeeds.baseName(index)}")

    suspend fun seed(annotationRows: Int = FixtureGraphSeeds.ANNOTATION_ROWS, assetRows: Int = FixtureGraphSeeds.ASSET_ROWS) {
        DriverFactory.setContext(context)
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite:$dbName"))
        assets = SqlDelightAssetRepository(database)
        annotations = SqlDelightImageAnnotationRepository(database)
        writeJpegs()
        linkRows(maxOf(annotationRows, assetRows))
        val graphPath = root.absolutePath
        FixtureGraphSeeds.assetEntries(assetRows, graphPath).forEach { assets.saveAsset(it) }
        FixtureGraphSeeds.imageAnnotations(annotationRows, graphPath).forEach { annotations.saveImageAnnotation(it) }
    }

    /** One symlink per row name so the unique `file_path` rows all decode one of the base files. */
    private fun linkRows(rows: Int) {
        for (n in 0 until rows) {
            Os.symlink(jpegFile(FixtureGraphSeeds.baseIndex(n)).absolutePath, File(root, "assets/${FixtureGraphSeeds.rowName(n)}").absolutePath)
        }
    }

    fun close() {
        root.deleteRecursively()
        context.deleteDatabase(dbName)
    }

    private fun writeJpegs() {
        File(root, "assets").mkdirs()
        val bitmap = Bitmap.createBitmap(FixtureGraphSeeds.JPEG_WIDTH_PX, FixtureGraphSeeds.JPEG_HEIGHT_PX, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(FixtureGraphSeeds.DISTINCT_JPEGS) { i ->
                paint.color = Color.HSVToColor(floatArrayOf(i * HUE_STEP, 0.6f, 0.9f))
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                FileOutputStream(jpegFile(i)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val HUE_STEP = 18f
        const val JPEG_QUALITY = 80
    }
}
