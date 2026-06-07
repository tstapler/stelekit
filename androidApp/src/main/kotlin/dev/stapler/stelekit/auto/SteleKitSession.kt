// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class SteleKitSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return AudiobookNoteScreen(carContext)
    }
}
