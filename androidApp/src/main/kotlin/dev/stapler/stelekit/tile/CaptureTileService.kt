// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import dev.stapler.stelekit.CaptureActivity
import dev.stapler.stelekit.MainActivity
import dev.stapler.stelekit.SteleKitApplication
import dev.stapler.stelekit.app.R

@RequiresApi(Build.VERSION_CODES.N)
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        try {
            qsTile?.apply {
                state = Tile.STATE_ACTIVE
                label = getString(R.string.tile_label_capture)
                icon = Icon.createWithResource(applicationContext, R.drawable.ic_tile_capture)
                updateTile()
            }
        } catch (_: Exception) { /* tile may not be bound */ }
    }

    @SuppressLint("NewApi") // version-guarded inline below
    override fun onClick() {
        super.onClick()
        val app = applicationContext as? SteleKitApplication
        val targetClass = if (app?.graphManager != null) CaptureActivity::class.java
                          else MainActivity::class.java
        val intent = Intent(this, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Bug 6 mitigation: use PendingIntent overload on API 34+, Intent overload on older
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
