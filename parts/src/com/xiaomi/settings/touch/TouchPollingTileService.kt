/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xiaomi.settings.R

class TouchPollingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val next = !TouchPollingController.isEnabled(this)
        TouchPollingController.setEnabled(this, next)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = TouchPollingController.isEnabled(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle =
            getString(
                if (enabled) R.string.touch_polling_state_high
                else R.string.touch_polling_state_standard
            )
        tile.updateTile()
    }
}
